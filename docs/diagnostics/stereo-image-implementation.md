# Stereo Image implementation — 2026-09-29

**Superseded generation acceptance:** the user reported an imperceptible effect
and excessive waiting. The original changed-sample acceptance was insufficient.
Current investigation/correction and final delivery status are in
`stereo-generation-correction.md`. The measurements below describe the first
version, not the corrected generator's defaults or harmonic topology.

Priority: sound quality, robust maintainable code, efficiency. Work in the existing
integration checkout; preserve unrelated edits. No release/commit requested.

## Delivery checklist

- [x] Immutable settings and measured energy profiles; preset migration.
- [x] Moving differential EQ (eight bands/channel), delta-only harmonics, aligned
      linear-phase bass exclusion. Verify mono sum and spectral rejection.
- [x] Offline Side Leveler and independent Side Guard; finite, gated plans.
- [x] Separate English UI; undo, A/B, snapshots, order, batch, processed waveform.
- [x] Interactive preview with cancellation, context and honest provisional state.
- [x] DSP/integration tests, real-track and packaged UI acceptance, performance.
- [x] Full application and DSPark suites, package, Windows image, portable deploy,
      installed JAR hash, installed executable and clean startup log.

No claim of human listening or universally optimal genre ratios. Numeric tests
and perceptual listening are different evidence. Research data are 50 public
streaming deliveries (five per family), not lossless original masters.

## DSP choices under test

Use trapezoidal-integrator state-variable bands with parameter interpolation, not
arbitrary interpolation of direct-form IIR coefficients. Source: Andrew Simper,
https://cytomic.com/files/dsp/SvfLinearTrapOptimised2.pdf .
Band trajectories use absolute audio time and disjoint logarithmic corridors.
Use DSPark FFT for partitioned convolution and DSPark oversampling/saturation for
optional delta harmonics. Long filters must not impose a direct convolution per
sample or modify the dry branch beyond exactly compensated delay.

Implementation and local delivery completed; results below.

## Implemented decisions and explicit boundaries

- Eight broad TPT-SVF parallel bell deltas/channel; alternating boost/cut polarities
  within each channel, disjoint logarithmic center corridors, continuous slow
  frequency and gain trajectories. Control interpolation is in physical SVF
  parameters at 64-frame intervals, not direct-form denominator coefficients.
- Kaiser FIR: stop-band <=150 Hz, pass-band >=200 Hz, measured rejection >90 dB,
  pass-band ripple <0.05 dB. Partitioned DSPark FFT convolution; full dry-branch
  alignment, with total latency divisible by 16 for the global oversampling modes.
- Harmonics use different DSPark Tube/Tape saturation only on the two deltas.
  An initial 4x version failed the fixed -80 dBFS 13 kHz foldback threshold for
  the 7 kHz/0.95-peak stress tone (-78.532 dBFS). 8x improves it to -84.332 dBFS.
  Factors are 8/4/2/1 for processing rates <=48/96/192/>192 kHz, respectively,
  using MAXIMUM half-band quality. No claim that every possible signal is alias-free.
- Impulse tests preserve original Mid and attack. New pre-ringing peak is
  -69.277 dBFS without harmonics, -70.755 with harmonics, for a 0.9 impulse.
  A separate transient-protection layer is therefore not activated by default.
  These measurements are not a substitute for human listening.
- Leveler uses 400 ms power context, a one-second-radius triangular log-gain
  smoother and bounded correction. Guard uses overlapping 40 ms full-band energy
  windows on a 10 ms grid, conservatively bounded gain and anticipatory release.
  Its ceiling is an energy-window contract, NOT sample-by-sample or per-frequency.
  No unsupported four-band genre thresholds or hard correlation thresholds are
  invented from the corpus. Frequency-distribution diagnostics and band-specific
  regulation are not exposed as if they had been implemented.
- Mono files do not change channel count. Two-channel dual mono is supported.
  Generation cannot guarantee monotonically increasing Side energy for every
  already-stereo input: the original/new Side cross-term can be negative.
- Auto uses post-generation track balance; selected genre targets are fixed to
  their measured percentages. Presets persist all controls; reference audio is
  never stored, only its measured target. Reference import runs on a worker.
- The final headroom guard may apply one output trim; it does not alter the
  relationship or add a hidden compressor. The existing output Gain shows it.
- Dedicated Mono/Delta listening buttons are not added to this first UI: they
  require a separate monitoring bus and must not accidentally alter export.
  Mono/delta preservation is checked numerically; original/master bypass remains.

## Validation results

19 Stereo Image tests pass, including DSP invariants, invalid states,
legacy presets, seeks at 4x, sample-aligned 2x–16x dry paths, rendering partition
invariance, actual meter energy and output headroom without clipping.

Initial real-controller acceptance on By Now: independent cold PCM equals the
published master bit for bit; cached A/B reuse, Undo and provisional preview
pass. Scene snapshots at 1360x900 and compact size have been inspected. Reference
import also passes against an actual loaded WAV. Final installed acceptance
after the complete clean build also passed, as recorded below.

### Real tracks and interaction

Source audio was read only, without playback or exporting private audio:
By Now, Quiet Gold, Billie Jean (80s Glam Metal), Wicked Game (80s Synthwave).
Each complete track passed five modes: generation, leveling, guard, combined,
combined with harmonics. All changed samples as expected; worst Mid numerical
error was <=5.96046448e-8. Logs: `dist/stereo-image-20260929/tracks.log`.

Stereo-only complete analysis/render costs were 2.4–3.4 s without harmonics and
21.5–30.7 s with harmonics for these 3–5 minute tracks. These are not interaction
latencies: provisional preview renders only the needed context on its worker.
Serial preview benchmark, 12 measured half-second windows after two warm-ups:

| Mode | Median | p95 |
| --- | ---: | ---: |
| 1x, no harmonics | 24.525 ms | 26.522 ms |
| 1x, harmonics | 195.797 ms | 198.292 ms |
| 4x, no harmonics | 83.725 ms | 86.090 ms |
| 4x, harmonics | 309.734 ms | 312.745 ms |

These measure window computation, not physical audio-device latency. Actual
controller preview publication measured 30.24 ms in the no-harmonics case.
Evidence: `performance.log`, `ui-final.log`, and `ui-final/` scene snapshots in
the same diagnostics directory. UI audit tests final PCM against an independent
cold render, not merely a Ready label.

Six additional real-song routing cases passed: Stereo Image before EQ, after
Peak Comp, and after the limiter, each at 1x/4x, with active EQ, Peak Comp, both
clips, multiband and broadband limiters. Final true peak stays at the requested
-1 dBTP within 0.001 dB; samples remain finite and source/length unchanged.
Source-build evidence: `routing-source.log`; installed replay also passed below.

The early full-suite run was cancelled after further DSP refinements and is NOT
counted. The final `clean package` completed successfully: **900 tests across 135
classes, zero failures/errors/skips**. DSPark: **140 tests, zero failures/errors/
skips**, unchanged audited binary. The musical matrix contains 106 passing cases;
official loudness evidence reports 94 readings, ITU/EBU PASSED, scoped to
`QM-OFFICIAL-LOUDNESS-FILE-V1` (not full EBU Mode, live or LRA certification).

### Packaged/installed delivery

- JDK: Eclipse Temurin 25.0.4.7; Windows portable, not an installer.
- Installed: `C:/Program Files/QuickMaster`.
- JAR SHA-256: `618fdd1c11d8a6a2348918279c7e2591df55437cfaa7ef4f0d461d463b585e78`.
- All **201 image files** match the installed copy, checked again after startup.
- Previous portable preserved at
  `C:/Program Files/QuickMaster-backup-20260929-011749`.
- Standard Windows elevation used, without changing ACLs or execution policy.
- Installed EXE: clean startup at 01:17:51/52 local time, controller initialized;
  verification process closed cleanly. `deployment-result.json` reports
  `DEPLOYED_AND_VERIFIED`, copied under `dist/stereo-image-20260929/`.
- **350 tests** rerun successfully with the installed JAR/dependencies, never
  `target/classes`, including all 19 Stereo Image tests. The source-only historic
  acceptance classes are covered by the complete Maven run instead.
- Installed real-controller audit on complete By Now: independent final PCM
  bit-exact, A/B cache reused, Undo, measured reference import and **30 rapid
  parameter edits** with correct final publication. Preview publication 30.34 ms
  in this test (no audio device). Normal/compact scene snapshots retained.
  `installed-ui.log`, `installed-ui/`, `installed-junit.log`.
- Installed four-track/five-mode replay: **20/20 passed**. Source-file SHA-256
  identities unchanged, finite changed samples and mono sum preservation in all
  cases. `installed-tracks.log`. This audit measures the isolated Stereo Image
  stage, so its sample peaks may exceed 0 dBFS before final output protection.
- Installed six routing cases: **6/6 passed** with downstream final protection;
  measured output <= -0.999 dBTP for a -1 dBTP target. `installed-routing.log`.
- All supplemental Java processes exited successfully. Acceptance completed at
  01:22 local time on 2026-09-29. No human/perceptual listening is claimed.

No release, commit or push made. Private audio untouched and not redistributed.
