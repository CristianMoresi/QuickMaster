# Dynamics and clipping audit — 2026-09-28

Status: **completed and locally delivered**, 2026-09-28 15:24 CEST.
Source/build, deployment and installed acceptance passed. Local candidate
1.3.3-SNAPSHOT, not a published release. No unresolved reproduced defect in
this audit's scope; sonic/algorithmic limits are explicitly recorded below.

## Implemented corrections and current evidence

### Peak Comp and the shared lookahead

Reproduced: a first-frame peak with a -6 dB target escaped at 0.972509
instead of 0.501187. The original forward-minimum also retained stale minima
near EOF. Replaced the Peak envelope with the already-tested offline
minimum-hold/double-box construction used by the limiter. Full 2 ms attack,
source alignment, stereo-linked gain, no post-hoc clipping. The release remains
60–250 ms, now checked numerically at its 63.2% recovery point. Removed an
absolute input floor that broke gain invariance. A live target tightening
cannot exceed the permitted reduction while awaiting the new render.

Independent tests cover first/middle/final impulses at 44.1/48/96 kHz,
1e-8..10 input scales, stereo ratios, release and a brute-force oracle for
every forward-window boundary. Beat's pre-existing raw-bit golden and all
eight dedicated Beat tests still pass; its tempo detector was not replaced.

`PeakToneAudit` also measures steady-state THD+N by removing the fitted carrier,
not by calling a listening score a measurement. At 48 kHz and a -3 dB request
(internally capped by the measured 1.62–1.72 dB peak/body headroom), residuals
are 1.5496% at 20 Hz, 0.5102% at 50 Hz, 0.1077% at 100 Hz and approximately
0.000003% at 1 kHz. Installed 1.3.2 baseline: 1.5392%, 0.4984%, 0.1070% and
approximately 0.000003%; 96 kHz is consistent. The corrected envelope contains
the peak more accurately but does **not** eliminate low-frequency gain
modulation at its 60 ms minimum release. This is an explicit sonic tradeoff,
not a claim of transparent or zero-distortion compression. No hidden filter
or additional release hold was introduced to improve that isolated score.

### Soft-Clip and Hard-Clip

Confirmed hidden processing: Hard-Clip's unexposed slew and first-order ADAA
changed the first sample and high-frequency content even below its intended
ceiling. The SOFT solver's fixed upper bracket applied 0.6843586 dB reduction
when the control requested 0.001 dB. Soft-Clip had unexposed channel drift and
a fixed-per-sample DC filter. Its Java asymmetric curves were not the C++
physical saturation models and were discontinuous near zero reduction.

The first symmetric-control prototype retaining those asymmetric curves was
rejected: a nominal 0.001 dB setting still produced approximately +0.00875 DC
on a symmetric test tone. It was never deployed. The application now uses
the existing canonical DSPark **clipping** curves. Saturation's legacy Java
streaming models remain available in the library but are not used by these
QuickMaster controls; no physical tube/tape/transformer parity is claimed.

Preset migration (intentional audible change; existing IDs still deserialize):

| Stored ID | English Soft-Clip label | DSPark transfer |
|---|---|---|
| TUBE | Analog | Unit-slope sine, bounded at the ceiling |
| TAPE | Soft (tanh) | Symmetric tanh |
| TRANSFORMER | Golden knee | Linear body with symmetric golden-ratio knee |

Hard-Clip now exposes its previously hidden persisted HARD/SOFT curve in the
UI. Both stages calibrate peak reduction in source-peak units with a bracket
that expands near zero. No hidden slew, drift, DC blocker or sample averaging.
Only Hard and Golden knee have an exactly linear below-knee region; sine/tanh
gently reshape the body too. Zero is exact bypass. Clipping itself is not a
linear stereo-linked compressor: each channel uses the same nonlinear curve.
Dual mono remains identical and there is no manufactured channel drift.

Antialiasing belongs to the explicit pipeline oversampling stage, not a second
unreported filter inside a clipper. **1x nonlinear clipping is not alias-free.**
The 7 kHz/48 kHz, 6 dB test measured suppression of the folded fifth harmonic
at 13 kHz with 8x versus 1x: Hard 43.68 dB, tanh 119.85 dB, Analog 71.10 dB,
Golden knee 54.60 dB. This is one specified spectral test, not a universal
aliasing bound. Oversampling reconstruction can change the stage's actual
peak reduction relative to base-rate calibration; the meter must show that,
not conceal it by clamping to the knob.

Actual signed input/output peak measurements populate each 1024-source-frame
meter interval during the render, including high-rate processing and negative
preroll. Meters follow the approved audible snapshot and persist through the
final base-rate preparation. Tests cover five curve configurations, six
amounts, three scales and two polarities (180 combinations), raw-exact ragged
blocks/seeks, rates and 1x/2x/4x/8x/16x metering clocks. Symmetric DC, near-zero
continuity and actual PCM reductions are checked independently.

Native transfer reference: `tools/diagnostics/ClipCppVectors.cpp` compiled
against unmodified DSPark **474b7d1** headers with MSVC. Java matches 6,416
native samples. Maximum error is 3.73270871884e-9 ceiling units in Analog,
within C++'s independently documented fastSin approximation error; other
curves retain the 3e-14 tolerance. Fixture SHA-256:
`8bd8e0e15a329b28caa72fc11708cf26aa4a56831f3ea198a22eaee9ea04066d`.

### Punch: event detection is not the tempo map

The original global-strength threshold suppressed quiet attacks when a later
section was loud. A first locally normalized spectral-flux prototype was
rejected after it falsely classified 20 of 24 stationary-carrier cases as
attacks (the previous algorithm also failed four). No prototype was deployed.

Ported the **offline SuperFlux path of DSPark C++ 474b7d1**: automatic
time-span FFT, periodic Hann, 200 Hz hop, quarter-tone triangular log bands,
three-band maximum reference and symmetric local peak picker. Pooled stereo
spectral power extends the mono C++ front end without antiphase cancellation.
The original strong-pulse tempo map is unchanged. Both analyses are cached
once per source, not recalculated per knob edit. Punch delta is explicitly
0.01 (versus C++'s conservative default 0.03) to retain the quiet transient
corpus; steady tones and FM/AM false-positive checks use that same 0.01.

Spectral event times are not sample-accurate waveform starts. Punch refines
each accepted event using the strongest local rise in stereo-pooled 1 ms
energy within 25 ms, without inventing extra events. A 5 ms cosine rise ends
before the refined attack; the hold also contains the original detected time,
then a 45 ms cosine release. Overlapping envelopes combine by maximum,
not sum. The same bounded gain applies to both channels.

Native generator `OnsetCppVectors.cpp`: **5,394 novelty frames** match C++
within 8.21705190310e-7 absolute error (2e-6 float-FFT allowance); all **18
event positions match exactly** at 44.1/48/96 kHz. The resource fixture hash is
`8aed8df8058ea155ce2d37779b5e5a85c8275ac87880403f5f3ecf3f6445611c`.
Zero interior false events on 24 steady carriers (20 Hz..10 kHz) and the
three-rate FM/AM corpus. Mono, dual mono and antiphase have identical novelty
and events. Cancellation, empty input and nonfinite final-hop samples tested.

The waveform-level `PunchAttackAudit` independently annotates **270 bursts**
at three rates and five hop phases, alternating quiet/loud sections. Every
burst receives the requested 6 dB; worst energy error 0.000000278 dB. These
annotations are not generated from the detector's own output. Detection
remains heuristic: dense mixes, very soft bass and events within the initial
FFT warm-up or final incomplete hop are not guaranteed. No claim of perfect
instrument separation, streaming parity or whitening/ComplexDomain port.
Method reference: Böck and Widmer,
[SuperFlux, DAFx-2013](https://www.dafx.de/paper-archive/2013/papers/09.dafx2013_submission_12.pdf).

### Dependency identity and guard successor

DSPark 0.2.2 full suite: **137 tests, zero failures/errors/skips**. Two clean
builds produce the same JAR SHA-256:
`fbcffe1caa39d72f0780ce555bc2bec667f60da388ddcb182bd58ee1d281592f`.
Class-byte comparison with 0.2.1: **40 existing classes identical**, only
Clipper/Saturation and their enum classes changed; three SuperFlux classes
added. None is a Leveler boundary class. The active guard's explicitly named
successor dependency pin is updated on this evidence, not learned from a
candidate. Historical artifact pins remain untouched. Old library artifacts
stay in `libs` for historical reproducibility; application packaging must
contain **only dspark-0.2.2.jar**, never an ambiguous wildcard of both versions.

### Current application checks (not yet installed acceptance)

By Now read-only source hash
`dcfb61d5d419bf6044bb0dd42fd7639659f33564c4b7ef9219cbf66798bf8e91`
unchanged. At 3 dB settings:

| Stage | Peak change | RMS change | Changed samples |
|---|---:|---:|---:|
| Peak Comp | -2.9999994 dB | -1.0525734 dB | 18,223,614 |
| Punch | +2.9775886 dB | +0.9443780 dB | 6,073,140 |
| Analog | -3.0000001 dB | -0.5465627 dB | 26,764,732 |
| Tanh | -3.0000001 dB | -0.6714165 dB | 26,775,054 |
| Golden knee | -3.0000001 dB | -0.1614689 dB | 798,978 |
| Hard | -3.0000001 dB | -0.0571626 dB | 141,251 |

Punch need not raise the file's absolute maximum by the full knob amount:
that sample need not belong to a detected attack. The bounded per-attack
gain and the actual change in output are checked separately. Dynamics stereo
gain error below 1.7e-7; all clip meters match independent PCM measurements.

The same seven-stage standalone measurements also pass on Quiet Gold, Billie
Jean (80s Glam Metal) and Wicked Game (80s Synthwave), with all source hashes
unchanged. `DynamicsClipsSerialAudit` adds 36 cumulative configurations:
Peak -> Beat -> Punch -> Soft -> Hard, three rates, mono/stereo and every
curve pairing. Every stage is exercised; independently analyzed sequential
ragged-block renders are raw-bit identical to the pipeline. Each clip's
measured reduction is relative to its actual preceding output, not the source.
The real JavaFX probe verifies English curve controls, legacy preset slots,
rapid edits, exact cold-reference PCM, actual meters and cached A/B identity;
the inspected screenshot shows measured -4.0/-1.0 dB at the selected peak.

## Build and local delivery

Complete official-corpus `clean package` passed in 29:11 on 2026-09-28 at
15:11:58 CEST: **858 tests in 128 suites**, no failures/errors/skips; **106
musical variants**, **94 official loudness readings**, reopened JAR attestation
PASSED. The latter is the specified file-measurement profile, not a blanket
EBU Mode/live/LRA/true-peak certification.

Application SHA-256:
`3a7bbe3e5bae9b8f79ed56313e6caa99acce9087b23ecdd1598386dfdc9f7f4b`.
Windows Java 25 image generated under
`dist/dynamics-clips-20260928/final-image/QuickMaster` and copied to
`C:/Program Files/QuickMaster`. All **201 files** compared equal after delivery.
No installer. Backup: `C:/Program Files/QuickMaster-backup-20260928-151239`.
Deployment report `DEPLOYED_AND_VERIFIED` at 15:12:47; installed EXE startup
also verified without elevation at 15:13:25–26, no application errors.
Installed DSPark also passes the 6,416 native clipping reference vectors.

Installed acceptance completed so far:

- **42 functional probes PASSED**, including **308 JUnit tests** executed
  against the installed JAR (not `target/classes`). Actual UI/approved PCM,
  visible GR, presets/A-B, four real-track dynamics/clip and limiter cascades,
  24 stationary carriers, 270 annotated attacks, 36 cumulative chains,
  source-race recovery, waveform, EQ, export and oversampling included.
- **48 macro configurations PASSED**: four real songs, four Leveling amounts
  and three speeds. Source audio remains unchanged.
- Full-chain boundary audit: **120 cases**, maximum -0.999999455 dBTP for a
  -1 dBTP output target (floating-point tolerance). The separate OS ceiling
  audit passes 270 configurations; export rate/band/spectral probes pass.
- Beat Comp on Quiet Gold: same raw output hash for blocks of 1024 and 257,
  zero frames exceeding the allowed -1 dB reduction tolerance, stereo-linked,
  source unchanged. Detected tempo remains 82.25 BPM/confidence 0.65; this
  synthetic/consistency audit is not an independent human tempo annotation.

Logs: `dist/installed-audit-functional-20260928-151315-99c60677` and
`dist/installed-audit-macromatrix-20260928-151315-0cdbbc27` (both summaries
PASSED). The installed clipping screenshot was visually inspected: curve
labels, -4.0/-1.0 dB meters, processed B waveform and position 2:20.928 agree.

### Installed performance — passed

All **four performance probes PASSED**, run alone after the functional/macro
suites, on By Now, using installed production classes and full-quality PCM.

| Scenario | Time until approved audio |
|---|---:|
| Leveler-only edit, median of three repeat edits | 1.368910 s |
| Full-chain edit, median of three repeat edits | 6.285010 s |
| Full-chain initial source load | 10.764948 s |
| First B with identical settings | 10.761 ms, no render |
| Cached A returns | 4.251–5.949 ms, no render |
| Uncached B, median of three variants | 5.776074 s |

Cold fresh-controller references match the published PCM with **zero raw-bit
differences**. Full-chain retained heap is stable near **1,329 MiB** over 30
repeat edits: 1328.991–1329.013 MiB (the memory probe intentionally forces GC;
timing probes do not).
Leveler-only retained heap: 496.518 MiB. No lower-quality preview substituted.
These are scenario/machine-specific measurements, not a promise that every
edit or oversampling setting takes the same time. New curves intentionally
change the DSP, so these are not audio-identical old-versus-new speed claims.

Performance log directory:
`dist/installed-audit-performance-20260928-151806-50c5f40c`, summary PASSED.
All three installed acceptance summaries and deployment result are also
copied under `dist/dynamics-clips-20260928`. Installed application/dependency
hashes were checked again after acceptance and still match the validated build.

No remaining implementation/delivery step for this request. No commit, push,
new release, or deletion of the existing public release was performed. The
dirty primary checkout is preserved; changes are in QuickMaster-Integration.
Measurements and automated/UI inspection are evidence, **not human listening
or a claim of perfect event detection, transparent clipping or zero aliasing**.

Rejected/interrupted runs are retained under `dist/dynamics-clips-20260928`:
initial red tests; old asymmetric-DC and stationary-Punch probes; a first
broad-suite invocation missing official corpus properties; a correctly
configured suite intentionally stopped when the Punch false positives were
found. None of those interrupted runs is counted as a successful build.

## Scope and baseline

Audit Peak Comp, Punch, Soft-Clip and Hard-Clip, and regress Beat Comp and
their cumulative routing with Leveler/limiters. The installed baseline is
QuickMaster 1.3.2 (application SHA-256
`ca98526f824cb76fd6e984e0fcde766bc6f120592a42646a6f34d9b7fac64ad4`).
Work is isolated in QuickMaster-Integration; the older dirty primary checkout
is not overwritten. No orchestration skill. No release/publication requested
for this audit.

DSPark C++ was fetched read-only: origin/main is
`474b7d1d2d9034b308505adba280f825e251b432`. Clipper.h and Saturation.h have
not changed since the previously examined `9330f1c`; their Java counterparts
are NOT interchangeable ports. Java Saturation implements three rational-knee
voicings, whereas C++ also provides actual filtered tape/transformer models.
Reusing a model requires checking its transfer, state, gain and timing contracts;
matching class/algorithm names is not parity evidence.

## Acceptance plan

1. Capture red tests against the unchanged implementation: end-of-file
   lookahead, boundary peak containment, near-zero clip control continuity,
   below-threshold transparency, polarity, level scaling and stereo identity.
2. Correct confirmed defects, with independent numerical transfer/timing
   oracles. No merely updating historical hashes to conceal a changed result.
   Preserve Beat Comp's maximum-reduction and tempo/release contract.
3. Audit Punch onset selection, maximum/overlapping boost, stereo linking,
   control validity, block/seek and analysis lifecycle. Explain detector limits;
   do not claim all musical attacks are detectable without error.
4. Test actual clip PCM and actual metering separately, including 1x–16x
   oversampling, source-aligned position, bypass/zero, extreme finite levels,
   sample rates and ragged blocks. Quantify aliasing against a high-rate
   reference; first-order ADAA is not an alias-free guarantee.
5. Render private real tracks read-only, independently measure changed audio,
   inspect JavaFX controls/approved snapshots, run full application and vendor
   tests, build/package and deploy the Windows image to Program Files.
6. Verify installed hashes, clean EXE startup and installed functional probes.
   Record remaining limitations honestly; no claim of human listening.

## Initial inspection hypotheses (historical; findings resolved above)

- The forward minimum reuses an overly long window at the end of the file.
- Peak Comp's one-pole attack starts at unity even for a first-frame peak;
  a finite lookahead does not mathematically guarantee its stated ceiling.
- Hard-Clip enables an unexposed slew limiter and always-on first-order ADAA.
  The SOFT ceiling solver does not expand its upper bracket near zero reduction.
- Soft-Clip enables unexposed channel drift and a sample-rate-independent DC
  blocker, while claiming unchanged body and exact reduction.
- Clip meters show a static, positive-side curve estimate, artificially bounded
  to the knob, instead of measurements of rendered audio.
- DSPark Java ANALOG clipping has small-signal gain pi/2 rather than the C++
  unit-slope sine transfer.

Primary antialiasing reference: Parker, Zavalishin and Le Bivic, DAFx-2016,
[Reducing the Aliasing of Nonlinear Waveshaping Using Continuous-Time Convolution](https://www.dafx.de/paper-archive/details/vem_XXF5qBbfiWOH2RVVAA).
The method reduces aliasing; it does not justify the existing blanket
"no aliasing at base rate" comments.
