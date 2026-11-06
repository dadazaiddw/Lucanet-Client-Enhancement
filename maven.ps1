[CmdletBinding()]
param(
    [string]$LucanetHome = 'D:\Soft\LucaNet',
    [string]$JdkHome = '',
    [string]$MavenHome = 'D:\Soft\Maven\apache-maven-3.9.9',
    [string[]]$MavenArguments = @('package')
)
$ErrorActionPreference = 'Stop'
$jdk = & (Join-Path $PSScriptRoot 'resolve-lucanet-jdk.ps1') -LucanetHome $LucanetHome -JdkHome $JdkHome
$mavenRoot = (Resolve-Path -LiteralPath $MavenHome -ErrorAction Stop).Path
$boot = @(Get-ChildItem -LiteralPath (Join-Path $mavenRoot 'boot') -Filter 'plexus-classworlds-*.jar' -File)
if ($boot.Count -ne 1) { throw 'Expected exactly one Maven plexus-classworlds boot JAR.' }
$configuration = Join-Path $mavenRoot 'bin\m2.conf'
if (-not (Test-Path -LiteralPath $configuration -PathType Leaf)) { throw 'Maven bin/m2.conf is missing.' }
Write-Host "Lucanet JDK: $($jdk.Version) [$($jdk.Home)]"
# 直接调用选出的 java.exe 启动 Maven，避免 mvn.cmd 再从 JAVA_HOME/PATH 选 JDK。
# Maven 参数按数组传递，不拼接为 cmd /c 命令；不修改全局环境变量。
Push-Location -LiteralPath $PSScriptRoot
try {
    & $jdk.Java "-Dclassworlds.conf=$configuration" "-Dmaven.home=$mavenRoot" "-Dmaven.multiModuleProjectDirectory=$PSScriptRoot" -classpath $boot[0].FullName org.codehaus.plexus.classworlds.launcher.Launcher @MavenArguments
    if ($LASTEXITCODE -ne 0) { throw "Maven failed with exit code $LASTEXITCODE." }
} finally {
    Pop-Location
}
