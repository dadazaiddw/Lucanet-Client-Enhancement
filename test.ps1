[CmdletBinding()]
param(
    [string]$JdkHome = '',
    [string]$JarName = 'client-enhancement.jar',
    [string]$LucanetHome = 'D:\Soft\LucaNet'
)
$ErrorActionPreference = 'Stop'
$jdk = & (Join-Path $PSScriptRoot 'resolve-lucanet-jdk.ps1') -LucanetHome $LucanetHome -JdkHome $JdkHome
$javac = $jdk.Javac
$java = $jdk.Java
& (Join-Path $PSScriptRoot 'test-jdk-selection.ps1')
& (Join-Path $PSScriptRoot 'build.ps1') -LucanetHome $LucanetHome -JdkHome $jdk.Home -JarName $JarName
$testRoot = Join-Path $PSScriptRoot ('build\tests-' + [Guid]::NewGuid().ToString('N'))
$classes = Join-Path $testRoot 'classes'
$logs = Join-Path $testRoot 'logs'
$temp = Join-Path $testRoot 'client temp'
New-Item -ItemType Directory -Path $classes, $logs, $temp -Force | Out-Null
$agent = Join-Path (Join-Path $PSScriptRoot 'dist') $JarName
$sources = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src\test\java') -Recurse -Filter '*.java' | ForEach-Object FullName)
& $javac --release 11 -encoding UTF-8 -classpath $agent -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed.' }
$archive = Join-Path $testRoot 'archive'
& $java '-Djava.awt.headless=true' "-Dlucanet.exit.logDir=$logs" -cp "$classes;$agent" cn.lucanet.enhancement.exit.TempFileArchiverTest (Join-Path $testRoot 'archive scenarios')
if ($LASTEXITCODE -ne 0) { throw 'Archive behavior tests failed.' }
# 使用 Windows 文件独占句柄，验证一个文件被占用不会阻断其他文件的归档。
$lockedRoot = Join-Path $testRoot 'locked files'
New-Item -ItemType Directory -Path $lockedRoot -Force | Out-Null
$lockedFile = Join-Path $lockedRoot 'busy.bin'
[IO.File]::WriteAllText($lockedFile, 'keep locked content')
$lockedHandle = [IO.File]::Open($lockedFile, [IO.FileMode]::Open, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
try {
    & $java "-Dlucanet.exit.logDir=$logs" -cp "$classes;$agent" cn.lucanet.enhancement.exit.LockedFileArchiveTest $lockedRoot (Join-Path $testRoot 'locked archive')
    if ($LASTEXITCODE -ne 0) { throw 'Locked file behavior test failed.' }
} finally {
    $lockedHandle.Dispose()
}
if ([IO.File]::ReadAllText($lockedFile) -ne 'keep locked content') { throw 'Locked source content changed.' }
& $java '-Djava.awt.headless=true' "-javaagent:$agent" "-Dlucanet.enhancement.runtime=$testRoot\runtime" "-Dlucanet.exit.logDir=$logs" "-Dlucanet.exit.archiveDir=$archive" "-Dfixture.tempDir=$temp" -cp "$classes;$agent" com.lucanet.lnswmgr_ui.NativeLaunchTest
if ($LASTEXITCODE -ne 0) { throw 'Native startup propagation / built-in exit handling test failed.' }
& $java '-Djava.awt.headless=true' "-javaagent:$agent" "-Dlucanet.enhancement.runtime=$testRoot\runtime" "-Dlucanet.exit.logDir=$logs" -cp "$classes;$agent" cn.lucanet.enhancement.exit.DialogIsolationTest
if ($LASTEXITCODE -ne 0) { throw 'Session-state independence / non-client isolation test failed.' }
Write-Host "All tests passed. Evidence: $testRoot"
& $java '-Djava.awt.headless=true' "-javaagent:$agent" "-Dlucanet.enhancement.runtime=$testRoot\runtime" "-Dlucanet.enhancement.features=$testRoot\features.properties" "-Dlucanet.exit.logDir=$logs" -cp "$classes;$agent" cn.lucanet.enhancement.exit.NavigationTest
if ($LASTEXITCODE -ne 0) { throw 'Live navigation feature tests failed.' }
& $java '-Djava.awt.headless=true' "-javaagent:$agent" "-Dlucanet.enhancement.runtime=$testRoot\runtime" "-Dlucanet.enhancement.features=$testRoot\script-features.properties" "-Dlucanet.exit.logDir=$logs" -cp "$classes;$agent" cn.lucanet.enhancement.exit.ScriptControlsTest
if ($LASTEXITCODE -ne 0) { throw 'Live script controls tests failed.' }
& $java -cp "$classes;$agent" cn.lucanet.enhancement.exit.HotUpdateTest (Join-Path $testRoot 'hot update 中文') $agent
if ($LASTEXITCODE -ne 0) { throw 'Hot update transaction tests failed.' }
