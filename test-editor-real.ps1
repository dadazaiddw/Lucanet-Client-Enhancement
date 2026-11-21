[CmdletBinding()]
param([string]$LucanetHome='D:\Soft\LucaNet',[string]$Resources='C:\ProgramData\LucaNet\Youniverse\.resource',[string]$JdkHome='',[ValidateSet('JavaEditorRealProbe','CustomJavaRealProbe','CustomJavaWindowProbe','NativeCompilerIsolationProbe')][string]$Probe='JavaEditorRealProbe',[string]$JarName='client-enhancement.jar',[switch]$Headless)
$ErrorActionPreference='Stop'
$headlessValue=$Headless.IsPresent.ToString().ToLowerInvariant()
$jdk=& (Join-Path $PSScriptRoot 'resolve-lucanet-jdk.ps1') -LucanetHome $LucanetHome -JdkHome $JdkHome
$work=Join-Path $PSScriptRoot ('build\java-editor-real-'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $work -Force|Out-Null
& $jdk.Javac --release 11 -encoding UTF-8 -d $work (Join-Path $PSScriptRoot ('tools\'+$Probe+'.java')) (Join-Path $PSScriptRoot 'tools\JavaLibraryProbe.java')
if($LASTEXITCODE -ne 0){throw 'Editor probe compilation failed'}
foreach($suite in @(@('2311.0.81+6','dom-2311.0.81+6-*.jar','_vm-2311.0.81+6-*.jar'),@('2505.3.4+3','script.engine-5.4.3-*.jar','lisa-6.8.0-*.jar'))){
    if([int]($jdk.Version.Split('.')[0]) -lt 21 -and $suite[0] -like '2505*'){continue}
    $scene=(Get-ChildItem -LiteralPath $Resources -Filter $suite[1] -File).FullName
    $ui=(Get-ChildItem -LiteralPath $Resources -Filter $suite[2] -File).FullName
    $summa=(Get-ChildItem -LiteralPath $Resources -Filter ('summa_client-'+$suite[0]+'-*.jar') -File).FullName
    if(-not $scene -or -not $ui -or -not $summa){throw ('Missing client jars for '+$suite[0])}
    $collections=@(Get-ChildItem -LiteralPath (Join-Path $LucanetHome '.resource') -Filter '*.jar' -File | Where-Object { $_.Name -match '^(eclipse-collections|jdom2|svgSalamander|commons-logging|commons-lang|commons-lang3)-' } | ForEach-Object FullName)
    # Runtime service loading reads every matching resource, so wildcard classpaths mix duplicate
    # dictionaries/providers from cached client releases. Keep one JAR per artifact for the widget probe.
    $preferred=if($suite[0] -like '2311*'){'-(2311\.0\.81\+6|15\.19\.10|26\.13\.8|10\.9\.1|14\.0\.0|4\.1\.0|4\.5\.0|9\.0\.1|1\.6\.0|1\.7\.2|2\.9\.0)-'}else{'-(2505\.11\.4\+11|17\.15\.3|28\.2\.0|11\.4\.0)-'}
    $dependencies=@(Get-ChildItem -LiteralPath $Resources -Filter '*.jar' -File |
        Group-Object { ($_.Name -replace '-\d.*$','') + $(if($_.Name -match '-Windows-'){'-Windows'}else{''}) } | ForEach-Object {
            $pinned=@($_.Group | Where-Object Name -match $preferred)
            if($pinned.Count){$pinned | Sort-Object LastWriteTime -Descending | Select-Object -First 1}
            else{$_.Group | Sort-Object @{Expression={if($_.Name -match '-(\d+\.\d+\.\d+)'){[version]$Matches[1]}else{[version]'0.0.0'}}},LastWriteTime -Descending | Select-Object -First 1}
        } | Where-Object { $_.Name -notmatch '^(dom|script.engine|lisa|_vm|summa_client)-' } | ForEach-Object FullName)
    $widgetDeps=@($dependencies | Where-Object { [IO.Path]::GetFileName($_) -match '^(script.api-|ln\.|base-|awt-|i18n-|osnative-|ui_scaling-|ui_util-|component-|assembly_summa_client-)' -or [IO.Path]::GetFileName($_) -notmatch '-lnsuite-' })
    if($suite[0] -like '2311*') {
        $widgetDeps=@($widgetDeps | Where-Object { [IO.Path]::GetFileName($_) -notmatch '^script.api-' })
        $widgetDeps+=@(Get-ChildItem -LiteralPath $Resources -Filter 'app.ui-2311.0.81+6-*.jar' -File | ForEach-Object FullName)
    }
    $classpath=(@($work,$scene,$ui)+$widgetDeps+$collections) -join ';'
    $testServerTask=$Probe -eq 'CustomJavaRealProbe' -and $suite[0] -like '2505*'
    $scriptFile=$null
    if($testServerTask) {
        & $jdk.Javac --release 11 -proc:none -encoding UTF-8 -cp $classpath -d $work (Join-Path $PSScriptRoot 'tools\ScriptProbeApplicationPaths.java') (Join-Path $PSScriptRoot 'tools\fixtures\CompilerInfo.java')
        if($LASTEXITCODE -ne 0){throw 'Script probe application paths compilation failed'}
        $librarySource=Join-Path $PSScriptRoot 'tools\fixtures\comparator-library'
        $libraryClasses=Join-Path $work 'comparator-classes'
        $libraryJar=Join-Path $work 'lucanet-test-comparator.jar'
        & $jdk.Javac --release 11 -proc:none -encoding UTF-8 -d $libraryClasses (Join-Path $librarySource 'module-info.java') (Join-Path $librarySource 'Test.java')
        if($LASTEXITCODE -ne 0){throw 'Comparator library compilation failed'}
        & $jdk.Jar --create --file $libraryJar -C $libraryClasses .
        if($LASTEXITCODE -ne 0){throw 'Comparator library packaging failed'}
        $scriptFile=Join-Path $work ('server-task-'+$suite[0]+'.lcs')
    }
    if($Probe -eq 'NativeCompilerIsolationProbe') {
        & $jdk.Java '-Xverify:all' '-Djava.awt.headless=true' '-Dfixture.baseline=true' -cp $classpath $Probe (Join-Path $work ('editor-'+$suite[0]+'.png'))
        if($LASTEXITCODE -ne 0){throw 'Native compiler baseline failed'}
    }
    $features=Join-Path $work ('features-'+$suite[0]+'.properties')
    $probeArguments=@(Join-Path $work ('editor-'+$suite[0]+'.png'))
    if($testServerTask){ $probeArguments+=@($scriptFile,(Join-Path $librarySource 'Test.java')) }
    & $jdk.Java '-Xverify:all' "-Djava.awt.headless=$headlessValue" "-javaagent:$PSScriptRoot\dist\$JarName" "-Dlucanet.enhancement.runtime=$work\runtime" "-Dlucanet.enhancement.features=$features" "-Dlucanet.exit.logDir=$work\logs" -cp $classpath $Probe @probeArguments
    if($LASTEXITCODE -ne 0){throw 'Real Java editor probe failed'}
    if($testServerTask) {
        & $jdk.Java '-Xverify:all' '-Djava.awt.headless=true' '-Dfixture.server=true' -cp $classpath $Probe $scriptFile $libraryJar
        if($LASTEXITCODE -ne 0){throw 'Saved script failed in the unenhanced execution process'}
        & $jdk.Java '-Xverify:all' "-Djava.awt.headless=$headlessValue" '-Dfixture.checkNativeEdits=true' "-javaagent:$PSScriptRoot\dist\$JarName" "-Dlucanet.enhancement.runtime=$work\runtime" "-Dlucanet.enhancement.features=$features" "-Dlucanet.exit.logDir=$work\logs" -cp $classpath $Probe $scriptFile
        if($LASTEXITCODE -ne 0){throw 'Native edits failed to reload in the enhanced process'}
    }
}
Write-Host "Java editor evidence: $work"
