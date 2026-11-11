[CmdletBinding()]
param([string]$JarName='client-enhancement.jar')
$ErrorActionPreference='Stop'
& (Join-Path $PSScriptRoot 'package.ps1') -JarName $JarName
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archivePath=Join-Path $PSScriptRoot 'dist\lucanet-client-enhancement.zip'
$expected=@('enhancement.exe','install.ps1','resolve-lucanet-jdk.ps1','tools/gui-core.ps1','tools/gui-service.ps1','dist/client-enhancement.jar')
$work=Join-Path $PSScriptRoot ('build\runtime-package-'+[Guid]::NewGuid().ToString('N'))
$runtime=Join-Path $work '仅运行包 中文 空格'
New-Item -ItemType Directory -Path $work -Force|Out-Null
function Assert-Package($Value,[string]$Message){if(-not $Value){throw "Runtime package test failed: $Message"}}
function Get-PackageHash([string]$Path){
    $sha=[Security.Cryptography.SHA256]::Create();$stream=[IO.File]::OpenRead($Path)
    try{[BitConverter]::ToString($sha.ComputeHash($stream)).Replace('-','')}
    finally{$stream.Dispose();$sha.Dispose()}
}
$zip=[IO.Compression.ZipFile]::OpenRead($archivePath)
try{
    $names=@($zip.Entries|ForEach-Object FullName)
    Assert-Package ($names.Count -eq 6 -and @(Compare-Object ($expected|Sort-Object) ($names|Sort-Object)).Count -eq 0) 'ZIP must contain exactly the six runtime dependencies'
    foreach($entry in $zip.Entries){Assert-Package ($entry.Length -gt 0) ('empty entry: '+$entry.FullName)}
}finally{$zip.Dispose()}
[IO.Compression.ZipFile]::ExtractToDirectory($archivePath,$runtime)
foreach($name in $expected){
    $source=if($name -eq 'dist/client-enhancement.jar'){Join-Path (Join-Path $PSScriptRoot 'dist') $JarName}else{Join-Path $PSScriptRoot $name}
    Assert-Package ((Get-PackageHash (Join-Path $runtime $name)) -eq (Get-PackageHash $source)) ('source parity: '+$name)
}
$runtimeJar=Join-Path $runtime 'dist\client-enhancement.jar'
$jar=[IO.Compression.ZipFile]::OpenRead($runtimeJar)
try{Assert-Package ($null -ne $jar.GetEntry('META-INF/ASM-LICENSE.txt')) 'ASM license retained inside JAR'}finally{$jar.Dispose()}

# 独立探针从解压包加载 GUI 和更新器，只用 .NET 框架，不依赖工程源码或 README。
$probeSource=Join-Path $work 'RuntimePackageProbe.cs'
@'
using System;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Windows.Forms;
class RuntimePackageProbe {
    [STAThread]
    static int Main(string[] args) {
        Application.EnableVisualStyles(); Application.SetCompatibleTextRenderingDefault(false);
        try {
            string root=args[0];
            var asm=Assembly.LoadFrom(Path.Combine(root,"enhancement.exe"));
            const BindingFlags instance=BindingFlags.Instance|BindingFlags.NonPublic|BindingFlags.Public;
            object service=Activator.CreateInstance(asm.GetType("PowerShellEnhancementService"),instance,null,new object[]{root},null);
            object preferences=Activator.CreateInstance(asm.GetType("GuiSettingsStore"),instance,null,new object[]{args[1]},null);
            var formType=asm.GetType("EnhancementForm");
            var constructor=formType.GetConstructors(instance).Single(c=>c.GetParameters().Length==7);
            using(var form=(Form)constructor.Invoke(new object[]{new string[0],service,preferences,null,null,false,null})){
                var handle=form.Handle;
                if(form.Text!="Lucanet Client Enhancement")throw new Exception("GUI title mismatch");
                var navigate=formType.GetMethod("Navigate",instance);
                foreach(string page in new[]{"workspace","application","features","workspace"}){
                    navigate.Invoke(form,new object[]{page});form.PerformLayout();
                }
                form.Close();
            }
            var verify=asm.GetType("GitHubUpdateService").GetMethod("VerifyAndExtract",BindingFlags.NonPublic|BindingFlags.Static);
            string updated=(string)verify.Invoke(null,new object[]{args[2],args[3],args[4]});
            if(Directory.GetFiles(updated,"*",SearchOption.AllDirectories).Length!=6)throw new Exception("Updater expected six runtime files");
            Console.WriteLine("PASS: GUI constructs all pages from runtime package; compiled updater verifies/extracts the minimal ZIP");
            return 0;
        }catch(Exception e){Console.Error.WriteLine(e);return 1;}
    }
}
'@ | Set-Content -LiteralPath $probeSource -Encoding UTF8
$compiler=Join-Path $env:SystemRoot 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
if(-not(Test-Path -LiteralPath $compiler)){$compiler=Join-Path $env:SystemRoot 'Microsoft.NET\Framework\v4.0.30319\csc.exe'}
$probe=Join-Path $work 'runtime-package-probe.exe'
& $compiler /nologo /target:exe /warn:4 /warnaserror+ "/out:$probe" /reference:System.Windows.Forms.dll /reference:System.Drawing.dll $probeSource
if($LASTEXITCODE -ne 0){throw 'Runtime package probe compilation failed.'}
& $probe $runtime (Join-Path $work 'preferences\ui.json') $archivePath (Get-PackageHash $archivePath) (Join-Path $work 'updater-extracted')
if($LASTEXITCODE -ne 0){throw 'Runtime GUI/updater probe failed.'}

# 模拟安装目录完全位于项目 build 内。所有操作均调用 ZIP 解压出的后台/安装脚本。
$fake=Join-Path $work '假 Lucanet 安装目录'
$release=Join-Path $fake '.2604+15'
New-Item -ItemType Directory -Path $release -Force|Out-Null
[IO.File]::WriteAllBytes((Join-Path $fake 'LucaNetSoftwareManager.exe'),[byte[]]@())
$original=@{}
foreach($pair in @(@('LucaNetSoftwareManager.ln10','ui'),@('LucaNetKickStart.ln10','kickstart'))){
    $path=Join-Path $release $pair[0]
    $text="version=2`njdkParameter=-Xmx256m`njdkParameter=-javaagent:other-agent.jar`nmainClass=com/lucanet/lnswmgr_$($pair[1])/Main`n`n"
    [IO.File]::WriteAllText($path,$text,[Text.UTF8Encoding]::new($false));$original[$path]=$text
}
foreach($version in @('17.0.4+8','21.0.4+7','21.0.11+10')){
    $jdk=Join-Path $fake ('.jdk\LucaNetJDK-'+$version)
    New-Item -ItemType Directory -Path (Join-Path $jdk 'bin') -Force|Out-Null
    foreach($name in @('java','javac','jar')){[IO.File]::WriteAllBytes((Join-Path $jdk ('bin\'+$name+'.exe')),[byte[]]@())}
    [IO.File]::WriteAllText((Join-Path $jdk 'release'),'JAVA_RUNTIME_VERSION="'+$version+'"')
}
$windowsPs=Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
$servicePath=Join-Path $runtime 'tools\gui-service.ps1'
function Invoke-PackageService([string]$Action){
    $json=& $windowsPs -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $servicePath -Action $Action -LucanetHome $fake
    if($LASTEXITCODE -ne 0){throw ('Extracted GUI service failed: '+($json -join "`n"))}
    $result=($json -join "`n")|ConvertFrom-Json
    Assert-Package $result.Ok ('GUI service '+$Action)
    return $result.Data
}
# 从与运行包不同的工作目录调用，确认所有路径相对脚本/应用目录，而非当前目录。
Push-Location -LiteralPath $work
try{
    $null=Invoke-PackageService 'discover'
    $status=Invoke-PackageService 'status'
    Assert-Package ($status.State -eq 'not-installed' -and $status.JdkVersion -eq '21.0.11+10') 'status and bundled highest JDK selection'
    $installResult=Invoke-PackageService 'install'
    Assert-Package ($installResult.Status.State -eq 'installed' -and $installResult.Status.Current) 'installation completed from extracted package'
    $installedJar=Join-Path $fake '.client-enhancement\client-enhancement.jar'
    Assert-Package ((Get-PackageHash $installedJar) -eq (Get-PackageHash $runtimeJar)) 'installed JAR is exact packaged Agent'
    Assert-Package (Test-Path -LiteralPath $installResult.Backup -PathType Container) 'backup created'
    $null=Invoke-PackageService 'install'
    & $windowsPs -NoLogo -NoProfile -ExecutionPolicy Bypass -File (Join-Path $runtime 'install.ps1') -LucanetHome $fake --check
    if($LASTEXITCODE -ne 0){throw 'Extracted read-only check failed.'}
    $uninstallResult=Invoke-PackageService 'uninstall'
    Assert-Package ($uninstallResult.Status.State -eq 'not-installed') 'uninstall from runtime package'
    foreach($path in $original.Keys){Assert-Package ([IO.File]::ReadAllText($path) -ceq $original[$path]) 'original configuration restored'}
    $reinstallResult=Invoke-PackageService 'install'
    Assert-Package $reinstallResult.Status.Current 'reinstall from runtime package'
    $null=Invoke-PackageService 'uninstall'
    foreach($path in $original.Keys){Assert-Package ([IO.File]::ReadAllText($path) -ceq $original[$path]) 'reinstall/uninstall restores configuration'}
}finally{Pop-Location}
Assert-Package (@(Get-ChildItem -LiteralPath $runtime -Recurse -Force -File).Count -eq 6) 'runtime folder did not require/create source or doc files'
Write-Host "PASS: exact six-file runtime package, discovery/status, highest Lucanet JDK, installation/check/uninstall/reinstall and preserved third-party license ($work)"
