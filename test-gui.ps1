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
$evidenceLog=Join-Path $logs 'client-exit-987654.log'
[IO.File]::AppendAllText($evidenceLog,"2026-11-05T12:02:00Z [INFO] [pid=987654] EXIT_DIALOG_SUPPRESSED: remaining file kept in place`n")
$status=Get-ClientEnhancementStatus $fake -LogDirectory $logs
Assert-Gui ($null -ne $status.LatestSuppressed -and ([DateTimeOffset]::Parse($status.LatestSuppressed.Time)).UtcDateTime.Minute -eq 2) 'legacy suppression evidence remains visible'
[IO.File]::AppendAllText($evidenceLog,"2026-11-05T12:03:00Z [INFO] [pid=987654] TEMP_FILE_DIALOG_SUPPRESSED: Financial Client unknown temporary file kept in place`n")
$status=Get-ClientEnhancementStatus $fake -LogDirectory $logs
Assert-Gui ($null -ne $status.LatestSuppressed -and ([DateTimeOffset]::Parse($status.LatestSuppressed.Time)).UtcDateTime.Minute -eq 3) 'session-timeout suppression evidence visible'
# 现场回归：有运行错误时，Get-Content 的扩展字符串绝不能进入 JSON。
# 先检查属性，旧实现会在这里快速失败，不让回归测试也陷入序列化假死。
$errorLine='2026-11-11T00:00:00Z [ERROR] [pid=987654] LINK_NEW_WINDOW_FAILED: 中文日志'
[IO.File]::AppendAllText($evidenceLog,$errorLine+"`n")
$status=Get-ClientEnhancementStatus $fake -LogDirectory $logs
Assert-Gui ($status.RuntimeErrors.Count -eq 1) 'runtime error included for diagnostics'
Assert-Gui (@($status.RuntimeErrors[0].PSObject.Properties | Where-Object Name -match '^PS(Path|ParentPath|ChildName|Drive|Provider)$').Count -eq 0) 'runtime error is plain text, not provider metadata'
$jsonWatch=[Diagnostics.Stopwatch]::StartNew()
$serialized=@{Ok=$true;Data=$status}|ConvertTo-Json -Depth 12 -Compress
$parsed=$serialized | ConvertFrom-Json
Assert-Gui ($jsonWatch.ElapsedMilliseconds -lt 2000 -and $serialized.Length -lt 20000) 'error JSON serializes promptly with bounded payload'
Assert-Gui ($parsed.Data.RuntimeErrors[0] -is [string] -and $parsed.Data.RuntimeErrors[0] -eq $errorLine) 'JSON preserves error string and Chinese text'
Assert-Gui ($serialized -notmatch 'PSProvider|PSDrive|PSParentPath') 'no recursive PowerShell provider graph in JSON'
Remove-Item -LiteralPath $agent -Force
$status=Get-ClientEnhancementStatus $fake -LogDirectory $logs
Assert-Gui ($status.State -eq 'partial' -and -not $status.InstalledValid) 'configured but missing JAR is not effective'
$result=Call-Service 'install';Assert-Gui $result.Ok 'repair missing installed JAR'
# 状态检查要识别热更新指针；普通离线安装必须清除旧指针。
$versions=Join-Path $fake '.client-enhancement\versions';New-Item -ItemType Directory -Path $versions -Force|Out-Null
$digest=(Get-ClientEnhancementHash $agent).ToLowerInvariant()
Copy-Item -LiteralPath $agent -Destination (Join-Path $versions ($digest+'.jar'))
$pointer=Join-Path $fake '.client-enhancement\active.properties'
[IO.File]::WriteAllText($pointer,"sha256=$digest`nversion=1.0.2`n")
$status=Get-ClientEnhancementStatus $fake -LogDirectory $logs
Assert-Gui ($status.Current -and $status.State -eq 'installed') 'active hot-update version is current'
[IO.File]::WriteAllText($pointer,'sha256=invalid')
$status=Get-ClientEnhancementStatus $fake -LogDirectory $logs
Assert-Gui ($status.State -eq 'partial' -and -not $status.Current) 'invalid active pointer is not effective'
$result=Call-Service 'update';Assert-Gui ($result.Ok -and -not(Test-Path -LiteralPath $pointer)) 'offline update repairs pointer'
$result=Call-Service 'uninstall';Assert-Gui $result.Ok 'final fixture uninstall'
Write-Host "PASS: discovery, manual selection, Lucanet JDK, JSON service install/uninstall/reinstall, running protection, evidence separation and repair ($work)"
