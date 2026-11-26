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
| `AutoEqTrackAudit.java` | `<private.wav> [amount=] [cutBoost=]` | Whole-song Auto EQ: render time, integrated loudness and true peak before and after, the curve in play along the track, alignment with the source, range respected, nothing raised at Cut and nothing lowered at Boost, joins checked and stretches carried on, the parallel render against one stream, source unchanged |
| `AutoEqUiAudit.java` | `<private.wav> <output-dir>` | The two real Auto EQ controls (Amount and Cut/Boost); Amount at zero publishes the source and draws nothing; the drawn correction grows with Amount; Cut raises and Boost lowers nothing; published PCM bit-exact to an independent render after each edit; provisional preview published at once; typed values; presets, including one of the slope-matching Auto EQ; undo; screenshots |
| `SaturationTrackAudit.java` | `<private.wav> [db=1,3,6] [types=TAPE,TUBE_PREAMP]` | Whole-song saturation through every analog model and every tape alignment: solve and render time, renders, solved drive, the meter never a gain and its deepest reading exactly the knob's (0.001 dB, against the model's linear response), the song's peak against the linear response and the input, loudness kept (0.06 dB; 0.1 dB when the sine knee completes the reduction), a second render identical to the bit, source unchanged |
| `ClipsUiAudit.java` | `<private.wav> <output-dir>` | The real Clip controls: the Saturation menu and its Soft clip and Tape machine submenus, a legacy preset shown as its curve, published PCM bit-exact to a cold render for a curve and for a tape alignment, visible meters equal to the audible plan, A/B caches, source unchanged |

`ReadmeScreenshot.java` (`<private.wav> <output.png> [scale] [playhead-seconds]`) is not a probe:
it redraws `docs/gui.png`, the picture of the interface the README shows, off
screen from the real FXML at the design size times the scale (2 when left out),
with every module on, a gentle tilt band, the Auto EQ at 50 % and the playhead
where you put it (a third of the way in by default; `docs/gui.png` is By Now at
160 s, where the Auto EQ moves most). Run it against the
packaged JARs after a change to the interface, and look at the result before
replacing the picture.

The compatibility probe above records the original DSPark migration, not blanket
equivalence for the later product audit: Auto EQ, multiband tail handling and
export SRC have intentional, independently tested corrections. Auto EQ has
since become the DSPark tonal balance: against a build that predates it the
probe is expected to differ there, by design.

Product audit probes (same installed classpath, no audio device):

| Program | Arguments | Acceptance |
|---|---|---|
| `LiveEditPublicationAudit.java` | none | Captured output keeps the approved normalized PCM while new EQ controls await analysis |
| `SlotPublicationAudit.java` | none | A/B cached PCM and measured peaks move together; cancel restores previous settings |
| `PresetAudit.java`, `ControlWiringAudit.java`, `EqControlAudit.java` | none | FXML preset validation/roundtrip, knob refresh/undo, band creation and supported slopes |
| `OversamplingUiAudit.java` | `<output-dir>` | Real OS control listener invalidates and republishes audio at the selected ceiling |
| `FullChainAdversarialAudit.java` | none | 120 short generated chain/rate/OS/order cases, independent true-peak ceiling (Leveler is tested separately) |
| `ExportSrcSpectralAudit.java`, `ExportSrcBandAudit.java`, `ExportResamplingAudit.java` | none | Actual export SRC: passband/alias/residual, frame extent, stereo polarity, final ceiling and unchanged source |
| `SpectrumAudit.java`, `AutoEqAudit.java`, `OversamplingCeilingAudit.java` | none | Rate boundaries, stereo polarity invariance and generated DSP counterexamples |
| `IoAllocationAudit.java` | `<output-dir>` | Generated WAV decoding allocation comparison; no private source modifications |
| `PackagedTestAudit.java` | `--jar <installed-jar> <JUnit-class>...` | Selected JUnit regressions with runtime origin checked; also supply test classes and JUnit dependencies, never `target/classes` |

The Leveler/performance probes must use an authenticated packaged JAR. Running
plain `target/classes` lacks the embedded evidence and correctly fails closed;
such a run cannot establish active-Leveler performance. Both latency modes
compare the **published audition PCM** directly with a cold reference (a fresh
controller for the full chain). Do not require `--positive` on the original Quiet Gold fixture: its
above-zero true peak triggers the documented safety abstention; its in-memory
headroom/+4 dB trial still must positively reduce the known regional error.

Run latency measurements without another suite/benchmark competing for CPU.
The optional heap checkpoints force GC in the **harness only**; do not count
them as production behavior, RSS, or latency measurements. A JavaFX probe is
not human listening and does not validate native macOS/Linux interaction.

Delivery still requires the real installed EXE's clean startup and exact JAR
hash, as described in [VALIDATION](../../docs/VALIDATION.md). A probe's `PASS`
cannot replace that gate or justify publishing a release.

For the 2026-09-27 product audit, `tools/Verify-InstalledAudit.ps1` runs the
installed checks with explicit JAR identity and the audited DSPark 0.2.1 pin:

```powershell
.\tools\Verify-InstalledAudit.ps1 -ExpectedJarSha256 '<verified-build-sha256>' `
  -PrivateAudioDirectory '<read-only-private-corpus-directory>' -Mode Functional
.\tools\Verify-InstalledAudit.ps1 -ExpectedJarSha256 '<verified-build-sha256>' `
  -PrivateAudioDirectory '<read-only-private-corpus-directory>' -Mode Performance
```

Run Performance only after other suites have stopped. Functional includes the
non-Leveler-contract JUnit classes against installed production bytes (the full
Maven suite still covers all contracts/musical variants), real FXML probes, four
private tracks and Beat block-partition identity. Performance separates the
30-edit forced-GC retention sweep from two unforced timing probes. Each run
creates a unique `dist/installed-audit-*` directory with isolated configuration,
individual logs and a pass/fail summary. It never deploys or modifies private WAVs.

Four additional source-only classes are listed explicitly in the Functional
summary: their unbound-source or package-builder preconditions do not hold for
an already authenticated installed JAR. They remain mandatory in the complete
Maven suite. Do not weaken their assertions to run them in the wrong context.
