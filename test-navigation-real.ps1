[CmdletBinding()]
param([string]$LucanetHome='D:\Soft\LucaNet',[string]$Resources='C:\ProgramData\LucaNet\Youniverse\.resource',[string]$JdkHome='')
$ErrorActionPreference='Stop'
$jdk=& (Join-Path $PSScriptRoot 'resolve-lucanet-jdk.ps1') -LucanetHome $LucanetHome -JdkHome $JdkHome
$work=Join-Path $PSScriptRoot ('build\navigation-real-'+[Guid]::NewGuid().ToString('N'))
$fixtures=Join-Path $work 'fixtures'
New-Item -ItemType Directory -Path $fixtures -Force | Out-Null
$sources=@('com/lucanet/lisa/ui/UIContext.java','com/lucanet/lisa/ui/UIController.java','com/lucanet/lisa/applicationframe/UIContainer.java','com/lucanet/lisa/useraction/AbstractUserAction.java') | ForEach-Object {Join-Path $PSScriptRoot ('src/test/java/'+$_)}
& $jdk.Javac --release 11 -encoding UTF-8 -d $fixtures @sources
if($LASTEXITCODE -ne 0){throw 'Fixture compile failed'}
$asm=Join-Path $LucanetHome '.resource\asm-9.7.jar'
& $jdk.Javac --release 11 -encoding UTF-8 -cp $asm -d $work (Join-Path $PSScriptRoot 'tools\RealNavigationTest.java') (Join-Path $PSScriptRoot 'tools\RealReflectionGuard.java') (Join-Path $PSScriptRoot 'tools\NavigationContractTest.java')
if($LASTEXITCODE -ne 0){throw 'Probe compile failed'}
foreach($set in @(@('2311.0.81+6','3.15.1','2311.0.81+6'),@('2505.3.4+3','6.8.0','2505.11.4+11'))){
    if([int]($jdk.Version.Split('.')[0]) -lt 21 -and $set[0] -like '2505*'){continue}
    $assembly=(Get-ChildItem -LiteralPath $Resources -Filter ('assembly_summa_client-'+$set[0]+'-*.jar')).FullName
    $uiPattern=if($set[0] -like '2311*'){'_vm-'+$set[0]+'-*.jar'}else{'lisa-'+$set[1]+'-*.jar'}
    $lisa=(Get-ChildItem -LiteralPath $Resources -Filter $uiPattern).FullName
    $sarah=(Get-ChildItem -LiteralPath $Resources -Filter ('sarah_client-'+$set[2]+'-*.jar')).FullName
    & $jdk.Java '-Djava.awt.headless=true' "-javaagent:$PSScriptRoot\dist\client-enhancement.jar" "-Dlucanet.enhancement.runtime=$work\runtime" "-Dlucanet.enhancement.features=$work\features.properties" "-Dlucanet.exit.logDir=$work\logs" -cp $work RealNavigationTest $assembly $fixtures $lisa $sarah $Resources
    if($LASTEXITCODE -ne 0){throw 'Real navigation transformation failed'}
    & $jdk.Java -cp "$work;$asm" NavigationContractTest $lisa $sarah
    if($LASTEXITCODE -ne 0){throw 'Real navigation contract failed'}
}
Write-Host "Navigation evidence: $work"
