[CmdletBinding()]
param([string]$LucanetHome='D:\Soft\LucaNet',[string]$Resources='C:\ProgramData\LucaNet\Youniverse\.resource',[string]$JdkHome='')
$ErrorActionPreference='Stop'
$jdk=& (Join-Path $PSScriptRoot 'resolve-lucanet-jdk.ps1') -LucanetHome $LucanetHome -JdkHome $JdkHome
$work=Join-Path $PSScriptRoot ('build\scene-real-'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $work -Force|Out-Null
$asm=Join-Path $LucanetHome '.resource\asm-9.7.jar'
& $jdk.Javac --release 11 -encoding UTF-8 -cp $asm -d $work (Join-Path $PSScriptRoot 'tools\SceneDialogContractProbe.java')
if($LASTEXITCODE -ne 0){throw 'Scene contract probe compilation failed'}
& $jdk.Javac --release 11 -encoding UTF-8 -d $work (Join-Path $PSScriptRoot 'tools\SceneDialogWidgetsProbe.java')
if($LASTEXITCODE -ne 0){throw 'Scene widgets probe compilation failed'}
$manifest=Join-Path $work 'probe.mf'
[IO.File]::WriteAllText($manifest,"Manifest-Version: 1.0`nPremain-Class: SceneDialogContractProbe`nCan-Retransform-Classes: true`n",[Text.UTF8Encoding]::new($false))
$probe=Join-Path $work 'probe.jar'
& $jdk.Jar cfm $probe $manifest -C $work SceneDialogContractProbe.class -C $work 'SceneDialogContractProbe$1.class'
if($LASTEXITCODE -ne 0){throw 'Probe agent packaging failed'}
foreach($suite in @(@('2311.0.81+6','dom-2311.0.81+6-*.jar','_vm-2311.0.81+6-*.jar'),@('2505.3.4+3','script.engine-5.4.3-*.jar','lisa-6.8.0-*.jar'))){
    $scene=(Get-ChildItem -LiteralPath $Resources -Filter $suite[1] -File).FullName
    $ui=(Get-ChildItem -LiteralPath $Resources -Filter $suite[2] -File).FullName
    $summa=(Get-ChildItem -LiteralPath $Resources -Filter ('summa_client-'+$suite[0]+'-*.jar') -File).FullName
    if(-not $scene -or -not $ui -or -not $summa){throw ('Missing client jars for '+$suite[0])}
    $collections=@(Get-ChildItem -LiteralPath (Join-Path $LucanetHome '.resource') -Filter '*.jar' -File | Where-Object { $_.Name -match '^(eclipse-collections|jdom2|svgSalamander)-' } | ForEach-Object FullName)
    # Runtime service loading reads every matching resource, so wildcard classpaths mix duplicate
    # dictionaries/providers from cached client releases. Keep one JAR per artifact for the widget probe.
    $preferred=if($suite[0] -like '2311*'){'-(2311\.0\.81\+6|15\.19\.10|26\.13\.8|10\.9\.1|14\.0\.0|4\.1\.0|4\.5\.0|9\.0\.1)-'}else{'-(2505\.11\.4\+11|17\.15\.3|28\.2\.0|11\.4\.0)-'}
    $dependencies=@(Get-ChildItem -LiteralPath $Resources -Filter '*.jar' -File |
        Group-Object { $_.Name -replace '-\d.*$','' } | ForEach-Object {
            $pinned=@($_.Group | Where-Object Name -match $preferred)
            if($pinned.Count){$pinned | Sort-Object LastWriteTime -Descending | Select-Object -First 1}
            else{$_.Group | Sort-Object @{Expression={if($_.Name -match '-(\d+\.\d+\.\d+)'){[version]$Matches[1]}else{[version]'0.0.0'}}},LastWriteTime -Descending | Select-Object -First 1}
        } | Where-Object { $_.Name -notmatch '^(dom|script.engine|lisa|_vm|summa_client)-' } | ForEach-Object FullName)
    $classpath=(@($work,$asm,$scene,$ui,$summa)+$dependencies+$collections) -join ';'
    & $jdk.Java '-Xverify:all' '-Djava.awt.headless=true' "-javaagent:$PSScriptRoot\dist\client-enhancement.jar" "-javaagent:$probe" "-Dlucanet.enhancement.runtime=$work\runtime" "-Dlucanet.enhancement.features=$work\features.properties" "-Dlucanet.exit.logDir=$work\logs" -cp $classpath SceneDialogContractProbe $scene (Join-Path $work ('scene-'+$suite[0]+'.class'))
    if($LASTEXITCODE -ne 0){throw 'Real scene dialog hook/verification failed'}
    $widgetDeps=@($dependencies | Where-Object { [IO.Path]::GetFileName($_) -match '^(ln\.|base-|awt-|i18n-|osnative-|ui_scaling-|ui_util-|component-|assembly_summa_client-)' -or [IO.Path]::GetFileName($_) -notmatch '-lnsuite-' })
    $widgetClasspath=(@($work,$scene,$ui)+$widgetDeps+$collections) -join ';'
    & $jdk.Java '-Xverify:all' '-Djava.awt.headless=false' "-javaagent:$PSScriptRoot\dist\client-enhancement.jar" "-Dlucanet.enhancement.runtime=$work\runtime" "-Dlucanet.enhancement.features=$work\widgets-features.properties" "-Dlucanet.exit.logDir=$work\logs" -cp $widgetClasspath SceneDialogWidgetsProbe
    if($LASTEXITCODE -ne 0){throw 'Real scene widgets behavior failed'}
}
Write-Host "Scene contract evidence: $work"
