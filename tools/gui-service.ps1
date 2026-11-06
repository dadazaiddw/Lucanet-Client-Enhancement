[CmdletBinding()]
param([ValidateSet('discover','status','install','uninstall')][string]$Action='status',[string]$LucanetHome='')
$ErrorActionPreference='Stop'
[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
. (Join-Path $PSScriptRoot 'gui-core.ps1')
try{
    if($Action -eq 'discover'){
        $result=@(Find-SoftwareManagerInstallations)
    }elseif($Action -eq 'status'){
        $result=Get-ClientEnhancementStatus $LucanetHome
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
