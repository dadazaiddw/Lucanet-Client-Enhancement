[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
$fake=Join-Path $PSScriptRoot ('build\install-test-'+[Guid]::NewGuid().ToString('N'))
$release=Join-Path $fake '.2604+15'
New-Item -ItemType Directory -Path $release -Force | Out-Null
[IO.File]::WriteAllBytes((Join-Path $fake 'LucaNetSoftwareManager.exe'),[byte[]]@())
$utf8=[Text.UTF8Encoding]::new($false)
$original=@{}
$ownAgentLine='jdkParameter=-javaagent:'+(Join-Path $fake '.client-enhancement\client-enhancement.jar')
foreach($entry in @(@('LucaNetSoftwareManager.ln10','ui'),@('LucaNetKickStart.ln10','kickstart'))){
    $path=Join-Path $release $entry[0]
    $text="version=2`njdkParameter=-Xmx256m`njdkParameter=-javaagent:other-agent.jar`n$ownAgentLine.other`n$ownAgentLine=separate-options`nmainClass=com/lucanet/lnswmgr_$($entry[1])/Main`n`n"
    [IO.File]::WriteAllText($path,$text,$utf8)
    $original[$path]=$text
}
$installer=Join-Path $PSScriptRoot 'install.ps1'
$beforeCheck=@(& $installer -LucanetHome $fake -Check)
if(@($beforeCheck | Where-Object Enabled).Count -ne 0){throw 'Similar prefixes were misreported as enabled.'}
& $installer -LucanetHome $fake
& $installer -LucanetHome $fake
foreach($path in $original.Keys){
    $text=[IO.File]::ReadAllText($path)
    if(@($text.Split("`n") | Where-Object {$_ -ceq $ownAgentLine}).Count -ne 1){throw 'Repeated installation duplicated agent option.'}
    if(-not $text.Contains('jdkParameter=-javaagent:other-agent.jar')){throw 'Other agent was lost.'}
    if(-not $text.Contains($ownAgentLine+'.other') -or -not $text.Contains($ownAgentLine+'=separate-options')){throw 'Similar agent paths/options were altered.'}
}
& $installer -LucanetHome $fake -Uninstall
foreach($path in $original.Keys){if([IO.File]::ReadAllText($path) -cne $original[$path]){throw 'Uninstall did not restore original configuration bytes.'}}
& $installer -LucanetHome $fake -Uninstall
$backupCount=@(Get-ChildItem -LiteralPath (Join-Path $fake '.client-enhancement\backups') -Directory).Count
if($backupCount -ne 4){throw 'Missing backup history.'}
# 卸载后重新安装：配置启用、JAR 正确，再卸载恢复原文。
& $installer -LucanetHome $fake
foreach($path in $original.Keys){if(@([IO.File]::ReadAllLines($path)|Where-Object {$_ -ceq $ownAgentLine}).Count -ne 1){throw 'Reinstall after uninstall did not enable config.'}}
& $installer -LucanetHome $fake -Uninstall
foreach($path in $original.Keys){if([IO.File]::ReadAllText($path) -cne $original[$path]){throw 'Reinstall/uninstall cycle changed original config.'}}
# 出现不支持的配置时，必须在修改其他文件前停止。
$bad=Join-Path $release 'LucaNetKickStart.ln10'
[IO.File]::WriteAllText($bad,'unsupported',$utf8)
$rejected=$false
try{& $installer -LucanetHome $fake}catch{$rejected=$_.Exception.Message -like '*Unsupported native configuration*'}
if(-not $rejected){throw 'Unknown native format was accepted.'}
$manager=Join-Path $release 'LucaNetSoftwareManager.ln10'
if([IO.File]::ReadAllText($manager) -cne $original[$manager]){throw 'Preflight failure partially modified config.'}
Write-Host 'PASS: installation, idempotent reinstall, uninstall, backups, unrelated parameters and unsupported-format preflight'
