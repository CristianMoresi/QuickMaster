# EQ Auto Gain — 2026-09-28

Status: completed and delivered locally, 2026-09-28 16:46 CEST. Full build,
installed functional/macro acceptance and isolated performance passed.

## Reproduction and scope

User report: By Now.wav, Bell 857 Hz, +18.8 dB, Q 0.71, linear phase,
stereo; EQ only, Peak Normalizer and oversampling off. Reproduced against the
installed 1.3.3-SNAPSHOT JAR `3a7bbe3e5bae9b8f79ed56313e6caa99acce9087b23ecdd1598386dfdc9f7f4b`:
full-track true peak +12.616215571 dBTP, 1,796,844 out-of-range samples,
zero samples equal to +/-1. The float EQ output was identical to an isolated
unclipped EQ render. The waveform marks out-of-range bins red; PCM16 playback
then clamps those samples. The screenshot's live peak is not a whole-track max.

User clarified the solution: EQ Auto Gain, using a current DSPark C++ port,
with an explicit on/off option. The initial unconditional-output-protection
proposal was abandoned before any implementation. Its failing exploratory
tests remain only in `dist/eq-output-20260928/regression-red.log`, not the suite.

## Design

- `Auto Gain` checkbox in the English EQ header, with the applied gain beside
  it. Default on, including legacy presets missing the new field; explicit off
  is preserved. Preset JSON, A/B, undo/redo, snapshot and tonal-prefix key carry
  `eqAutoGain`. Gain labels describe the last completed audible render.
- Update Java AutoGain from unmodified DSPark C++ `474b7d1` AutoGain.h: two
  independent K-weighted filter paths, 400 ms block integration, bounded dB
  matching, smoothing, volatile publication, silence and non-finite recovery.
  Flat weighting is retained. This is a level matcher, NOT a peak limiter.
- Explicit offline extension: measure the full aligned pre/post EQ signal
  with the same K-weighting and match rule. Apply one stereo-linked constant
  gain (match bounded +/-24 dB). This preserves the EQ's relative response and
  musical dynamics; it does not ride a 100 ms/400 ms gain envelope over a song.
  If that match would exceed 0 dBTP, reduce the scalar to reach -0.1 dBTP.
  Peak protection may exceed the matching range when extreme EQ requires it.
- Analyze the already-rendered EQ buffer, after compensating its latency,
  before downstream processors see it. No second EQ render or retained PCM.
  A prepared scalar also applies in streaming/oversampled processing.
- When EQ Auto Gain is active, opt into attenuation-only final output safety
  at the existing Peak Normalizer boundary. This remeasures after decimation
  and downstream effects and uses a static trim if output exceeds 0 dBTP.
  With Normalize on, its explicit target wins. With EQ Auto Gain off, safety
  is off too: no undisclosed headroom normalization, and red overload remains
  a truthful warning. Export SRC rechecks the delivery ceiling in both modes.
- The oversampled render retains the base-rate perceptual calibration (as do
  existing analyzed dynamics), with an exact final delivery-peak check. This
  does not claim bit-identical K-weighted energy between all resampling factors.
- The ordinary Bypass control still auditions the untouched source. It is not
  a promise to repair a source file that already clips. MP3 decode/DAC peaks
  beyond the finite 4x true-peak estimator remain outside a strict guarantee.

## Port verification

Unmodified C++ headers compiled with MSVC 14.51, /O2, C++20. Generator:
`tools/diagnostics/AutoGainCppVectors.cpp`; 720 block-state/sample vectors:
3 rates x 2 weightings x 2 scenarios x 60 ragged blocks. Cases include boosts,
cuts, recovery and added sub-bass. Java control dB agrees within 2e-9 and float
samples within 1e-7. Fixture SHA256:
`d60b1863e1b091c43e84b781c123dc8cfc59e8a3e317db96d4bc636c264e6a42`.
Full vendor suite: 140 tests, zero failures/errors/skips.

The current upstream main was checked again at 16:04 CEST: `be2073ac5c416feb65818a783ab3424b678a226c`.
AutoGain.h and its coefficient/math/spec dependencies have no changes since
the pinned native reference `474b7d1`. The C++ checkout itself was not replaced.

DSPark 0.2.3 SHA256:
`56df525290b09435a8d8cb5e3b56508546ad11b88f4ce2e858bdb1c2f2bb77b9`.
Zip class comparison against 0.2.2: 46 existing classes byte-identical, only
AutoGain.class changed, AutoGain$Meter and AutoGain$Weighting added. Leveler
boundaries are unchanged; historical attestations/pins were not rewritten.
Two clean vendor builds produced the same JAR SHA256.

## Acceptance checklist

- Targeted tests: uniform gain null, macro/stereo preservation, intersample
  overload, on/off/source reset, normalizer precedence, 30 rate/channel/OS
  configurations and legacy/explicit preset state.
- Actual FXML/controller with read-only By Now: auto on/off, visible gain,
  PCM vs isolated EQ times one scalar, waveform, meters, undo/redo, cached
  A/B, oversampling and normalizer. No human-listening claim.
- Preflight passed: By Now +18.8 dB EQ at 1x ends at -0.100000186 dBTP,
  -12.716215531 dB scalar and zero samples over scale. Off is raw-PCM exact;
  undo/redo and A/B return the right setting/PCM. At 4x, output -0.087854902
  dBTP; moving the scalar algebraically past float decimation leaves a maximum
  residual 1.17986929e-6 and RMS 6.31601285e-9 (not clipping). Normalize -1 at 4x
  measures -0.999999667 dBTP. These are not sample-identical comparisons across
  oversampling factors. WAV float round-trip is exact; 24-bit max PCM error
  1.77416950e-7, no overload. 44.1 kHz delivery SRC safety remains one scalar.
- 640 synthetic routing/phase/dynamic/type combinations passed with zero scalar
  error and calibrated 257-frame streaming matching offline rendering. Source
  buffers are unchanged. Includes mono/stereo, M/S, L/R, all eight band types.
- Complete clean package passed: 865 application tests in 129 suites, no
  failures/errors/skips, including 106 musical variants. Official ITU/EBU
  attestation completed with 94 readings and reopened-JAR verification.
- Installed acceptance passed: 315 JUnit tests, 48 functional probes and 48
  macro Leveler combinations. The 640-case EQ matrix and the actual FXML/UI
  reproduction above also passed against the installed JAR, not target/classes.
- Read-only By Now, Quiet Gold, Billie Jean and Wicked Game each passed Bell
  and High Shelf +18.8 dB tests. All outputs were finite and within 0 dBTP;
  every sample equalled isolated raw EQ times one scalar. Source file SHA256s
  were checked before and after. The toggle's off state is deliberately raw,
  including the original overload; neither the waveform nor export conceals it.

## Local delivery and evidence

Portable 1.3.3-SNAPSHOT built with Eclipse Temurin 25.0.4.7 and jpackage
1.3.3; copied to `C:/Program Files/QuickMaster` at 16:29 CEST. This is an
application image, not an installer. All 201 files match the generated image.
Both elevated verification and a separate unprivileged EXE launch produced a
clean startup log. Only owned test processes were closed.

Application JAR SHA256:
`e165b6c4fcaf35c69aaa95e4d6fecd9d8e05316850cfa76fac01a4678fc4c082`.
Previous portable preserved at:
`C:/Program Files/QuickMaster-backup-20260928-162909`.

- Build, baseline, deployment and preflight: `dist/eq-output-20260928/`.
- Installed functional: `dist/installed-audit-functional-20260928-163010-154e4382/`.
- Installed macro matrix: `dist/installed-audit-macromatrix-20260928-163020-14189a3d/`.
- Installed screenshot: functional directory, `eq-auto-gain/eq-after.png`.
  Visually inspected: English checkbox/gain fit the EQ header; processed
  waveform retains varying peaks, without the old red full-scale flattening.
- Isolated performance: `dist/installed-audit-performance-20260928-163930-37a7f102/`.
  All four probes passed, with no competing acceptance or benchmark process.
  Median time to audible PCM over three repeated edits: Leveler 1.415358 s,
  full chain 5.972084 s. Uncached B median 5.883734 s; identical first B
  9.802 ms, cached A 4.729–6.614 ms (no rendering). These timings cover this
  machine/track/preset; they are not a claim that arbitrary EQ edits are instant.
  Fresh-controller references are raw-bit exact. Thirty full-chain edits hold
  1329.126–1329.147 MiB after GC; final burst checkpoint 1329.162 MiB. GC is
  only forced by the separate memory probe, not the isolated timing probes or app.
- Summary JSON copies: `dist/eq-output-20260928/installed-{functional,macro,performance}-summary.json`.
  Final installed JAR/dependency hashes rechecked after acceptance. No owned
  test Java/QuickMaster process remains. No human-listening or universal DSP
  perfection claim is made by these mechanical checks.

Working files: `dist/eq-output-20260928/`. No public release, tag, commit,
push or original audio modification is part of this request.
