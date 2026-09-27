param(
    [Parameter(Mandatory)][ValidatePattern('^[0-9a-fA-F]{64}$')][string]$ExpectedJarSha256,
    [Parameter(Mandatory)][string]$PrivateAudioDirectory,
    [ValidateSet('Functional', 'Performance', 'MacroMatrix')][string]$Mode = 'Functional',
    [string]$Java = 'C:/Program Files/Eclipse Adoptium/jdk-25.0.4.7-hotspot/bin/java.exe'
)

# Supplemental acceptance, not a replacement for clean package or EXE startup.
# All configuration/fixtures/logs are isolated; private audio is read-only.
$ErrorActionPreference = 'Stop'
$workspace = (Get-Item -LiteralPath (Join-Path $PSScriptRoot '..')).FullName
$installed = 'C:/Program Files/QuickMaster/app'
$jar = (Get-Item -LiteralPath "$installed/quickmaster.jar").FullName
$vendor = @(Get-ChildItem -LiteralPath $installed -Filter 'dspark-*.jar')
if ((Get-FileHash -LiteralPath $jar).Hash -ne $ExpectedJarSha256) { throw 'Wrong installed application JAR' }
if ($vendor.Count -ne 1 -or $vendor[0].Name -ne 'dspark-0.2.1.jar' -or
        (Get-FileHash -LiteralPath $vendor[0].FullName).Hash -ne '4f8759e3334ce1970382076cfe2015c44dd7f1378f625eeff831e70915fd4382') {
    throw 'Wrong or ambiguous installed DSPark dependency'
}
$run = Join-Path $workspace ('dist/installed-audit-' + $Mode.ToLowerInvariant() + '-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0,8))
[void](New-Item -ItemType Directory -Path $run)
$originalAppData = $env:APPDATA
$results = [Collections.Generic.List[object]]::new()
$summary = [ordered]@{ status='RUNNING'; mode=$Mode; installedJar=$jar; sha256=$ExpectedJarSha256; probes=$results; output=$run }
$byNow = Join-Path $PrivateAudioDirectory 'By Now.wav'
$quiet = Join-Path $PrivateAudioDirectory 'Quiet Gold.wav'

function Run-Probe([string]$Name, [string]$Program, [string[]]$ProbeArgs, [string[]]$Markers, [string]$Classpath = "$installed/*") {
    $env:APPDATA = Join-Path $run ($Name + '-profile')
    [void](New-Item -ItemType Directory -Path $env:APPDATA)
    $log = Join-Path $run ($Name + '.log')
    Write-Output "START $Name"
    & $Java -Xmx4g --enable-native-access=ALL-UNNAMED -cp $Classpath (Join-Path $workspace "tools/diagnostics/$Program.java") @ProbeArgs *> $log
    $code = $LASTEXITCODE
    $text = Get-Content -LiteralPath $log -Raw
    $passed = $code -eq 0
    foreach ($marker in $Markers) { if ($text -notmatch $marker) { $passed = $false } }
    $results.Add([ordered]@{ name=$Name; passed=$passed; exitCode=$code; log=$log })
    if (-not $passed) { Get-Content -LiteralPath $log -Tail 35; throw "Failed installed probe: $Name" }
    Write-Output "PASS $Name"
}

Push-Location $workspace
try {
    if ($Mode -eq 'Functional') {
        # Test dependencies come from the completed Maven run. Production code
        # comes ONLY from the installed directory, never target/classes or m2.
        [xml]$report = Get-Content -LiteralPath 'target/surefire-reports/TEST-com.quickmaster.audio.ExportResamplingAuditTest.xml' -Raw
        $mavenClasspath = ($report.testsuite.properties.property | Where-Object name -eq 'java.class.path').value
        $junit = @($mavenClasspath -split ';' | Where-Object { $_ -match '[\\/]org[\\/](junit|opentest4j|apiguardian)[\\/]' })
        if ($junit.Count -lt 5) { throw 'JUnit runtime dependencies are missing from the completed test report' }
        $testRoot = (Get-Item -LiteralPath 'src/test/java').FullName
        # These four classes deliberately require unbound target/classes (or
        # build their own candidate from those directories). They passed in the
        # complete Maven suite, but their fail-closed expectations do not apply
        # to an authenticated installed JAR. Positive installed Leveler audio is
        # tested below, including the real UI, corpus and exact zero amount.
        $sourceOnly = @('AnalysisDynamicsCompatibilityTest.java', 'LevelerActivePublicationTest.java',
            'LevelerProcessorTest.java', 'LevelerShadowIsolationTest.java')
        $summary.sourceOnlyClassesValidatedByMaven = $sourceOnly
        $classes = @(Get-ChildItem -LiteralPath $testRoot -Recurse -Filter '*Test.java' |
            Where-Object { $_.FullName -notmatch '[\\/]processing[\\/]dynamics[\\/]leveler[\\/]' -and $_.Name -notin $sourceOnly } |
            ForEach-Object { $_.FullName.Substring($testRoot.Length+1).Replace('\','.').Replace('/','.') -replace '\.java$','' })
        $classpath = "$installed/*;" + (Join-Path $workspace 'target/test-classes') + ';' + ($junit -join ';')
        Run-Probe 'junit' 'PackagedTestAudit' (@('--jar',$jar)+$classes) @('0 tests failed','PACKAGED_TEST_CLASS') $classpath
        Run-Probe 'live-edit' 'LiveEditPublicationAudit' @() @('LIVE_EDIT_PUBLICATION_PASS')
        Run-Probe 'slots' 'SlotPublicationAudit' @() @('SLOT_PUBLICATION_PASS.*metersFollowPcm=true')
        Run-Probe 'presets' 'PresetAudit' @() @('PRESET_AUDIT failures=0')
        Run-Probe 'controls' 'ControlWiringAudit' @() @('CONTROL_AUDIT failures=0')
        Run-Probe 'eq-controls' 'EqControlAudit' @() @('EQ_CONTROL_AUDIT failures=0')
        Run-Probe 'os-controls' 'OversamplingUiAudit' @($run) @('OS_UI_PASS factor=8','OS_UI_PASS factor=2')
        Run-Probe 'waveform' 'WaveformUiProbe' @($run) @('WAVEFORM_UI_PASS','WAVEFORM_PAN_PASS','TEMPO_UI_PASS')
        Run-Probe 'source-races' 'SourceAnalysisRaceProbe' @($run,'--failure') @('SOURCE_LOAD_PASS','SOURCE_EDIT_PASS','SOURCE_FAILURE_RECOVERY_PASS','SOURCE_CLOSE_PASS')
        Run-Probe 'adversarial-chain' 'FullChainAdversarialAudit' @() @('CHAIN_BOUNDARY_SUMMARY cases=120')
        Run-Probe 'spectrum' 'SpectrumAudit' @() @('SPECTRUM_AUDIT failures=0')
        Run-Probe 'auto-eq' 'AutoEqAudit' @() @('AUTO_EQ_AUDIT failures=0')
        Run-Probe 'os-ceiling' 'OversamplingCeilingAudit' @() @('CEILING_AUDIT cases=270 violations=0')
        Run-Probe 'export-src-spectral' 'ExportSrcSpectralAudit' @() @('EXPORT_SRC_SPECTRAL failures=0')
        Run-Probe 'export-src-band' 'ExportSrcBandAudit' @() @('EXPORT_SRC_BAND cases=135 failures=0')
        Run-Probe 'export-src' 'ExportResamplingAudit' @() @('EXPORT_SRC_PASS cases=50')
        Run-Probe 'leveler-ui' 'LevelerUiAcceptance' @($byNow,(Join-Path $run 'leveler-ui.png')) @('UI_PASS actualAsyncFileLoad=true.*audiblePcmBitExact=true','UI_ZERO_PASS audiblePcmRawExact=true','UI_MACRO_PASS publishedPcm=true')
        # Macro acceptance measures a fixed temporal grid, not regions selected
        # by the Leveler. Historical cohort probes do not validate the new engine.
        Run-Probe 'by-now' 'MacroLevelerAcceptance' @($byNow,'1','.5','--assert') @('MACRO_ACCEPTANCE_PASS')
        Run-Probe 'quiet-gold' 'MacroLevelerAcceptance' @($quiet,'1','.5','--assert') @('MACRO_ACCEPTANCE_PASS')
        Run-Probe 'billie-jean' 'MacroLevelerAcceptance' @((Join-Path $PrivateAudioDirectory 'Billie Jean (80s Glam Metal).wav'),'1','.5','--assert') @('MACRO_ACCEPTANCE_PASS')
        Run-Probe 'wicked-game' 'MacroLevelerAcceptance' @((Join-Path $PrivateAudioDirectory 'Wicked Game (80s Synthwave).wav'),'1','.5','--assert') @('MACRO_ACCEPTANCE_PASS')
        Run-Probe 'by-now-half' 'MacroLevelerAcceptance' @($byNow,'.5','.5','--assert') @('MACRO_ACCEPTANCE_PASS')
        Run-Probe 'leveler-meter' 'LevelerPlaybackDiagnostic' @($byNow,'1','--assert') @('MACRO_PLAYBACK_PASS')
        Run-Probe 'leveler-noise' 'MacroLevelerAdversarial' @('--assert') @('RESIDUAL_NOISE.*gain=0.000000')
        Run-Probe 'beat-1024' 'QuietGoldBeatProbe' @($quiet,'1024') @('SOURCE_UNCHANGED=true')
        Run-Probe 'beat-257' 'QuietGoldBeatProbe' @($quiet,'257') @('SOURCE_UNCHANGED=true')
        $beatA = Get-Content -LiteralPath (Join-Path $run 'beat-1024.log') -Raw
        $beatB = Get-Content -LiteralPath (Join-Path $run 'beat-257.log') -Raw
        $hashA = [regex]::Match($beatA,'(?i)outputSha=([0-9a-f]{64})')
        $hashB = [regex]::Match($beatB,'(?i)outputSha=([0-9a-f]{64})')
        if (!$hashA.Success -or !$hashB.Success -or $hashA.Groups[1].Value -ne $hashB.Groups[1].Value) { throw 'Beat partition outputs differ or output hashes are missing' }
    } elseif ($Mode -eq 'MacroMatrix') {
        $corpus = [ordered]@{
            'by-now' = 'By Now.wav'
            'quiet-gold' = 'Quiet Gold.wav'
            'billie-jean' = 'Billie Jean (80s Glam Metal).wav'
            'wicked-game' = 'Wicked Game (80s Synthwave).wav'
        }
        foreach ($song in $corpus.GetEnumerator()) {
            foreach ($amount in @('0.25', '0.5', '0.75', '1')) {
                foreach ($speed in @('0', '0.5', '1')) {
                    $name = $song.Key + '-amount-' + $amount + '-speed-' + $speed
                    Run-Probe $name 'MacroLevelerAcceptance' @((Join-Path $PrivateAudioDirectory $song.Value), $amount, $speed, '--assert') @('MACRO_ACCEPTANCE_PASS')
                }
            }
        }
    } else {
        # Run without another suite or benchmark competing for CPU. Forced GC
        # belongs only to the separate memory probe, never the timing probes.
        Run-Probe 'memory-30' 'InteractionLatencyProbe' @($byNow,'--full-chain','--repeats=30','--heap-checkpoints') @('LATEST_FULL_AUDIO_PASS publishedAuditionPcm=true.*bitExact=true')
        Run-Probe 'latency-leveler' 'InteractionLatencyProbe' @($byNow,'--repeats=3') @('LATEST_AUDIO_PASS publishedAuditionPcm=true.*bitExactToColdReference=true')
        Run-Probe 'latency-full-chain' 'InteractionLatencyProbe' @($byNow,'--full-chain','--repeats=3') @('LATEST_FULL_AUDIO_PASS publishedAuditionPcm=true.*bitExact=true')
    }
    if ((Get-FileHash -LiteralPath $jar).Hash -ne $ExpectedJarSha256) { throw 'Installed JAR changed during acceptance' }
    if ((Get-FileHash -LiteralPath $vendor[0].FullName).Hash -ne '4f8759e3334ce1970382076cfe2015c44dd7f1378f625eeff831e70915fd4382') { throw 'Installed DSPark changed during acceptance' }
    $summary.status = 'PASSED'
} catch {
    $summary.status = 'FAILED'
    $summary.error = $_.Exception.Message
    Write-Output "FAILED: $($summary.error)"
} finally {
    $env:APPDATA = $originalAppData
    Pop-Location
    $summary.completedAt = [DateTimeOffset]::Now.ToString('o')
    $summary | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $run 'summary.json') -Encoding UTF8
    Write-Output "RESULT_PATH=$run"
}
if ($summary.status -ne 'PASSED') { exit 1 }
