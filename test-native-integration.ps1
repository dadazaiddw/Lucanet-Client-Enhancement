[CmdletBinding()]
param([string]$LucanetHome='D:\Soft\LucaNet',[string]$ResourceDirectory='C:\ProgramData\LucaNet\Youniverse\.resource')
$ErrorActionPreference='Stop'
$jdk=& (Join-Path $PSScriptRoot 'resolve-lucanet-jdk.ps1') -LucanetHome $LucanetHome -MinimumMajorVersion 21
$work=Join-Path $PSScriptRoot ('build\native-integration-'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $work -Force | Out-Null
$agent=Join-Path $PSScriptRoot 'dist\client-enhancement.jar'
& $jdk.Javac --release 11 -encoding UTF-8 -d $work (Join-Path $PSScriptRoot 'tools\RealNativeConfigTest.java') (Join-Path $PSScriptRoot 'tools\RealDialogTest.java')
if($LASTEXITCODE -ne 0){throw 'Integration probes compilation failed.'}
$configs=@(Get-ChildItem -LiteralPath $LucanetHome -Force -Directory | Where-Object Name -match '^\.\d+\+\d+$' | ForEach-Object {Join-Path $_.FullName 'LucaNetSoftwareManager.ln10'} | Where-Object {Test-Path -LiteralPath $_} | Sort-Object -Descending)
$config=$configs[0]
$moduleLine=Get-Content -LiteralPath $config | Where-Object { $_.StartsWith('jdkParameter=--module-path=') }
$modulePath=($moduleLine.Substring('jdkParameter=--module-path='.Length).Split(';') | ForEach-Object {Join-Path $LucanetHome $_}) -join ';'
# 若本机已安装本增强，用不含旧 Agent 行的只读副本检验本轮构建；不修改原配置。
$inputConfig=Join-Path $work 'input-manager.ln10'
$installedOption='jdkParameter=-javaagent:'+(Join-Path $LucanetHome '.client-enhancement\client-enhancement.jar')
$nativeText=[IO.File]::ReadAllText($config)
$nativeText=[regex]::Replace($nativeText, '(?m)^'+[regex]::Escape($installedOption)+'\r?\n?', '')
[IO.File]::WriteAllText($inputConfig,$nativeText,[Text.UTF8Encoding]::new($false))
# 使用真实 named modules，额外验证模块到 bootstrap Agent 的访问。
& $jdk.Java '-Djava.awt.headless=true' "-javaagent:$agent" "-Dlucanet.enhancement.runtime=$work\runtime" "-Dlucanet.exit.logDir=$work\logs" --module-path $modulePath --add-modules ALL-MODULE-PATH -cp $work RealNativeConfigTest $LucanetHome $inputConfig $work
if($LASTEXITCODE -ne 0){throw 'Real native configuration integration failed.'}
# 选取同一套新版本基础/UI 依赖，其他库仅作链接补充，避免先加载缓存旧版本。
$preferred=@(Get-ChildItem -LiteralPath $ResourceDirectory -File | Where-Object { $_.Name -match '^(ln\..*-(17\.15\.3|28\.2\.0)-|lisa-6\.8\.0-|base-11\.4\.0-|file-7\.0\.0-)' } | ForEach-Object FullName)
$classpath=(@($work,$agent)+$preferred+@((Join-Path $ResourceDirectory '*'))) -join ';'
$financialAssembly = Get-ChildItem -LiteralPath $ResourceDirectory -Filter 'assembly_summa_client-*.jar' -File | Sort-Object Name -Descending | Select-Object -First 1
$scenarios = @([pscustomobject]@{Client='financial';Assembly=$financialAssembly.FullName})
$scenarios += @(Get-ChildItem -LiteralPath $ResourceDirectory -Filter 'assembly_summa_admin-*.jar' -File | ForEach-Object {
    [pscustomobject]@{Client='administrator';Assembly=$_.FullName}
})
if($scenarios.Count -lt 2){throw 'Administrator assembly missing for isolated dialog test.'}
foreach($scenario in $scenarios){
    & $jdk.Java '-Djava.awt.headless=true' "-javaagent:$agent" "-Dlucanet.enhancement.runtime=$work\runtime" "-Dlucanet.exit.logDir=$work\logs" -cp $classpath RealDialogTest $scenario.Client $scenario.Assembly
    if($LASTEXITCODE -ne 0){throw "Real $($scenario.Client) dialog suppression integration failed."}
}
Write-Host "Native integration evidence: $work"
