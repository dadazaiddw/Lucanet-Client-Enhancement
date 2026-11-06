[CmdletBinding()]
param([string]$JarName = 'client-enhancement.jar')
$ErrorActionPreference = 'Stop'
if ($JarName -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]*\.jar$') { throw 'JarName must be a simple .jar file name, without directories.' }
$agent = Join-Path (Join-Path $PSScriptRoot 'dist') $JarName
if (-not (Test-Path -LiteralPath $agent)) { throw 'Run test.ps1 before packaging.' }
$files = @(
    'README.md', 'pom.xml', 'enhancement.exe', 'build-gui.ps1',
    'build.ps1', 'build.cmd',
    'test.ps1', 'test.cmd',
    'test-real-lifecycle.ps1', 'test-real-lifecycle.cmd',
    'test-native-integration.ps1', 'test-native-integration.cmd', 'test-install.ps1', 'test-gui.ps1', 'test-gui-window.ps1',
    'test-jdk-selection.ps1', 'resolve-lucanet-jdk.ps1',
    'maven.ps1', 'maven.cmd',
    'package.ps1', 'package.cmd',
    'install.ps1', 'install.cmd', 'uninstall.cmd', 'check.cmd'
)
$archive = Join-Path $PSScriptRoot 'dist\lucanet-client-enhancement.zip'
# 按交付白名单直接写 ZIP，不再生成一整份 release-* 源码/JAR 副本。
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$entries = @()
foreach ($file in $files) {
    $path = Join-Path $PSScriptRoot $file
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Missing delivery file: $file" }
    $entries += [pscustomobject]@{Path=$path;Name=$file}
}
foreach ($folder in @('src','docs','tools','licenses','gui')) {
    $directory = Join-Path $PSScriptRoot $folder
    if ((Get-Item -LiteralPath $directory -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Linked source directory is not supported.' }
    if (@(Get-ChildItem -LiteralPath $directory -Recurse -Force -Attributes ReparsePoint).Count -gt 0) { throw 'Source tree contains a link.' }
    foreach ($file in Get-ChildItem -LiteralPath $directory -Recurse -Force -File) {
        $entries += [pscustomobject]@{Path=$file.FullName;Name=$file.FullName.Substring($PSScriptRoot.TrimEnd('\').Length+1).Replace('\','/')}
    }
}
$entries += [pscustomobject]@{Path=$agent;Name='dist/client-enhancement.jar'}
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
