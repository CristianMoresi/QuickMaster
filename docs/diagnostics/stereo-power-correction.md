# Stereo power, low-frequency switch and meter correction — 2026-09-29

## Status

Implementation, full-suite/package, deployment and installed acceptance passed.
Closed at 05:44 local time on 2026-09-29. No commit, push or release requested.
This supersedes `stereo-generation-correction.md`: previous changed-sample and
energy tests did not establish the user-requested maximum generation range.

## Reproduced failures

Old installed JAR: `59ee0c61e01f8abaea963bd7caa98ef36bbe6aa473b1d7d941edc5e790ea09b2`.
Read-only eight-second By Now excerpts, Generation 100%, other sections off,
Peak Normalizer off. At 90 seconds, enabling generated bass changed the hidden
output gain from -0.118030 to -1.172971 dB. Stereo had silently enabled the
normalizer's safety pass. The checkbox also changed the entire frequency bank,
rather than just allowing generated lows. An enabled module with no active
sections returned before metering, producing NaN and misleading UI text.

Evidence: `dist/stereo-power-fix-20260929/before-power.log`, `red-tests.log`.
The red tests caught all three defects. The user's screenshot also shows the
Generation section unchecked; the module being enabled alone does not activate
sections, which remain independently opt-in as requested.

## Implementation

- New delta mix multiplier 2 → 8 at maximum (four times / +12.041 dB range).
  Zero remains exact; this is not multiplication of original Side. Eight upper
  differential bands/channel plus two low bands/channel remain identical in
  both low-checkbox states. Only the compensated 150–200 Hz linear-phase
  exclusion changes. Original Mid/bass are not attenuated or filtered.
- Same smooth, disjoint band trajectories and DSPark harmonic residual at
  full-quality rate-adaptive oversampling. No reduced-quality preview branch.
- Stereo does not request final linked output attenuation. An upstream EQ Auto
  Gain cannot extend its output-safety pass past an active Stereo stage. An EQ
  deliberately placed after Stereo can still compensate its own input/output.
- Explicit Peak Normalizer/Limit remain available. Above-0-dBTP output is warned
  about, not silently reduced or saturated. Strong added Side increases peaks:
  with protection off and insufficient headroom, PCM playback/integer export
  may clip. Floating-point rendering itself remains unclamped.
- Inactive sections still measure passing stereo audio without changing PCM.
  UI heading follows current controls, distinguishes provisional results and
  says “Select a section to process stereo” instead of a false empty-meter error.

## Targeted evidence

For the same 30/90/180-second excerpts with lows excluded, orthogonal new delta
relative to Mid changed from -9.447/-13.906/-14.417 dB to
+2.266/-2.110/-2.683 dB. Every low-checkbox state now gives 0 dB output gain
and 0 dB Mid gain within numerical tolerance. Floating peaks can exceed one;
that is explicitly tested unattenuated, not presented as safe integer output.

Tests cover upper-band invariance under the low switch, preview/final PCM,
no-section meters, explicit versus implicit normalization, EQ/Stereo ordering,
strong generated energy, mono preservation and alias/DC/transient limits.
Measured 13 kHz alias -96.074 dBFS, DC 2.846e-6, impulse pre-peak -57.416 dBFS:
existing quality thresholds have not been relaxed.

These are objective measurements, not a human listening or universal perceptual
guarantee. Source audio remains private and unchanged. Further results below
will identify the final installed binary and the completed acceptance gates.

## Extended source checks

All 20 full-song renders passed (five independent/combined modes per recording).
Generation 100%, low generation off, Leveler/Guard off:

| Recording | Original Side energy | Generated Side energy |
| --- | ---: | ---: |
| By Now | 28.84% | 54.27% |
| Quiet Gold | 16.13% | 54.96% |
| Billie Jean (80s Glam Metal) | 7.20% | 52.50% |
| Wicked Game (80s Synthwave) | 13.05% | 50.14% |

These are power fractions, not perceived loudness percentages or recommended
mastering targets. The maximum is deliberately strong. Guard/Leveler, if enabled,
regulate the combined Side and can counter this widening. Worst L+R numerical
error across the modes was 1.78813934e-7; every source identity remained unchanged.
Evidence: `source-tracks.log`. These concurrent functional runs are not serial
performance benchmarks.

With a +9 dB linear-phase EQ at 857 Hz before Stereo on a real By Now excerpt,
EQ Auto Gain remained -3.497102 dB for both low states, and output trim stayed
0 dB. Maximum Mid error versus that EQ's output was 1.19209290e-7. Preview used
its independently estimated EQ gain (-3.954656 dB) but no additional output trim
in either low state. Evidence: `source-eq-stereo.log`.

Full source UI acceptance passed: inactive-section metering, first Generation
enable, low-frequency maximum with zero hidden gain and an overload warning,
cached A/B, Undo, actual reference-file import, provisional preview and 30 rapid
parameter changes. Final published PCM is bit-exact against independent cold
renders at the checked states. Source identity stayed unchanged. Normal and
compact scene snapshots were visually inspected. The concurrent controller
publication measurement (310.81 ms) is not a serial/device latency benchmark.
Evidence: `source-full-ui.log`, `source-full-ui/`.

## Package and installed playback

Clean Maven suite/package: **905 tests, 135 classes, zero failures/errors/skips**,
including 24 Stereo Image tests. DSPark: **140 tests**, unchanged binary hash
`56df525290b09435a8d8cb5e3b56508546ad11b88f4ce2e858bdb1c2f2bb77b9`.
Musical matrix: **106/106**. Package file-loudness attestation: **94 readings**,
PASSED, limited to `QM-OFFICIAL-LOUDNESS-FILE-V1`, not a full EBU Mode/live/LRA
or true-peak certification claim. Existing true-peak tests run separately.

Portable built with Temurin 25.0.4.7 and copied to `C:/Program Files/QuickMaster`.
All **201 files** matched the image after startup. EXE startup and controller
initialization logged cleanly at **05:39:07** local time.

- Installed/build JAR SHA-256:
  `ec8b3a831c5152ff34aab24ea7fa87432c0e3a54f2a32d069b2db64f2a9b2ff4`.
- Prior portable preserved at `C:/Program Files/QuickMaster-backup-20260929-053905`.
- Deployment receipt: `deployment-result.json` in this run's evidence directory.

Installed serial half-second preview rendering, 12 windows after two warmups:

| Global oversampling | Sections | Median | p95 |
| --- | --- | ---: | ---: |
| 1x | Generation | 54.835 ms | 56.082 ms |
| 1x | All three | 211.494 ms | 219.046 ms |
| 4x | Generation | 119.848 ms | 122.384 ms |
| 4x | All three | 340.480 ms | 347.986 ms |

These used unscaled real By Now audio and full-quality automatic harmonics.
The silent AudioPlayer/InteractivePreview/PCM16-write test uses an explicit
**-18.0618 dB input trim in the test fixture only**, leaving room for the new
strong maximum. It does not insert a hidden gain in the product. Seven edits
include toggling low generation on/off while playing:

- No background render: 85.585–143.732 ms, 231,424 PCM16 samples checked,
  max error 4.51593660e-5 against the active preview.
- Concurrent whole-file render: 85.204–125.623 ms, 233,472 samples checked,
  max error 4.49605286e-5. The worker started before edits, overlapped the whole
  interaction and then completed successfully.

No physical sound-card latency or human listening claim; no audio sent to Windows.
Evidence: `installed-performance.log`, `installed-device*.log`.

The installed JavaFX controller passed the first-enable path, inactive meters,
Generation 100% with lows on and no hidden output gain, overload warning and
independent bit-exact final render. Installed snapshots were visually inspected.
Evidence: `installed-ui.log`, `installed-ui/`. The longer A/B/Undo/reference/30-edit
acceptance above used the source classes from the same frozen build.

## Final installed acceptance

**355 regression tests passed against the installed production JAR**, with zero
skips/failures. Class provenance was checked; `target/classes` was not on that
classpath. The initial narrower 327-test run is retained but not added again to
the count. Historical source-only contracts are covered by the complete Maven
run. Evidence: `installed-junit-full.log`.

The six By Now excerpt/low-switch cases reproduce the corrected power measurements
above on the installed build. Eight additional whole-song renders (four tracks,
each with low generation off/on) passed with **0 dB hidden output gain and 0 dB
Mid change** within numerical tolerance. The genuinely new component, orthogonal
to existing Side, relative to Mid over the complete songs:

| Recording | Lows excluded | Lows included |
| --- | ---: | ---: |
| By Now | -1.908 dB | +1.341 dB |
| Quiet Gold | -0.542 dB | +2.693 dB |
| Billie Jean (80s Glam Metal) | -0.213 dB | +2.112 dB |
| Wicked Game (80s Synthwave) | -1.414 dB | +3.054 dB |

These are unscaled original recordings with Peak Normalizer off, not the
headroom-trimmed PCM16 test fixture. All source identities remained unchanged.
Installed EQ-before-Stereo checks also reproduce the gain/Mid results above.
Evidence: `installed-power-excerpts.log`, `installed-power-whole.log`,
`installed-eq-stereo.log`.

All **six installed routing cases** passed: Stereo before EQ, after Peak Comp,
and after Limit, each at 1x/4x, with active EQ, dynamics, clips, multiband and
broadband limiting. When the final Peak Normalizer is explicitly enabled,
each final output meets its -1 dBTP ceiling. Evidence: `installed-routing.log`.

Every audit process exited successfully. Private audio was neither overwritten
nor exported/redistributed. The previous portable remains recoverable. No claim
of physical-device latency, human listening, or universally ideal stereo width.
