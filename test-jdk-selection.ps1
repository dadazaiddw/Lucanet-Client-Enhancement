[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$resolver = Join-Path $PSScriptRoot 'resolve-lucanet-jdk.ps1'
$work = Join-Path $PSScriptRoot ('build\jdk-selection-' + [Guid]::NewGuid().ToString('N'))
$fakeInstallation = Join-Path $work 'Software Manager'
New-Item -ItemType Directory -Path (Join-Path $fakeInstallation '.jdk') -Force | Out-Null

function New-TestJdk([string]$Name, [string]$Version, [switch]$Incomplete) {
    $directory = Join-Path (Join-Path $fakeInstallation '.jdk') $Name
    New-Item -ItemType Directory -Path (Join-Path $directory 'bin') -Force | Out-Null
    # 只验证发现/排序，不运行占位 exe；实际执行由 test.ps1 用真实安装 JDK 验证。
    foreach ($toolName in @('java', 'javac', 'jar')) {
        if ($Incomplete -and $toolName -eq 'javac') { continue }
        [IO.File]::WriteAllBytes((Join-Path $directory "bin\$toolName.exe"), [byte[]]@())
    }
    [IO.File]::WriteAllText((Join-Path $directory 'release'), 'JAVA_RUNTIME_VERSION="' + $Version + '"')
    return $directory
}
function Assert-Equal($Actual, $Expected, [string]$Description) {
    if ($Actual -ne $Expected) { throw "JDK selection test failed: $Description; actual=$Actual, expected=$Expected" }
}
$v17 = New-TestJdk 'LucaNetJDK-17.0.4+8' '17.0.4+8'
$v214 = New-TestJdk 'LucaNetJDK-21.0.4+7' '21.0.4+7'
$v2110 = New-TestJdk 'LucaNetJDK-21.0.10+7' '21.0.10+7'
$v2111 = New-TestJdk 'LucaNetJDK-21.0.11+10' '21.0.11+10-LTS'
Assert-Equal (& $resolver -LucanetHome $fakeInstallation).Home $v2111 'numeric version, not lexical order'
$build11 = New-TestJdk 'same-version-new-build' '21.0.11+11-LTS'
Assert-Equal (& $resolver -LucanetHome $fakeInstallation).Home $build11 'same-version build number'
$ea = New-TestJdk 'preview' '21.0.11-ea+99'
Assert-Equal (& $resolver -LucanetHome $fakeInstallation).Home $build11 'stable release before equal-version preview'
$misleading = New-TestJdk 'LucaNetJDK-99.0.0+1' '17.0.1+1'
$incomplete = New-TestJdk 'incomplete' '25.0.1+8' -Incomplete
Assert-Equal (& $resolver -LucanetHome $fakeInstallation).Home $build11 'release metadata and complete toolset'
$v25 = New-TestJdk 'LucaNetJDK-25.0.1+8' '25.0.1+8'
Assert-Equal (& $resolver -LucanetHome $fakeInstallation).Home $v25 'highest major version'
Assert-Equal (& $resolver -LucanetHome $fakeInstallation -JdkHome $v17).Home $v17 'explicit version inside installation'

$savedJavaHome = $env:JAVA_HOME
$savedPath = $env:PATH
try {
    $env:JAVA_HOME = Join-Path $work 'unrelated-env-jdk'
    $env:PATH = ''
    Assert-Equal (& $resolver -LucanetHome $fakeInstallation).Home $v25 'ignore JAVA_HOME and PATH'
    $emptyInstall = Join-Path $work 'empty-install'
    New-Item -ItemType Directory -Path (Join-Path $emptyInstall '.jdk') -Force | Out-Null
    $rejected = $false
    try { & $resolver -LucanetHome $emptyInstall | Out-Null } catch { $rejected = $_.Exception.Message -like '*No complete JDK*' }
    Assert-Equal $rejected $true 'no fallback when local .jdk is empty'
} finally {
    $env:JAVA_HOME = $savedJavaHome
    $env:PATH = $savedPath
}
$rejected = $false
try { & $resolver -LucanetHome $fakeInstallation -JdkHome $work | Out-Null } catch { $rejected = $_.Exception.Message -like '*must be inside*' }
Assert-Equal $rejected $true 'explicit version cannot leave Lucanet .jdk'
Write-Host 'PASS: numeric JDK versions, build versions, release metadata, complete tools, highest major, explicit pin, no environment fallback'
