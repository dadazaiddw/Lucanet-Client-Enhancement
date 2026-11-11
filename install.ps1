[CmdletBinding()]
param(
    [string]$LucanetHome = 'D:\Soft\LucaNet',
    [Alias('-check')][switch]$Check,
    [Alias('-uninstall')][switch]$Uninstall,
    [switch]$PassThru
)
$ErrorActionPreference = 'Stop'
if ($Check -and $Uninstall) { throw 'Use -Check or -Uninstall, not both.' }
if ($Check) {
    . (Join-Path $PSScriptRoot 'tools\gui-core.ps1')
    $diagnostic = Get-ClientEnhancementStatus $LucanetHome
    $diagnostic.Configurations | Select-Object Path,Enabled
    Write-Host ("安装状态: {0}；当前交付版本: {1}；JDK: {2}" -f $diagnostic.State,$diagnostic.Current,$diagnostic.JdkVersion)
    Write-Host ("Software Manager 运行中: {0}；Lucanet 进程数: {1}；已确认加载的当前进程数: {2}" -f $diagnostic.ManagerRunning,$diagnostic.Processes.Count,$diagnostic.Loaded.Count)
    foreach ($process in $diagnostic.Processes) {
        Write-Host ("  进程: {0}，PID {1}，路径: {2}" -f $process.Name,$process.Id,$process.Path)
    }
    foreach ($evidence in $diagnostic.Loaded) {
        Write-Host ("  当前加载: {0}，PID {1}，退出入口已接入: {2}" -f $evidence.Name,$evidence.Id,$evidence.ExitHook)
    }
    if ($diagnostic.LatestExit) { Write-Host ("最近退出处理（历史记录）: " + $diagnostic.LatestExit.Time) }
    if ($diagnostic.LatestSuppressed) { Write-Host ("最近静默保留提示（历史记录）: " + $diagnostic.LatestSuppressed.Time) }
    foreach ($runtimeError in $diagnostic.RuntimeErrors | Select-Object -First 3) { Write-Host ("运行异常记录: " + $runtimeError) }
    if ($diagnostic.Problems.Count -gt 0) { Write-Host ($diagnostic.Problems -join "`n") }
    if ($diagnostic.JdkError) { Write-Host $diagnostic.JdkError }
    Write-Host '配置完整、启动器已加载、客户端退出已执行是不同状态；历史记录不能代替当前会话验证。'
    return
}
$installRoot = (Resolve-Path -LiteralPath $LucanetHome -ErrorAction Stop).Path.TrimEnd('\')
$installPrefix = $installRoot + '\'
$extensionRoot = Join-Path $installRoot '.client-enhancement'
$installedJar = Join-Path $extensionRoot 'client-enhancement.jar'
$activePointer = Join-Path $extensionRoot 'active.properties'
$agentLine = 'jdkParameter=-javaagent:' + $installedJar
$sourceJar = Join-Path $PSScriptRoot 'dist\client-enhancement.jar'
$utf8 = [Text.UTF8Encoding]::new($false, $true)
if (-not (Test-Path -LiteralPath (Join-Path $installRoot 'LucaNetSoftwareManager.exe') -PathType Leaf)) {
    throw "Not a Lucanet Software Manager installation: $installRoot"
}
function Assert-InstallationPath([string]$Path) {
    $full = [IO.Path]::GetFullPath($Path)
    if (-not $full.StartsWith($installPrefix, [StringComparison]::OrdinalIgnoreCase)) { throw "Path outside installation: $full" }
    # 不允许安装目标路径经 junction/symlink 改写到其他目录。
    $cursor = $full
    while ($cursor -and $cursor.StartsWith($installPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        if (Test-Path -LiteralPath $cursor) {
            if ((Get-Item -LiteralPath $cursor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw "Redirected installation path: $cursor" }
        }
        $cursor = Split-Path -Parent $cursor
    }
}
function Write-Atomic([string]$Path, [byte[]]$Bytes) {
    Assert-InstallationPath $Path
    $temporary = $Path + '.enhancement-' + [Guid]::NewGuid().ToString('N')
    Assert-InstallationPath $temporary
    [IO.File]::WriteAllBytes($temporary, $Bytes)
    $replaceBackup = $temporary + '.previous'
    try {
        if (Test-Path -LiteralPath $Path) { [IO.File]::Replace($temporary, $Path, $replaceBackup) }
        else { [IO.File]::Move($temporary, $Path) }
    } finally {
        if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Force }
        if (Test-Path -LiteralPath $replaceBackup) { Remove-Item -LiteralPath $replaceBackup -Force }
    }
}
$plans = @()
$roots = @(Get-ChildItem -LiteralPath $installRoot -Directory -Force | Where-Object { $_.Name -match '^\.\d+\+\d+$' })
foreach ($release in $roots) {
    foreach ($name in @('LucaNetSoftwareManager.ln10', 'LucaNetKickStart.ln10')) {
        $path = Join-Path $release.FullName $name
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { continue }
        Assert-InstallationPath $path
        $bytes = [IO.File]::ReadAllBytes($path)
        $text = $utf8.GetString($bytes)
        if ($text -notmatch '(?m)^version=2\r?$' -or $text -notmatch '(?m)^mainClass=com/lucanet/lnswmgr_(ui|kickstart)/Main\r?$') {
            throw "Unsupported native configuration: $path"
        }
        $newline = if ($text.Contains("`r`n")) { "`r`n" } else { "`n" }
        # 只移除本安装目录中本增强专用路径的参数，保留其他 Agent 和所有原生配置。
        $pattern = '(?m)^' + [regex]::Escape($agentLine) + '(?:\r?\n|$)'
        $clean = [regex]::Replace($text, $pattern, '')
        $updated = if ($Uninstall) { $clean } else { $clean + $(if ($clean.EndsWith("`n")) { '' } else { $newline }) + $agentLine + $newline }
        $enabled = [regex]::IsMatch($text, '(?m)^' + [regex]::Escape($agentLine) + '\r?$')
        $plans += [pscustomobject]@{Path=$path;Relative=$release.Name + '\' + $name;Before=$bytes;After=$utf8.GetBytes($updated);Enabled=$enabled}
    }
}
if ($plans.Count -eq 0) { throw 'No supported Software Manager/KickStart native launch configuration found.' }
if (-not $Uninstall -and -not (Test-Path -LiteralPath $sourceJar -PathType Leaf)) { throw '交付包缺少 dist/client-enhancement.jar，请重新完整解压运行包。' }
if (-not $Uninstall) {
    # JVM 会在启动期读取 Agent。安装前检查完整包，避免把缺类/未打包的 JAR 写入原启动配置。
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $sourceArchive = [IO.Compression.ZipFile]::OpenRead($sourceJar)
    try {
        foreach ($entry in @('cn/lucanet/enhancement/exit/ExitAgent.class','cn/lucanet/enhancement/exit/NativeBridge.class','cn/lucanet/enhancement/exit/NativeTransformer.class','cn/lucanet/enhancement/internal/asm/ClassReader.class')) {
            if ($null -eq $sourceArchive.GetEntry($entry)) { throw "Incomplete enhancement JAR; missing $entry" }
        }
        if ($null -ne $sourceArchive.GetEntry('cn/lucanet/enhancement/exit/UserExitAction.class')) {
            throw 'Old callback edition detected. Build the current built-in enhancement before installing.'
        }
    } finally { $sourceArchive.Dispose() }
}
# 配置不会动态重新注入已运行的 JVM。仅在对应安装的 Manager/客户端关闭时安装。
. (Join-Path $PSScriptRoot 'tools\gui-core.ps1')
$active = @(Get-SoftwareManagerProcesses $installRoot)
if ($active.Count -gt 0) {
    $runningNames = ($active | ForEach-Object { $_.Name + ' (PID ' + $_.Id + ')' }) -join ', '
    throw "请先正常关闭 Lucanet Software Manager 和客户端，再执行安装或卸载。当前检测到：$runningNames"
}
Assert-InstallationPath $extensionRoot
New-Item -ItemType Directory -Path $extensionRoot -Force | Out-Null
$backupRoot = Join-Path $extensionRoot ('backups\' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [Guid]::NewGuid().ToString('N'))
Assert-InstallationPath $backupRoot
New-Item -ItemType Directory -Path $backupRoot -Force | Out-Null
$oldJar = if (Test-Path -LiteralPath $installedJar) { [IO.File]::ReadAllBytes($installedJar) } else { $null }
$oldPointer = if (Test-Path -LiteralPath $activePointer) { [IO.File]::ReadAllBytes($activePointer) } else { $null }
foreach ($plan in $plans) {
    $backup = Join-Path $backupRoot $plan.Relative
    Assert-InstallationPath $backup
    New-Item -ItemType Directory -Path (Split-Path -Parent $backup) -Force | Out-Null
    [IO.File]::WriteAllBytes($backup, $plan.Before)
}
if ($null -ne $oldJar) { [IO.File]::WriteAllBytes((Join-Path $backupRoot 'previous-agent.jar'), $oldJar) }
if ($null -ne $oldPointer) { [IO.File]::WriteAllBytes((Join-Path $backupRoot 'previous-active.properties'), $oldPointer) }
$changed = @()
try {
    if (-not $Uninstall) {
        Write-Atomic $installedJar ([IO.File]::ReadAllBytes($sourceJar))
        Assert-InstallationPath $activePointer
        if (Test-Path -LiteralPath $activePointer) { Remove-Item -LiteralPath $activePointer -Force }
    }
    foreach ($plan in $plans) {
        Write-Atomic $plan.Path $plan.After
        $changed += $plan
    }
    $status = [pscustomobject]@{Installed=(-not $Uninstall);Agent=$installedJar;Backup=$backupRoot;Configurations=@($plans.Path);Time=(Get-Date -Format o)}
    Write-Atomic (Join-Path $extensionRoot 'status.json') ($utf8.GetBytes(($status | ConvertTo-Json -Depth 4)))
} catch {
    foreach ($plan in $changed) { Write-Atomic $plan.Path $plan.Before }
    if ($null -ne $oldJar -and -not $Uninstall) { Write-Atomic $installedJar $oldJar }
    if ($null -ne $oldPointer -and -not $Uninstall) { Write-Atomic $activePointer $oldPointer }
    throw
}
if ($Uninstall) { Write-Host 'Disabled. Original Software Manager entry no longer loads the enhancement. Backups and inert JAR retained.' }
else { Write-Host 'Installed. Start the ORIGINAL LucaNetSoftwareManager.exe or its existing shortcut; no extra launcher is needed.' }
Write-Host "Backup: $backupRoot"
Write-Host 'After a Software Manager upgrade, open enhancement.exe and install again for the new release configuration.'
if ($PassThru) { $status }
