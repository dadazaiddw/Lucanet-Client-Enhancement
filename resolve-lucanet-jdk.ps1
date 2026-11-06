[CmdletBinding()]
param(
    [string]$LucanetHome = 'D:\Soft\LucaNet',
    [string]$JdkHome = '',
    [ValidateRange(11, 99)][int]$MinimumMajorVersion = 11
)
$ErrorActionPreference = 'Stop'

# 所有构建/测试入口共用此规则。只读 Software Manager 安装目录的 .jdk，
# 不读取 JAVA_HOME、不从 PATH 搜索 java，不更改 Lucanet 自身的运行时配置。
$installation = (Resolve-Path -LiteralPath $LucanetHome -ErrorAction Stop).Path
$jdkRoot = Join-Path $installation '.jdk'
if (-not (Test-Path -LiteralPath $jdkRoot -PathType Container)) {
    throw "Lucanet JDK directory not found: $jdkRoot. Specify -LucanetHome with the Software Manager installation directory."
}
$jdkRoot = (Resolve-Path -LiteralPath $jdkRoot).Path.TrimEnd('\')
if ([string]::IsNullOrWhiteSpace($JdkHome)) {
    $candidates = @(Get-ChildItem -LiteralPath $jdkRoot -Directory -Force)
} else {
    # 允许显式固定 .jdk 内某一版本，便于针对旧版客户端回归。
    $explicit = (Resolve-Path -LiteralPath $JdkHome -ErrorAction Stop).Path.TrimEnd('\')
    if (-not $explicit.StartsWith($jdkRoot + '\', [StringComparison]::OrdinalIgnoreCase)) {
        throw "JdkHome must be inside the Lucanet .jdk directory: $jdkRoot"
    }
    $candidates = @(Get-Item -LiteralPath $explicit)
}

$eligible = foreach ($candidate in $candidates) {
    $executables = @{}
    $complete = $true
    foreach ($toolName in @('java', 'javac', 'jar')) {
        $toolPath = Join-Path $candidate.FullName "bin\$toolName.exe"
        if (-not (Test-Path -LiteralPath $toolPath -PathType Leaf)) { $complete = $false }
        $executables[$toolName] = $toolPath
    }
    if (-not $complete) { Write-Verbose "Skipping incomplete JDK: $($candidate.FullName)"; continue }

    # release 内的版本优先于目录名；不能把目录字符串按字典序排序。
    $release = @{}
    $releasePath = Join-Path $candidate.FullName 'release'
    if (Test-Path -LiteralPath $releasePath -PathType Leaf) {
        foreach ($line in Get-Content -LiteralPath $releasePath) {
            if ($line -match '^(?<key>[A-Z_]+)="(?<value>[^"]*)"$') {
                $release[$Matches['key']] = $Matches['value']
            }
        }
    }
    $versionText = $null
    $versionMatch = $null
    $versionInputs = @($release['JAVA_RUNTIME_VERSION'], $release['FULL_VERSION'], $release['SEMANTIC_VERSION'], $release['JAVA_VERSION'])
    $hasReleaseVersion = @($versionInputs | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }).Count -gt 0
    if (-not $hasReleaseVersion) { $versionInputs += ($candidate.Name -replace '^.*?JDK-', '') }
    foreach ($inputVersion in $versionInputs) {
        if ([string]::IsNullOrWhiteSpace($inputVersion)) { continue }
        $match = [regex]::Match($inputVersion, '^(?<v>\d+(?:\.\d+){0,3})(?:-(?<pre>ea|internal|beta|rc)(?:[.\d]*))?(?:\+(?<build>\d+))?(?:-(?:LTS|GA|[A-Za-z0-9_.]+))?$')
        if ($match.Success) { $versionText = $inputVersion; $versionMatch = $match; break }
    }
    if ($null -eq $versionMatch) { Write-Verbose "Skipping unrecognized JDK version: $($candidate.FullName)"; continue }
    $numbers = @($versionMatch.Groups['v'].Value.Split('.') | ForEach-Object { [int]$_ })
    if ($numbers[0] -lt $MinimumMajorVersion) { continue }
    while ($numbers.Count -lt 4) { $numbers += 0 }
    $numericVersion = [version]::new($numbers[0], $numbers[1], $numbers[2], $numbers[3])
    $buildNumber = if ($versionMatch.Groups['build'].Success) { [int]$versionMatch.Groups['build'].Value } else { 0 }
    [pscustomobject]@{
        Home = $candidate.FullName
        Version = $versionText
        NumericVersion = $numericVersion
        ReleaseRank = if ($versionMatch.Groups['pre'].Success) { 0 } else { 1 }
        BuildNumber = $buildNumber
        Java = $executables['java']
        Javac = $executables['javac']
        Jar = $executables['jar']
        LucanetHome = $installation
    }
}
$selected = $eligible | Sort-Object -Property @{Expression='NumericVersion';Descending=$true}, @{Expression='ReleaseRank';Descending=$true}, @{Expression='BuildNumber';Descending=$true}, @{Expression='Home';Descending=$false} | Select-Object -First 1
if ($null -eq $selected) {
    throw "No complete JDK $MinimumMajorVersion+ found in $jdkRoot (requires bin/java.exe, javac.exe and jar.exe). No environment/PATH fallback is used."
}
$selected
