# GUI 与 --check 的只读检查逻辑；部署写入仍由已验证的 install.ps1 完成。
$script:GuiPackageRoot = Split-Path -Parent $PSScriptRoot

function Get-ClientEnhancementHash([string]$Path) {
    $stream=[IO.File]::OpenRead($Path); $sha=[Security.Cryptography.SHA256]::Create()
    try { [BitConverter]::ToString($sha.ComputeHash($stream)).Replace('-','') }
    finally { $sha.Dispose(); $stream.Dispose() }
}

function Resolve-SoftwareManagerRoot([string]$Candidate) {
    if([string]::IsNullOrWhiteSpace($Candidate)){return $null}
    try {
        $path=[Environment]::ExpandEnvironmentVariables($Candidate.Trim().Trim('"'))
        if(Test-Path -LiteralPath $path -PathType Leaf){$path=Split-Path -Parent $path}
        if(-not(Test-Path -LiteralPath $path -PathType Container)){return $null}
        $path=(Resolve-Path -LiteralPath $path).Path.TrimEnd('\')
        # 注册表/进程也可能指向 .bin 下的启动器，最多向上寻找 3 层。
        for($i=0;$i -lt 4 -and $path;$i++){
            if(Test-Path -LiteralPath (Join-Path $path 'LucaNetSoftwareManager.exe') -PathType Leaf){return $path}
            $path=Split-Path -Parent $path
        }
    } catch { }
    return $null
}

function Find-SoftwareManagerInstallations([string[]]$AdditionalCandidates=@()) {
    $found=@{}
    function Add-Candidate([string]$Value,[string]$Source){
        $root=Resolve-SoftwareManagerRoot $Value
        if($root -and -not $found.ContainsKey($root)){$found[$root]=[pscustomobject]@{Root=$root;Source=$Source}}
    }
    foreach($candidate in $AdditionalCandidates){Add-Candidate $candidate '指定目录'}
    foreach($process in Get-Process -ErrorAction SilentlyContinue | Where-Object ProcessName -match '^LucaNet'){
        try{Add-Candidate $process.Path '运行中的进程'}catch{}
    }
    foreach($hive in @('HKCU:\SOFTWARE','HKLM:\SOFTWARE','HKLM:\SOFTWARE\WOW6432Node')){
        $uninstall=Join-Path $hive 'Microsoft\Windows\CurrentVersion\Uninstall'
        foreach($entry in Get-ChildItem -LiteralPath $uninstall -ErrorAction SilentlyContinue){
            $properties=Get-ItemProperty -LiteralPath $entry.PSPath -ErrorAction SilentlyContinue
            if($properties.DisplayName -match 'Luca[Nn]et'){
                Add-Candidate $properties.InstallLocation '安装注册表'
                if($properties.DisplayIcon){Add-Candidate ($properties.DisplayIcon -replace ',\d+$','') '安装注册表'}
            }
        }
        $appPath=Join-Path $hive 'Microsoft\Windows\CurrentVersion\App Paths\LucaNetSoftwareManager.exe'
        try{$key=Get-Item -LiteralPath $appPath -ErrorAction Stop;Add-Candidate ($key.GetValue('')) '程序注册表'}catch{}
    }
    # 常见安装位置：仅有限目录，不扫描整盘/网络盘。
    $basePaths=@($env:ProgramFiles,${env:ProgramFiles(x86)},$env:LOCALAPPDATA,'C:\','D:\','C:\Soft','D:\Soft','C:\Software','D:\Software')
    foreach($base in $basePaths){
        if(-not $base){continue}
        foreach($relative in @('LucaNet','LucaNet\Youniverse','LucaNet\Youniverse\program','Lucanet Software Manager')){
            Add-Candidate (Join-Path $base $relative) '常用安装位置'
        }
    }
    # 用户/公共开始菜单与桌面快捷方式，只读取目标路径。
    $shortcutRoots=@([Environment]::GetFolderPath('Desktop'),[Environment]::GetFolderPath('CommonDesktopDirectory'),[Environment]::GetFolderPath('Programs'),[Environment]::GetFolderPath('CommonPrograms'))
    $shell=$null
    try{
        $shell=New-Object -ComObject WScript.Shell
        foreach($directory in $shortcutRoots){
            if(-not $directory -or -not(Test-Path -LiteralPath $directory)){continue}
            foreach($shortcut in Get-ChildItem -LiteralPath $directory -Filter '*luca*.lnk' -Recurse -ErrorAction SilentlyContinue){
                try{$link=$shell.CreateShortcut($shortcut.FullName);Add-Candidate $link.TargetPath '快捷方式'}catch{}
            }
        }
    }catch{}finally{if($shell){[void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($shell)}}
    return @($found.Values | Sort-Object Root)
}

function Get-SoftwareManagerProcesses([string]$Root) {
    $items=@()
    foreach($process in Get-Process -ErrorAction SilentlyContinue | Where-Object ProcessName -match '^LucaNet'){
        try{
            $path=$process.Path
            if($path -and $path.StartsWith($Root.TrimEnd('\')+'\',[StringComparison]::OrdinalIgnoreCase)){
                $items += [pscustomobject]@{Id=$process.Id;Name=$process.ProcessName;Path=$path;Started=$process.StartTime.ToUniversalTime().ToString('o');Matched=$true}
            }elseif(-not $path){
                $items += [pscustomobject]@{Id=$process.Id;Name=$process.ProcessName;Path='';Started='';Matched=$false}
            }
        }catch{$items += [pscustomobject]@{Id=$process.Id;Name=$process.ProcessName;Path='';Started='';Matched=$false}}
    }
    return $items
}

function Get-ClientEnhancementStatus([string]$LucanetHome,[string]$LogDirectory='') {
    $root=Resolve-SoftwareManagerRoot $LucanetHome
    if(-not $root){throw '请选择包含 LucaNetSoftwareManager.exe 的安装目录。'}
    $installedJar=Join-Path $root '.client-enhancement\client-enhancement.jar'
    $sourceJar=Join-Path $script:GuiPackageRoot 'dist\client-enhancement.jar'
    $expectedLine='jdkParameter=-javaagent:'+$installedJar
    $configs=@();$problems=@()
    foreach($release in Get-ChildItem -LiteralPath $root -Directory -Force | Where-Object Name -match '^\.\d+\+\d+$'){
        foreach($name in @('LucaNetSoftwareManager.ln10','LucaNetKickStart.ln10')){
            $path=Join-Path $release.FullName $name
            if(-not(Test-Path -LiteralPath $path -PathType Leaf)){continue}
            try{
                $text=[IO.File]::ReadAllText($path)
                $valid=$text -match '(?m)^version=2\r?$' -and $text -match '(?m)^mainClass=com/lucanet/lnswmgr_(ui|kickstart)/Main\r?$'
                $lines=[regex]::Matches($text,'(?m)^'+[regex]::Escape($expectedLine)+'\r?$')
                $configs += [pscustomobject]@{Path=$path;Enabled=($lines.Count -eq 1);Valid=$valid}
                if(-not $valid){$problems+='发现不支持的启动配置：'+$path}
                if($lines.Count -gt 1){$problems+='启动配置中有重复的增强参数：'+$path}
            }catch{$problems+='无法读取启动配置：'+$path}
        }
    }
    if($configs.Count -eq 0){$problems+='没有找到受支持的 Manager / KickStart 启动配置。'}
    $enabled=@($configs|Where-Object Enabled).Count
    $installedValid=$false;$current=$false
    if(Test-Path -LiteralPath $installedJar -PathType Leaf){
        try{
            Add-Type -AssemblyName System.IO.Compression.FileSystem
            $zip=[IO.Compression.ZipFile]::OpenRead($installedJar)
            try{
                $required=@('cn/lucanet/enhancement/exit/ExitAgent.class','cn/lucanet/enhancement/exit/NativeBridge.class','cn/lucanet/enhancement/exit/NativeTransformer.class','cn/lucanet/enhancement/internal/asm/ClassReader.class')
                $installedValid=@($required|Where-Object {$null -eq $zip.GetEntry($_)}).Count -eq 0
            }finally{$zip.Dispose()}
            if($installedValid -and (Test-Path -LiteralPath $sourceJar)){$current=(Get-ClientEnhancementHash $installedJar) -eq (Get-ClientEnhancementHash $sourceJar)}
        }catch{$problems+='已安装的增强包无法读取或已损坏。'}
    }
    $state=if($enabled -eq 0){'not-installed'}elseif($enabled -eq $configs.Count -and $installedValid -and $problems.Count -eq 0){'installed'}else{'partial'}
    if($enabled -gt 0 -and -not $installedValid){$problems+='启动参数已配置，但增强 JAR 缺失或不完整。'}
    $jdkHome='';$jdkVersion='';$jdkError=''
    try{
        $jdk=& (Join-Path $script:GuiPackageRoot 'resolve-lucanet-jdk.ps1') -LucanetHome $root
        $jdkHome=$jdk.Home;$jdkVersion=$jdk.Version
    }catch{$jdkError='Lucanet 的 .jdk 中没有可用的完整 JDK（至少 Java 11）。'}
    $processes=@(Get-SoftwareManagerProcesses $root)
    $managerRunning=@($processes|Where-Object {$_.Matched -and $_.Name -ieq 'LucaNetSoftwareManager'}).Count -gt 0
    $unknown=@($processes|Where-Object {-not $_.Matched}).Count -gt 0
    if(-not $LogDirectory){$LogDirectory=Join-Path ([Environment]::GetFolderPath('UserProfile')) '.lucanet-exit-enhancement\logs'}
    $loaded=@();$latestExit=$null;$latestSuppressed=$null;$runtimeErrors=@()
    # 仅选择日志中 Agent 路径匹配本安装目录的记录；历史记录与当前运行证据分开。
    foreach($file in Get-ChildItem -LiteralPath $LogDirectory -Filter 'client-exit-*.log' -File -ErrorAction SilentlyContinue | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 24){
        try{
            $head=@(Get-Content -LiteralPath $file.FullName -Encoding UTF8 -TotalCount 8)
            $agentRecords=@($head|Where-Object {$_ -match 'AGENT_ACTIVE:' -and $_.EndsWith('jar='+$installedJar,[StringComparison]::OrdinalIgnoreCase)})
            if($agentRecords.Count -eq 0){continue}
            $tail=@(Get-Content -LiteralPath $file.FullName -Encoding UTF8 -Tail 100)
            $pidNumber=[int]([regex]::Match($file.Name,'client-exit-(\d+)').Groups[1].Value)
            $active=$processes|Where-Object {$_.Id -eq $pidNumber -and $_.Matched}|Select-Object -First 1
            if($active){
                $line=$agentRecords | Select-Object -Last 1
                $stamp=[DateTimeOffset]::Parse(($line -split ' ')[0])
                if($stamp.UtcDateTime -ge ([DateTimeOffset]::Parse($active.Started)).UtcDateTime.AddSeconds(-5)){
                    $hook=@(($head+$tail)|Where-Object {$_ -match 'TRANSFORMED: ln/application/Application'}).Count -gt 0
                    $loaded += [pscustomobject]@{Id=$pidNumber;Name=$active.Name;Time=$stamp.ToString('o');Log=$file.FullName;ExitHook=$hook}
                }
            }
            foreach($line in $tail){
                if($line -match 'EXIT_ARCHIVE_FINISHED'){
                    $stamp=[DateTimeOffset]::Parse(($line -split ' ')[0])
                    if(-not $latestExit -or $stamp -gt [DateTimeOffset]::Parse($latestExit.Time)){$latestExit=[pscustomobject]@{Time=$stamp.ToString('o');Log=$file.FullName}}
                }
                if($line -match 'EXIT_DIALOG_SUPPRESSED'){
                    $stamp=[DateTimeOffset]::Parse(($line -split ' ')[0])
                    if(-not $latestSuppressed -or $stamp -gt [DateTimeOffset]::Parse($latestSuppressed.Time)){$latestSuppressed=[pscustomobject]@{Time=$stamp.ToString('o');Log=$file.FullName}}
                }
                if($line -match 'UNSUPPORTED_CLASS|TRANSFORM_FAILED|AGENT_DISABLED'){$runtimeErrors += $line}
            }
        }catch{ }
    }
    return [pscustomobject]@{
        Root=$root;State=$state;Current=$current;ConfigCount=$configs.Count;EnabledCount=$enabled;Configurations=$configs
        InstalledValid=$installedValid;SourceAvailable=(Test-Path -LiteralPath $sourceJar -PathType Leaf)
        JdkHome=$jdkHome;JdkVersion=$jdkVersion;JdkError=$jdkError
        ManagerRunning=$managerRunning;Processes=$processes;UnknownProcesses=$unknown
        CanModify=($processes.Count -eq 0);Problems=$problems
        Loaded=$loaded;LatestExit=$latestExit;LatestSuppressed=$latestSuppressed;RuntimeErrors=$runtimeErrors
        LogDirectory=$LogDirectory;BackupRoot=(Join-Path $root '.client-enhancement\backups')
    }
}
