# Stereo generation correction — 2026-09-29

**Historical acceptance, superseded by the user's subsequent rejection and
[stereo-power-correction.md](stereo-power-correction.md).** Its maximum remained
too subtle, implicit output attenuation was unwanted, and inactive-section
metering was misleading. The results below document the previous binary only.

This supersedes the initial generation acceptance in `stereo-image-implementation.md`.
The user's perceptual report exposed an inadequate original test: changing float
samples and preserving mono did not demonstrate a useful amount of new stereo.

## Reproduction and cause

Installed original JAR: `618fdd1c11d8a6a2348918279c7e2591df55437cfaa7ef4f0d461d463b585e78`.
Read-only By Now, fixed 8-second excerpts at 30, 90, 180 seconds, Generation only,
harmonics on. At 100%, new delta versus Mid was -21.42/-25.68/-26.38 dB;
Side energy changed only +0.480/+0.163/+0.118 dB. The component orthogonal to
existing Side was similarly weak: -21.59/-25.86/-26.52 dB relative to Mid.
Evidence: `dist/stereo-generation-fix-20260929/before.log`.

Broad alternating Q=.8 bells substantially cancelled one another. The harmonic
stage replaced the entire differential signal with a ceiling-limited version,
which could constrain peaks instead of remaining a parallel color layer. The
initial three enabled sections also allowed
Leveler/Guard to counter the generator. A section checkbox could remain inert
behind the separately bypassed module. Earlier UI acceptance set the module On
programmatically and did not exercise that initial user path.

## Corrections

- Generation, Side Leveler, Side Guard all start off; a user turning on one
  section enables the parent module, without activating the other two.
- Harmonics is automatic within Generation. No checkbox and no visible band-count
  explanation/footer. Short English labels: Add stereo, Balance stereo width,
  Control excessive width. Legacy `harmonics:false` migrates to automatic on.
- Q=2 keeps the sixteen differential bands distinct. Their smooth gains span
  +/-2.2–3 dB. New-delta mix is unity at 50%, +6 dB at 100%; zero remains exact.
  This is not a gain applied to existing Side.
- Harmonic processing is a 10% blend of the aligned DSPark nonlinear residual,
  preserving the differential EQ branch, with the existing full-quality 8x
  oversampling at normal rates. The original audio is not saturated.
- Generation-only analysis no longer synthesizes the entire harmonic signal
  a second time for an unused gain plan. Leveler/Guard still analyze the actual
  generated signal when enabled. Unused target percentages are not displayed as
  an applied target during generation-only operation.
  A dual-mono input no longer produces a contradictory request to enable
  Generation while Generation is already active (regression assertion added).
- Same compensated linear-phase 150 Hz exclusion on the final new delta. No
  filtering/mono conversion of original bass. No new temporal widening layer.

## Verification results

On those same excerpts, full-generation new delta/Mid is now -9.34/-13.65/-14.35
dB, with novel/orthogonal components -9.45/-13.79/-14.42 dB. Side-energy changes
are +2.740/+0.846/+0.557 dB. Around 12 dB more actual NEW signal, not just extra
overall volume. The benchmark's 8-second renders dropped from about 765 to 398 ms.
These are objective differences, not a claim of listening or universal perception.

The same twelve real excerpts across By Now, Quiet Gold, Billie Jean (80s Glam
Metal) and Wicked Game (80s Synthwave) produced novel delta/Mid between -14.433
and -9.447 dB at 100%. At 50% the new signal is exactly 6.021 dB lower, as a
linear parallel mix should be. These excerpts share the same local modulation
origin in the before/after probe; the full-track audit separately exercises the
continuous absolute-time trajectories. Evidence: `generation-four-tracks-source.log`.

New tests enforce meaningful generated energy (>1.5% on the declared dual-mono
fixture), 0/50/100% delta mixing, default states and legacy harmonic migration.
Existing fixed -80 dBFS foldback threshold still passes (-118.38 dBFS measured);
DC 7.13e-7; impulse pre-ringing -62.94 dBFS, within the unchanged 1%-of-attack
limit. Mono, bass exclusion, preview/final alignment and partition tests pass.

A silent, paced SourceDataLine test routes real By Now through AudioPlayer,
InteractivePreview, fades and PCM16 writes: 180,224 output samples checked;
maximum error vs active preview 4.50e-5. Five edits reached the sink in
82.99–141.96 ms. This tests actual device-write bytes but is not a physical sound
card latency measurement or a human listening test. No audio sent to Windows.
Source evidence: `device-source.log`, `after.log`, `targeted.log` in the same run.

Completed delivery gates: revised UI/initial enable path; full clean suite and
package; fresh portable image and Program Files deployment; installed tests,
real source/device replay and clean EXE startup. No commit/push/release requested.

The source UI acceptance has now passed: first Generation click enables the
module while the other two remain off; final PCM matches an independent render
bit-for-bit; cached A/B, Undo, local Reference import and thirty rapid edits pass.
Normal and compact JavaFX snapshots were inspected, with no Harmonics checkbox,
band count or explanatory footer. This is UI/PCM evidence, not listening evidence.

## Verified package and installed results

The final clean Maven package passed **902 tests across 135 classes**, including
**21 Stereo Image tests**, with zero failures/errors/skips. The independent
DSPark suite passed **140 tests**. The existing musical matrix passed **106/106**
variants. The package's ITU/EBU file-loudness attestation reports **94 readings**
and PASSED, scoped to `QM-OFFICIAL-LOUDNESS-FILE-V1`, not a claim of full EBU Mode,
live, LRA or true-peak certification. Earlier interrupted/locked-clean attempts
are retained in their separate logs and are not counted as completed runs.

Windows portable generated with Temurin 25.0.4.7 (no installer), deployed to
`C:/Program Files/QuickMaster`, and launched successfully at 02:22:01 local time.
The application log records controller initialization at 02:22:02 without errors.
All **201 files** match the generated image, checked again after startup.

- Application JAR SHA-256:
  `59ee0c61e01f8abaea963bd7caa98ef36bbe6aa473b1d7d941edc5e790ea09b2`.
- Previous installation preserved at
  `C:/Program Files/QuickMaster-backup-20260929-022159`.
- DSPark 0.2.3 binary unchanged:
  `56df525290b09435a8d8cb5e3b56508546ad11b88f4ce2e858bdb1c2f2bb77b9`.
- **352 tests** rerun against installed production classes: all passed, none
  skipped. Historical source-only acceptance stays covered by the complete
  Maven run. No `target/classes` in the installed-test classpath.
- Installed By Now difference measurements reproduce the corrected values above.
- All **six routing cases** passed: before EQ, after Peak Comp, after Limit;
  each at 1x/4x with active dynamics/clips/limiters and final -1 dBTP protection.

Serial installed preview-window benchmark, twelve half-second windows after two
warm-ups. Harmonics remains automatic and full quality in every row:

| Global oversampling | Enabled sections | Median | p95 |
| --- | --- | ---: | ---: |
| 1x | Generation | 51.941 ms | 53.659 ms |
| 1x | All three | 197.301 ms | 207.866 ms |
| 4x | Generation | 114.161 ms | 116.468 ms |
| 4x | All three | 324.141 ms | 326.501 ms |

Installed PCM16 device-write path, five parameter changes each:

- No background render: **85.058–145.077 ms** to consumption.
- Concurrent whole-file render: **87.064–134.607 ms** to consumption. A started
  worker remained active throughout the interaction, then completed successfully.
- Each run checked **182,272 samples**, maximum error versus the active preview
  **4.49568033e-5**, within PCM16 conversion tolerance.

These are a paced simulated device's writes, not physical audio-interface latency
or a human listening test. No sound was emitted to Windows. Whole-file rendering
still takes seconds; interaction does not wait for it. The first Generation click
also passed in the installed JavaFX controller: parent enabled, other sections off,
changed output and bit-exact independent reference.

The installed full-track audit passed **20/20 renders**, five modes on each of the
four private recordings: Generation only, Leveler only, Guard only, combined,
combined with generated low frequencies. Every source identity stayed unchanged,
all output was finite, and worst L+R numerical error was **1.19209290e-7**.
Full-song energy fractions with Generation at 100%, measured before final output
headroom protection:

| Track | Original Side energy | Generated output Side energy |
| --- | ---: | ---: |
| By Now | 28.84% | 32.33% |
| Quiet Gold | 16.13% | 22.47% |
| Billie Jean (80s Glam Metal) | 7.20% | 13.65% |
| Wicked Game (80s Synthwave) | 13.05% | 18.10% |

These are power fractions, not perceived loudness percentages. Isolated floating-
point stage peaks can exceed 0 dBFS; final chain protection is separately tested
as linked gain, not clipping. Full-song run times were collected concurrently
with UI acceptance, not used as serial speed benchmarks. The preview timings in
the table above were measured serially before that concurrent work.

Extended installed UI acceptance also passed: cached A/B, Undo, local Reference,
the first Generation click, and **30 rapid edits** with no stale final publication.
Final PCM was bit-exact against independent cold renders for every checked state.
Controller preview publication took **213.36 ms** in this supplementary run;
it is separate from the serial/device benchmarks above. Default, Generation-only,
normal and compact snapshots were retained; layout/labels were visually checked.
All supplemental test processes exited successfully. Acceptance closed at 02:32
local time on 2026-09-29. No private audio changed, saved as a new audio export or
redistributed; no human listening or universal perceptual guarantee is claimed.

Evidence is
under `dist/stereo-generation-fix-20260929/`: `full-suite.log`, `dspark-tests.log`,
`deployment-result.json`, `installed-junit.log`, `installed-generation.log`,
`installed-performance.log`, `installed-device*.log`, `installed-routing.log`,
`installed-tracks.log`, `installed-ui.log`, and scene snapshots in `installed-ui/`.
