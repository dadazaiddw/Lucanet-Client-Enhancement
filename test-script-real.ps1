[CmdletBinding()]
param([string]$LucanetHome='D:\Soft\LucaNet',[string]$Resources='C:\ProgramData\LucaNet\Youniverse\.resource',[string]$JdkHome='')
$ErrorActionPreference='Stop'
$jdk=& (Join-Path $PSScriptRoot 'resolve-lucanet-jdk.ps1') -LucanetHome $LucanetHome -JdkHome $JdkHome
$work=Join-Path $PSScriptRoot ('build\script-real-'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $work -Force|Out-Null
$asm=Join-Path $LucanetHome '.resource\asm-9.7.jar'
& $jdk.Javac --release 11 -encoding UTF-8 -cp $asm -d $work (Join-Path $PSScriptRoot 'tools\ScriptPanelContractProbe.java')
if($LASTEXITCODE -ne 0){throw 'Real script panel probe compilation failed'}
$manifest=Join-Path $work 'probe.mf'
[IO.File]::WriteAllText($manifest,"Manifest-Version: 1.0`nPremain-Class: ScriptPanelContractProbe`nCan-Retransform-Classes: true`n",[Text.UTF8Encoding]::new($false))
$probe=Join-Path $work 'probe.jar'
& $jdk.Jar cfm $probe $manifest -C $work ScriptPanelContractProbe.class -C $work 'ScriptPanelContractProbe$1.class'
if($LASTEXITCODE -ne 0){throw 'Probe agent packaging failed'}
foreach($suite in @(@('2311.0.81+6','_vm-2311.0.81+6-*.jar'),@('2505.3.4+3','lisa-6.8.0-*.jar'))){
    if([int]($jdk.Version.Split('.')[0]) -lt 21 -and $suite[0] -like '2505*'){continue}
    $ui=(Get-ChildItem -LiteralPath $Resources -Filter $suite[1] -File).FullName
    $summa=(Get-ChildItem -LiteralPath $Resources -Filter ('summa_client-'+$suite[0]+'-*.jar') -File).FullName
    $classpath=(@($work,$asm,$ui,$summa,(Join-Path $Resources '*'))) -join ';'
    & $jdk.Java '-Djava.awt.headless=true' "-javaagent:$PSScriptRoot\dist\client-enhancement.jar" "-javaagent:$probe" "-Dlucanet.enhancement.runtime=$work\runtime" "-Dlucanet.exit.logDir=$work\logs" -cp $classpath ScriptPanelContractProbe $summa (Join-Path $work ('panel-'+$suite[0]+'.class'))
    if($LASTEXITCODE -ne 0){throw 'Real script execution panel hook/verification failed'}
}
Write-Host "Script panel evidence: $work"
