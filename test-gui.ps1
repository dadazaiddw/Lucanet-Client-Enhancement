[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'tools\gui-core.ps1')
$work=Join-Path $PSScriptRoot ('build\gui-tests-'+[Guid]::NewGuid().ToString('N'))
$fake=Join-Path $work 'Lucanet 安装 测试'
$release=Join-Path $fake '.2604+15'
New-Item -ItemType Directory -Path $release -Force | Out-Null
$compiler=Join-Path $env:SystemRoot 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
$testSource=Join-Path $work 'process.cs'
[IO.File]::WriteAllText($testSource,'class Fixture { static void Main() { System.Threading.Thread.Sleep(60000); } }')
$fakeExe=Join-Path $fake 'LucaNetSoftwareManager.exe'
& $compiler /nologo /target:winexe "/out:$fakeExe" $testSource
if($LASTEXITCODE -ne 0){throw 'Process fixture compilation failed.'}
foreach($pair in @(@('LucaNetSoftwareManager.ln10','ui'),@('LucaNetKickStart.ln10','kickstart'))){
    [IO.File]::WriteAllText((Join-Path $release $pair[0]),"version=2`nmainClass=com/lucanet/lnswmgr_$($pair[1])/Main`n",[Text.UTF8Encoding]::new($false))
}
# 使用真实 Lucanet JDK 的只读路径检测；假安装 JDK 用空工具文件验证选择器，不执行它们。
$fakeJdk=Join-Path $fake '.jdk\LucaNetJDK-21.0.11+10'
New-Item -ItemType Directory -Path (Join-Path $fakeJdk 'bin') -Force | Out-Null
foreach($name in @('java','javac','jar')){[IO.File]::WriteAllBytes((Join-Path $fakeJdk ('bin\'+$name+'.exe')),[byte[]]@())}
[IO.File]::WriteAllText((Join-Path $fakeJdk 'release'),'JAVA_RUNTIME_VERSION="21.0.11+10-LTS"')
function Assert-Gui($condition,[string]$description){if(-not $condition){throw "GUI test failed: $description"}}
$service=Join-Path $PSScriptRoot 'tools\gui-service.ps1'
$ps=Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
function Call-Service([string]$action){
    $json=& $ps -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $service -Action $action -LucanetHome $fake
    return (($json -join "`n")|ConvertFrom-Json)
}
Assert-Gui ((Resolve-SoftwareManagerRoot $fakeExe) -eq $fake) 'manual EXE path resolves to installation'
Assert-Gui (@(Find-SoftwareManagerInstallations -AdditionalCandidates @($fake,$fakeExe)|Where-Object Root -eq $fake).Count -eq 1) 'discovery de-duplicates installation'
$status=Get-ClientEnhancementStatus $fake -LogDirectory (Join-Path $work 'logs')
Assert-Gui ($status.State -eq 'not-installed' -and $status.JdkVersion -eq '21.0.11+10-LTS' -and $status.CanModify) 'not installed / selected Lucanet JDK'
$result=Call-Service 'install';Assert-Gui $result.Ok 'GUI install service'
$status=Get-ClientEnhancementStatus $fake -LogDirectory (Join-Path $work 'logs')
Assert-Gui ($status.State -eq 'installed' -and $status.Current) 'complete current installation'
$logs=Join-Path $work 'logs';New-Item -ItemType Directory -Path $logs -Force|Out-Null
$agent=Join-Path $fake '.client-enhancement\client-enhancement.jar'
$process=Start-Process -FilePath $fakeExe -WindowStyle Hidden -PassThru
try{
    $status=Get-ClientEnhancementStatus $fake -LogDirectory (Join-Path $work 'logs')
    Assert-Gui ($status.ManagerRunning -and -not $status.CanModify) 'Manager running state blocks modifications'
    $result=Call-Service 'uninstall';Assert-Gui (-not $result.Ok -and $result.Message -match '正在运行') 'GUI refuses uninstall while running'
    $liveLog=Join-Path $logs ('client-exit-'+$process.Id+'.log')
    $stamp=[DateTime]::UtcNow.ToString('o')
    [IO.File]::WriteAllText($liveLog,"$stamp [INFO] [pid=$($process.Id)] AGENT_ACTIVE: jar=$agent`n$stamp [INFO] [pid=$($process.Id)] TRANSFORMED: ln/application/Application`n")
    $status=Get-ClientEnhancementStatus $fake -LogDirectory $logs
    Assert-Gui ($status.Loaded.Count -eq 1 -and $status.Loaded[0].ExitHook) 'current process and exact Agent path match'
    [IO.File]::WriteAllText($liveLog,"$stamp [INFO] [pid=$($process.Id)] AGENT_ACTIVE: jar=$agent.other`n")
    $status=Get-ClientEnhancementStatus $fake -LogDirectory $logs
    Assert-Gui ($status.Loaded.Count -eq 0) 'same-prefix Agent from another path is not accepted'
    [IO.File]::WriteAllText($liveLog,"2020-01-01T00:00:00Z [INFO] [pid=$($process.Id)] AGENT_ACTIVE: jar=$agent`n")
    $status=Get-ClientEnhancementStatus $fake -LogDirectory $logs
    Assert-Gui ($status.Loaded.Count -eq 0) 'old log with reused PID is not current evidence'
}finally{
    # 仅结束本测试刚创建、无数据/无界面的假进程。
    if(-not $process.HasExited){$process.Kill();$process.WaitForExit()}
    $process.Dispose()
}
$result=Call-Service 'uninstall';Assert-Gui $result.Ok 'GUI uninstall service'
$result=Call-Service 'install';Assert-Gui $result.Ok 'GUI reinstall after uninstall'
# 历史日志不可冒充当前进程加载；只接受本安装目录的日志。
[IO.File]::WriteAllText((Join-Path $logs 'client-exit-987654.log'),"2026-11-05T12:00:00Z [INFO] [pid=987654] AGENT_ACTIVE: jar=$agent`n2026-11-05T12:01:00Z [INFO] [pid=987654] EXIT_ARCHIVE_FINISHED`n")
$status=Get-ClientEnhancementStatus $fake -LogDirectory $logs
Assert-Gui ($status.Loaded.Count -eq 0 -and $null -ne $status.LatestExit) 'historical exit is separate from current activation'
Remove-Item -LiteralPath $agent -Force
$status=Get-ClientEnhancementStatus $fake -LogDirectory $logs
Assert-Gui ($status.State -eq 'partial' -and -not $status.InstalledValid) 'configured but missing JAR is not effective'
$result=Call-Service 'install';Assert-Gui $result.Ok 'repair missing installed JAR'
$result=Call-Service 'uninstall';Assert-Gui $result.Ok 'final fixture uninstall'
Write-Host "PASS: discovery, manual selection, Lucanet JDK, JSON service install/uninstall/reinstall, running protection, evidence separation and repair ($work)"
