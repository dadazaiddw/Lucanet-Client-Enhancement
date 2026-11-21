[CmdletBinding()]
param(
    [string]$LucanetHome='D:\Soft\LucaNet',
    [string]$Resources='C:\ProgramData\LucaNet\Youniverse\.resource',
    [string]$JdkHome=''
)
$ErrorActionPreference='Stop'
$project=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..\..')).Path
$jdk=& (Join-Path $project 'resolve-lucanet-jdk.ps1') -LucanetHome $LucanetHome -JdkHome $JdkHome
$work=Join-Path $project ('build\audit-reproduction-'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $work -Force|Out-Null
$agent=Join-Path $project 'dist\audit-client-enhancement.jar'
& (Join-Path $project 'build.ps1') -LucanetHome $LucanetHome -JdkHome $jdk.Home -JarName 'audit-client-enhancement.jar'
& $jdk.Javac --release 11 -encoding UTF-8 -classpath $agent -d $work (Join-Path $PSScriptRoot 'AuditGenerationProbe.java')
if($LASTEXITCODE -ne 0){throw 'Audit probe compilation failed'}
& $jdk.Java '-Djava.awt.headless=true' -cp "$work;$agent" cn.lucanet.enhancement.exit.AuditGenerationProbe (Join-Path $work 'generation') 2>&1 |
    Tee-Object -FilePath (Join-Path $work 'generation.txt')
if($LASTEXITCODE -ne 0){throw 'Audit generation probe failed to execute'}

# Reuse the existing two-release classpath selection without editing the production test script.
# The model probe uses in-memory fixture scripts, no business login and no visible windows.
$runner=[IO.File]::ReadAllText((Join-Path $project 'test-editor-real.ps1'))
$assignment='$auditProjectRoot = ' + "'" + $project.Replace("'","''") + "'"
$runner=$runner.Replace('$PSScriptRoot','$auditProjectRoot')
$runner=$runner.Replace('$ErrorActionPreference=''Stop''',('$ErrorActionPreference=''Stop'''+[Environment]::NewLine+$assignment))
$runner=$runner.Replace('[ValidateSet(''JavaEditorRealProbe'',''CustomJavaRealProbe'',''CustomJavaWindowProbe'',''NativeCompilerIsolationProbe'')]','')
$runner=$runner.Replace('(Join-Path $auditProjectRoot (''tools\''+$Probe+''.java''))',
    '(Join-Path $auditProjectRoot ''docs\evidence\audit-2026-11-21\AuditNativeProjectionProbe.java'') (Join-Path $auditProjectRoot ''tools\CustomJavaRealProbe.java'')')
$runner=$runner.Replace('-Djava.awt.headless=false','-Djava.awt.headless=true').Replace('dist\client-enhancement.jar','dist\audit-client-enhancement.jar')
$runnerPath=Join-Path $work 'native-projection.ps1'
[IO.File]::WriteAllText($runnerPath,$runner,[Text.UTF8Encoding]::new($false))
& $runnerPath -LucanetHome $LucanetHome -Resources $Resources -JdkHome $jdk.Home -Probe AuditNativeProjectionProbe -JarName 'audit-client-enhancement.jar' -Headless 2>&1 |
    Tee-Object -FilePath (Join-Path $work 'native-projection.txt')
if($LASTEXITCODE -ne 0){throw 'Audit native probe failed to execute'}
Write-Host "Audit reproduction evidence: $work"
Write-Host 'This probe reports before/after behavior; successful execution does not mean the audited defects are fixed.'
