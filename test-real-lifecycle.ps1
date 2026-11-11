[CmdletBinding()]
param(
    [string]$ResourceDirectory = 'C:\ProgramData\LucaNet\Youniverse\.resource',
    [string]$JdkHome = '',
    [string]$LibraryVersion = '17.15.3',
    [string]$BaseVersion = '11.4.0',
    [string]$JavaFxVersion = '21.0.4',
    [string]$LucanetHome = 'D:\Soft\LucaNet'
)
$ErrorActionPreference = 'Stop'
$jdk = & (Join-Path $PSScriptRoot 'resolve-lucanet-jdk.ps1') -LucanetHome $LucanetHome -JdkHome $JdkHome -MinimumMajorVersion 21
$javac = $jdk.Javac
$java = $jdk.Java
Write-Host "Lucanet JDK: $($jdk.Version) [$($jdk.Home)]"
$work = Join-Path $PSScriptRoot ('build\real-lifecycle-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $work -Force | Out-Null
$patterns = @(
    "ln.application-$LibraryVersion-*.jar",
    "ln.base_binding-$LibraryVersion-*.jar",
    "ln.base_lang-$LibraryVersion-*.jar",
    "ln.base_data-$LibraryVersion-*.jar",
    "ln.base_log-$LibraryVersion-*.jar",
    "ln.base_util-$LibraryVersion-*.jar",
    "ln.base_concurrency-$LibraryVersion-*.jar",
    "ln.deprecated_collection-$LibraryVersion-*.jar",
    "base-$BaseVersion-lnsuite-*.jar",
    "javafx-base-$JavaFxVersion-win.jar",
    "javafx-graphics-$JavaFxVersion-win.jar",
    "javafx-swing-$JavaFxVersion-win.jar"
)
$jars = foreach ($pattern in $patterns) {
    $matches = @(Get-ChildItem -LiteralPath $ResourceDirectory -Filter $pattern -File)
    if ($matches.Count -ne 1) { throw "Expected exactly one dependency matching $pattern, found $($matches.Count)." }
    $matches[0].FullName
}
# 较早组件版本的 base_data 还依赖独立的正则模块；新版本已移除此模块。
$regexJars = @(Get-ChildItem -LiteralPath $ResourceDirectory -Filter "ln.base_regex-$LibraryVersion-*.jar" -File)
if ($regexJars.Count -gt 1) { throw 'More than one matching ln.base_regex dependency.' }
if ($regexJars.Count -eq 1) { $jars += $regexJars[0].FullName }
$agent = Join-Path $PSScriptRoot 'dist\client-enhancement.jar'
if (-not (Test-Path -LiteralPath $agent)) { throw 'Run build.ps1 first.' }
$classpath = ($jars + @($agent, $work)) -join ';'
$sources = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src\integration\java') -Recurse -Filter '*.java' | ForEach-Object FullName)
& $javac --release 11 -encoding UTF-8 -cp $classpath -d $work @sources
if ($LASTEXITCODE -ne 0) { throw 'Real-library test compilation failed.' }
& $java '-Djava.awt.headless=true' "-javaagent:$agent" "-Dlucanet.enhancement.runtime=$work\runtime" "-Dlucanet.exit.logDir=$work\logs" "-Dlucanet.exit.archiveDir=$work\archive" "-Dfixture.tempDir=$work\temp" -cp $classpath cn.lucanet.enhancement.exit.RealLifecycleTest
if ($LASTEXITCODE -ne 0) { throw 'Real-library lifecycle test failed.' }
# 管理员使用真实 assembly_summa_admin 标记；此运行目录不带 Financial 入口替身。
$adminClasses = Join-Path $work 'administrator-probe'
$adminPackage = Join-Path $adminClasses 'cn\lucanet\enhancement\exit'
New-Item -ItemType Directory -Path $adminPackage -Force | Out-Null
Get-ChildItem -LiteralPath (Join-Path $work 'cn\lucanet\enhancement\exit') -Filter 'RealLifecycleTest*.class' | ForEach-Object {
    Copy-Item -LiteralPath $_.FullName -Destination $adminPackage
}
$adminAssemblies = @(Get-ChildItem -LiteralPath $ResourceDirectory -Filter 'assembly_summa_admin-*.jar' -File)
if($adminAssemblies.Count -eq 0){throw 'Administrator assembly missing for isolated lifecycle test.'}
foreach($assembly in $adminAssemblies){
    $adminClasspath = (@($jars) + @($agent, $adminClasses, $assembly.FullName)) -join ';'
    $scenario = Join-Path $work $assembly.BaseName
    & $java '-Djava.awt.headless=true' '-Dfixture.administrator=true' "-javaagent:$agent" "-Dlucanet.enhancement.runtime=$work\runtime" "-Dlucanet.exit.logDir=$work\logs" "-Dlucanet.exit.archiveDir=$scenario\archive" "-Dfixture.tempDir=$scenario\temp" -cp $adminClasspath cn.lucanet.enhancement.exit.RealLifecycleTest
    if($LASTEXITCODE -ne 0){throw 'Administrator real-library lifecycle test failed.'}
}
Write-Host "Real-library test passed. Evidence: $work"
