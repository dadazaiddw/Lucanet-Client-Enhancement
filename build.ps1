[CmdletBinding()]
param(
    [string]$JdkHome = '',
    [string]$JarName = 'client-enhancement.jar',
    [string]$LucanetHome = 'D:\Soft\LucaNet'
)
$ErrorActionPreference = 'Stop'
if ($JarName -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]*\.jar$') { throw 'JarName must be a simple .jar file name, without directories.' }
$projectRoot = $PSScriptRoot
$jdk = & (Join-Path $PSScriptRoot 'resolve-lucanet-jdk.ps1') -LucanetHome $LucanetHome -JdkHome $JdkHome
$javac = $jdk.Javac
Write-Host "Lucanet JDK: $($jdk.Version) [$($jdk.Home)]"
$classes = Join-Path $projectRoot ('build\classes-' + [Guid]::NewGuid().ToString('N'))
$dist = Join-Path $projectRoot 'dist'
New-Item -ItemType Directory -Path $classes, $dist -Force | Out-Null
try {
$sources = @(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'src\main\java') -Recurse -Filter '*.java' | ForEach-Object FullName)
$asm = Join-Path $LucanetHome '.resource\asm-9.7.jar'
$asmCommons = Join-Path $LucanetHome '.resource\asm-commons-9.7.jar'
foreach ($dependency in @($asm,$asmCommons)) {
    if (-not (Test-Path -LiteralPath $dependency)) { throw "Build dependency missing: $dependency" }
}
& $javac --release 11 -encoding UTF-8 -classpath $asm -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw 'Java compilation failed.' }
$outputJar = Join-Path $dist $JarName
$assembler = Join-Path $classes 'assembler'
New-Item -ItemType Directory -Path $assembler -Force | Out-Null
& $javac --release 11 -encoding UTF-8 -classpath "$asm;$asmCommons" -d $assembler (Join-Path $projectRoot 'tools\AssembleAgent.java')
if ($LASTEXITCODE -ne 0) { throw 'Agent assembler compilation failed.' }
$version = ([xml](Get-Content -LiteralPath (Join-Path $projectRoot 'pom.xml') -Raw)).project.version
& $jdk.Java -cp "$assembler;$asm;$asmCommons" AssembleAgent $classes $asm $outputJar (Join-Path $projectRoot 'licenses\ASM-LICENSE.txt') $version
if ($LASTEXITCODE -ne 0) { throw 'JAR packaging failed.' }
Write-Host "Built: $outputJar"
& (Join-Path $PSScriptRoot 'build-gui.ps1')
} finally {
    # 编译中间文件只用于本次构建，结束后移除，避免长期保留旧版本 class 和副本。
    $buildRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot 'build')).TrimEnd('\')
    $cleanup = [IO.Path]::GetFullPath($classes)
    if (-not $cleanup.StartsWith($buildRoot + '\', [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Build cleanup target is outside the project build directory.'
    }
    if (Test-Path -LiteralPath $cleanup) {
        $links = @((Get-Item -LiteralPath $cleanup -Force) | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint })
        $links += @(Get-ChildItem -LiteralPath $cleanup -Recurse -Force -Attributes ReparsePoint)
        if ($links.Count -gt 0) { throw 'Build cleanup refused: intermediate directory contains a link.' }
        Remove-Item -LiteralPath $cleanup -Recurse -Force
    }
}
