[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
& (Join-Path $PSScriptRoot 'build-gui.ps1')
$manager=Join-Path $PSScriptRoot 'enhancement.exe'
if(-not(Test-Path -LiteralPath $manager -PathType Leaf)){throw 'enhancement.exe missing.'}
$bytes=[IO.File]::ReadAllBytes($manager);$pe=[BitConverter]::ToInt32($bytes,0x3c)
if([Text.Encoding]::ASCII.GetString($bytes,$pe,4) -ne ('PE'+[char]0+[char]0)){throw 'Not a PE executable.'}
if([BitConverter]::ToInt16($bytes,$pe+24+68) -ne 2){throw 'GUI is not Windows subsystem.'}
Write-Host 'PASS: enhancement.exe compiled as a DPI-aware Windows GUI without console output'
$project=[xml](Get-Content -LiteralPath (Join-Path $PSScriptRoot 'pom.xml') -Raw)
$fileVersion=[Diagnostics.FileVersionInfo]::GetVersionInfo($manager)
if($fileVersion.ProductVersion -ne $project.project.version){throw 'GUI and Maven project versions differ.'}
$work=Join-Path $PSScriptRoot ('build\gui-window-'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $work -Force | Out-Null
$compiler=Join-Path $env:SystemRoot 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
$sources=@(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'gui') -Filter '*.cs' -File | ForEach-Object FullName)
$testExe=Join-Path $work 'window-tests.exe'
& $compiler /nologo /target:exe /main:WindowTests /warn:4 /warnaserror+ "/out:$testExe" "/resource:$PSScriptRoot\gui\assets\lucanet.ico,Lucanet.ClientIcon" /reference:System.Windows.Forms.dll /reference:System.Drawing.dll /reference:System.Web.Extensions.dll /reference:System.Net.Http.dll /reference:System.IO.Compression.dll /reference:System.IO.Compression.FileSystem.dll @sources (Join-Path $PSScriptRoot 'gui\tests\window-tests.cs') (Join-Path $PSScriptRoot 'gui\tests\update-tests.cs') (Join-Path $PSScriptRoot 'gui\tests\toggle-tests.cs') (Join-Path $PSScriptRoot 'gui\tests\service-tests.cs')
if($LASTEXITCODE -ne 0){throw 'GUI behavior test compilation failed.'}
& $testExe (Join-Path $work 'settings')
if($LASTEXITCODE -ne 0){throw 'GUI behavior regression failed.'}
# 再用与实际程序相同的 DPI manifest 测试隐藏页首次显示，避免仅靠手动缩放模拟。
$dpiTestExe=Join-Path $work 'window-dpi-tests.exe'
& $compiler /nologo /target:exe /main:WindowTests /warn:4 /warnaserror+ "/out:$dpiTestExe" "/resource:$PSScriptRoot\gui\assets\lucanet.ico,Lucanet.ClientIcon" "/win32manifest:$PSScriptRoot\gui\enhancement.manifest" /reference:System.Windows.Forms.dll /reference:System.Drawing.dll /reference:System.Web.Extensions.dll /reference:System.Net.Http.dll /reference:System.IO.Compression.dll /reference:System.IO.Compression.FileSystem.dll @sources (Join-Path $PSScriptRoot 'gui\tests\window-tests.cs') (Join-Path $PSScriptRoot 'gui\tests\update-tests.cs') (Join-Path $PSScriptRoot 'gui\tests\toggle-tests.cs') (Join-Path $PSScriptRoot 'gui\tests\service-tests.cs')
if($LASTEXITCODE -ne 0){throw 'Native DPI test compilation failed.'}
& $dpiTestExe (Join-Path $work 'native') --native-dpi
if($LASTEXITCODE -ne 0){throw 'Native DPI layout regression failed.'}
