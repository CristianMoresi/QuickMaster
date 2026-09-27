# Packaged-application acceptance probes

These source-file Java programs run against the actual packaged JARs, not
`target/classes`. They complement (do not replace) the complete Maven suite.
They do not open an audio output device. Real source WAVs are read-only; injected
level errors exist only in memory. Generated fixtures, logs and screenshots go
to the diagnostic output directory. Never distribute the private or official audio.

Example PowerShell setup from the repository root:

```powershell
$java = 'C:/Program Files/Eclipse Adoptium/jdk-25.0.4.7-hotspot/bin/java.exe'
$classpath = 'C:/Program Files/QuickMaster/app/*'
$run = Join-Path (Get-Location) ('dist/acceptance-' + [guid]::NewGuid())
New-Item -ItemType Directory -Path $run | Out-Null
$originalAppData = $env:APPDATA
$env:APPDATA = Join-Path $run 'config'
New-Item -ItemType Directory -Path $env:APPDATA | Out-Null
try {
    & $java -Xmx4g -cp $classpath tools/diagnostics/SourceAnalysisRaceProbe.java $run --failure
    if ($LASTEXITCODE -ne 0) { throw 'Source/load regression failed' }
    & $java -Xmx4g -cp $classpath tools/diagnostics/WaveformUiProbe.java $run
    if ($LASTEXITCODE -ne 0) { throw 'Waveform/tempo UI regression failed' }
} finally {
    $env:APPDATA = $originalAppData
}
```

`SourceAnalysisRaceProbe --failure` deliberately opens a nonexistent generated
path. Its expected exception is not a clean-startup test; require the explicit
`SOURCE_FAILURE_RECOVERY_PASS` and `SOURCE_CLOSE_PASS` markers and zero exit.
`WaveformUiProbe` sends JavaFX events and uses a transport sentinel that throws
if a wheel event attempts to seek, play, pause or stop. Inspect its PNGs too.

Additional probes, with the same Java/classpath/isolated APPDATA setup:

| Program | Arguments after the `.java` path | Acceptance |
|---|---|---|
| `LevelerCorpusAcceptance.java` | `<private.wav> [--positive]` | Original correction (required with `--positive`), known +4 dB error reduced, protected samples exact, 0% identity, source unchanged |
| `LevelerUiAcceptance.java` | `<private.wav> <screenshot.png>` | Real asynchronous UI load, current plan adoption, actual changed audio and 0% identity |
| `QuietGoldBeatProbe.java` | `<Quiet Gold.wav> <blockFrames>` | Hash-bound regression fixture; actual applied reduction ≤1 dB within float rounding, stereo link and output hash |
| `InteractionLatencyProbe.java` | `<private.wav> [--full-chain] [--repeats=20] [--heap-checkpoints]` | Real control listeners, latest result bitexact to a cold reference, bounded output work; separate audio-ready and meters-ready latency |
| `PipelineLatencyProbe.java` | `<private.wav> --check-fades` | Four real chain configurations, per-stage time, finite PCM hash, source unchanged and fade snapshot preservation |
| `PackagedDspCompatibilityProbe.java` | `<old-app-directory> <new-app-directory>` | Independent class loaders; fixed numeric limits across rates, channels and phase modes; intentionally corrected broadband excluded |

Run latency measurements without another suite/benchmark competing for CPU.
The optional heap checkpoints force GC in the **harness only**; do not count
them as production behavior, RSS, or latency measurements. A JavaFX probe is
not human listening and does not validate native macOS/Linux interaction.

Delivery still requires the real installed EXE's clean startup and exact JAR
hash, as described in [VALIDATION](../../docs/VALIDATION.md). A probe's `PASS`
cannot replace that gate or justify publishing a release.
