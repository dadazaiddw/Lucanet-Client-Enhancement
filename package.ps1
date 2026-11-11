[CmdletBinding()]
param([string]$JarName = 'client-enhancement.jar')
$ErrorActionPreference = 'Stop'
if ($JarName -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]*\.jar$') { throw 'JarName must be a simple .jar file name, without directories.' }
$agent = Join-Path (Join-Path $PSScriptRoot 'dist') $JarName
if (-not (Test-Path -LiteralPath $agent -PathType Leaf)) { throw 'Run test.ps1 before packaging.' }
# 客户交付包只包含 GUI 的运行依赖。维护源码、文档、测试、命令行快捷入口均留在仓库。
# 此清单同时对应 GUI 更新器 VerifyAndExtract 检查的完整运行包结构。
$files = @(
    'enhancement.exe',
    'install.ps1',
    'resolve-lucanet-jdk.ps1',
    'tools/gui-core.ps1',
    'tools/gui-service.ps1'
)
$archive = Join-Path $PSScriptRoot 'dist\lucanet-client-enhancement.zip'
# 按交付白名单直接写 ZIP，不再生成一整份 release-* 源码/JAR 副本。
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$entries = @()
foreach ($file in $files) {
    $path = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot $file))
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Missing delivery file: $file" }
    $entries += [pscustomobject]@{Path=$path;Name=$file}
}
$entries += [pscustomobject]@{Path=$agent;Name='dist/client-enhancement.jar'}
$root = [IO.Path]::GetFullPath($PSScriptRoot).TrimEnd('\')
foreach ($entry in $entries) {
    $cursor = [IO.Path]::GetFullPath($entry.Path)
    if (-not $cursor.StartsWith($root + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Delivery source escaped project.' }
    while ($cursor -ine $root) {
        if ((Get-Item -LiteralPath $cursor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw "Linked delivery source is not supported: $cursor" }
        $cursor = Split-Path -Parent $cursor
    }
}
# 独立许可证文件不重复打包；交付 JAR 必须继续内嵌所使用的 ASM 许可证。
$agentArchive = [IO.Compression.ZipFile]::OpenRead($agent)
try {
    $licenseEntry = $agentArchive.GetEntry('META-INF/ASM-LICENSE.txt')
    if ($null -eq $licenseEntry -or $licenseEntry.Length -eq 0) { throw 'Agent JAR must include META-INF/ASM-LICENSE.txt.' }
} finally { $agentArchive.Dispose() }
$temporary = $archive + '.' + [Guid]::NewGuid().ToString('N') + '.tmp'
try {
    $zip = [IO.Compression.ZipFile]::Open($temporary, [IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($entry in $entries) {
            [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $entry.Path, $entry.Name, [IO.Compression.CompressionLevel]::Optimal) | Out-Null
        }
    } finally { $zip.Dispose() }
    Move-Item -LiteralPath $temporary -Destination $archive -Force
} finally {
    if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Force }
}
# 版本文件与通用下载文件始终指向同一构建，避免重复使用旧的同版本发布包。
$projectVersion = ([xml](Get-Content -LiteralPath (Join-Path $PSScriptRoot 'pom.xml') -Raw)).project.version
if ($projectVersion -notmatch '^\d+\.\d+\.\d+(?:\.\d+)?$') { throw 'Unsupported release version.' }
$versionedArchive = Join-Path $PSScriptRoot ('dist\lucanet-client-enhancement-v' + $projectVersion + '.zip')
Copy-Item -LiteralPath $archive -Destination $versionedArchive -Force
# 使用 .NET 生成校验值，兼容 Windows PowerShell 5.1 的模块加载环境。
$checksumLines = foreach ($file in @($agent, (Join-Path $PSScriptRoot 'enhancement.exe'), $archive, $versionedArchive)) {
    $stream = [IO.File]::OpenRead($file)
    $sha256 = [Security.Cryptography.SHA256]::Create()
    try {
        $relative = if ($file -eq (Join-Path $PSScriptRoot 'enhancement.exe')) { '../enhancement.exe' } else { [IO.Path]::GetFileName($file) }
        [BitConverter]::ToString($sha256.ComputeHash($stream)).Replace('-', '') + '  ' + $relative
    } finally {
        $sha256.Dispose()
        $stream.Dispose()
    }
}
$checksumLines | Set-Content -LiteralPath (Join-Path $PSScriptRoot 'dist\SHA256SUMS.txt') -Encoding ascii
Write-Host "Packaged: $archive"
Write-Host 'Runtime package: 6 files. Extract all files and open enhancement.exe.'
