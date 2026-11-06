[CmdletBinding()]
param([switch]$Force)
$ErrorActionPreference='Stop'
$compiler=Join-Path $env:SystemRoot 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
if(-not(Test-Path -LiteralPath $compiler)){$compiler=Join-Path $env:SystemRoot 'Microsoft.NET\Framework\v4.0.30319\csc.exe'}
if(-not(Test-Path -LiteralPath $compiler)){throw 'Windows .NET Framework 编译器不可用。'}
$output=Join-Path $PSScriptRoot 'enhancement.exe'
$sources=@(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'gui') -Filter '*.cs' -File | ForEach-Object FullName)
$manifest=Join-Path $PSScriptRoot 'gui\enhancement.manifest'
# GUI 源码未变化时复用已生成程序，用户打开管理器时也可继续构建 Java Agent。
if(-not $Force -and (Test-Path -LiteralPath $output -PathType Leaf)){
    $newest=@((Get-Item -LiteralPath $sources).LastWriteTimeUtc,(Get-Item -LiteralPath $manifest).LastWriteTimeUtc) | ForEach-Object {$_} | Sort-Object -Descending | Select-Object -First 1
    if((Get-Item -LiteralPath $output).LastWriteTimeUtc -ge $newest){Write-Host "GUI up to date: $output";return}
}
$stage=Join-Path $PSScriptRoot ('build\gui-'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $stage -Force | Out-Null
$candidate=Join-Path $stage 'enhancement.exe'
& $compiler /nologo /target:winexe /platform:anycpu /optimize+ /warn:4 /warnaserror+ "/out:$candidate" "/win32manifest:$manifest" /reference:System.Windows.Forms.dll /reference:System.Drawing.dll /reference:System.Web.Extensions.dll /reference:System.Net.Http.dll /reference:System.IO.Compression.dll /reference:System.IO.Compression.FileSystem.dll @sources
if($LASTEXITCODE -ne 0){throw 'GUI compilation failed.'}
# 先完整编译再替换。Windows 允许时将正在使用的旧程序移为临时备份，
# 不终止已打开的管理器；若文件锁不允许移动则明确报错，保留原程序。
$previous=Join-Path $stage 'previous-enhancement.exe'
if(Test-Path -LiteralPath $output){Move-Item -LiteralPath $output -Destination $previous -ErrorAction Stop}
try{Move-Item -LiteralPath $candidate -Destination $output -ErrorAction Stop}
catch{if(Test-Path -LiteralPath $previous){Move-Item -LiteralPath $previous -Destination $output};throw}
Write-Host "Built GUI: $output"
