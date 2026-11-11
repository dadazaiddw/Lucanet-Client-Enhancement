[CmdletBinding()]
param([ValidateSet('discover','status','install','uninstall','update')][string]$Action='status',[string]$LucanetHome='')
$ErrorActionPreference='Stop'
[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
. (Join-Path $PSScriptRoot 'gui-core.ps1')
try{
    if($Action -eq 'discover'){
        $result=@(Find-SoftwareManagerInstallations)
    }elseif($Action -eq 'status'){
        $result=Get-ClientEnhancementStatus $LucanetHome
    }elseif($Action -eq 'update'){
        $status=Get-ClientEnhancementStatus $LucanetHome
        if($status.State -eq 'not-installed'){
            # 仅管理器自更新时不顺带安装用户尚未启用的增强。
            $result=[pscustomobject]@{Status=$status;Updated=$false}
        }elseif($status.CanModify){
            & (Join-Path $script:GuiPackageRoot 'install.ps1') -LucanetHome $status.Root 6>$null
            $result=[pscustomobject]@{Status=(Get-ClientEnhancementStatus $status.Root);Updated=$true}
        }else{
            if($status.State -ne 'installed'){throw '安装配置不完整，请正常关闭 Lucanet 后点击安装修复。'}
            if(@($status.Processes | Where-Object {-not $_.Matched}).Count -gt 0){throw '无法确认部分进程所属安装目录，请以相同用户身份操作，或正常关闭后安装。'}
            $runtime=Join-Path $env:LOCALAPPDATA 'Lucanet\ClientEnhancement\runtime\processes'
            foreach($process in $status.Processes){
                if(-not(Test-Path -LiteralPath (Join-Path $runtime ($process.Id.ToString()+'\state.properties')))){
                    throw '当前运行的版本尚不支持热更新。首次升级到 v1.0.1 请正常关闭客户端和 Manager 后安装；后续兼容版本无需重新登录。'
                }
            }
            $jdk=& (Join-Path $script:GuiPackageRoot 'resolve-lucanet-jdk.ps1') -LucanetHome $status.Root
            $agent=Join-Path $script:GuiPackageRoot 'dist\client-enhancement.jar'
            $installed=Join-Path $status.Root '.client-enhancement\client-enhancement.jar'
            $pids=($status.Processes | ForEach-Object {$_.Id.ToString()}) -join ','
            # 不让 Java stderr 在 PowerShell 5.1 中误变成终止错误；明确检查退出码。
            $start=New-Object Diagnostics.ProcessStartInfo
            $start.FileName=$jdk.Java
            $start.Arguments='-Dfile.encoding=UTF-8 -cp "'+$agent+'" cn.lucanet.enhancement.exit.HotUpdateClient "'+$installed+'" "'+$agent+'" '+$pids
            $start.UseShellExecute=$false;$start.CreateNoWindow=$true
            $start.RedirectStandardOutput=$true;$start.RedirectStandardError=$true
            $start.StandardOutputEncoding=[Text.Encoding]::UTF8;$start.StandardErrorEncoding=[Text.Encoding]::UTF8
            $updater=[Diagnostics.Process]::Start($start)
            try{
                $stdout=$updater.StandardOutput.ReadToEndAsync();$stderr=$updater.StandardError.ReadToEndAsync()
                $updater.WaitForExit()
                if($updater.ExitCode -ne 0){throw ('热更新未完成，当前窗口保留。详情：'+$stdout.Result+$stderr.Result)}
            }finally{$updater.Dispose()}
            $result=[pscustomobject]@{Status=(Get-ClientEnhancementStatus $status.Root);Updated=$true;Hot=$true}
        }
    }else{
        $status=Get-ClientEnhancementStatus $LucanetHome
        if(-not $status.CanModify){throw '检测到 Lucanet 正在运行。请正常关闭 Software Manager 和客户端，然后点击刷新状态。'}
        if($Action -eq 'install' -and $status.JdkError){throw $status.JdkError}
        $args=@{LucanetHome=$status.Root;PassThru=$true}
        if($Action -eq 'uninstall'){$args.Uninstall=$true}
        $deployment=@(& (Join-Path $script:GuiPackageRoot 'install.ps1') @args 6>$null)
        $result=[pscustomobject]@{Status=(Get-ClientEnhancementStatus $status.Root);Backup=$deployment[-1].Backup}
    }
    [Console]::WriteLine((@{Ok=$true;Data=$result}|ConvertTo-Json -Depth 12 -Compress))
}catch{
    $permission=$_.Exception -is [UnauthorizedAccessException] -or $_.Exception.Message -match 'Access.*denied|拒绝访问|Unauthorized'
    $message=if($permission){'当前账户没有写入安装目录的权限。请点击“以管理员身份重新打开”。'}else{$_.Exception.Message}
    [Console]::WriteLine((@{Ok=$false;Message=$message;Permission=$permission}|ConvertTo-Json -Depth 4 -Compress))
    exit 1
}
