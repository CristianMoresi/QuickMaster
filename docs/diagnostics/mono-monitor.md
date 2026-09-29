# Mono monitoring and Stereo Image readouts — 2026-09-29

Status: implemented, tested, packaged and locally delivered; installed acceptance passed.

## Changes

- Generated Stereo Low Cut and Side Gain values are plain, non-interactive labels.
  Their popup dialogs have been removed, not restyled. Sliders and double-click
  reset remain; the low-cut DSP and approved Generation range are unchanged.
- Generation's initial and knob-reset value is 25%. The three stereo sections
  still start off. Explicit amounts in existing presets are not overwritten.
- `Listen in mono` sits below the right-hand Correlation / Mid / Side rows.
  It starts off each application session. It is a monitor control, not a preset,
  processing-chain stage or export option.

## Audio contract

The device encoder folds stereo to `(L + R) / 2` in both output channels before
PCM16 clamping/dither. Centre gain is unity; opposite polarity cancels. No
automatic compensation obscures that cancellation. Original one-channel input
stays one channel at unity. Stereo mode retains the previous conversion path.

Each playback session owns a 20 ms smoothstep transition of Side from unity to
zero (or back), retaining Mid. Reversals start from the current gain. The UI only
changes a volatile monitor request; it never seeks, flushes, prepares DSP, starts
analysis, invalidates cached renders or edits shared sample arrays. The setting
applies after original/processed A/B selection, fixed comparison and preview.

Meters, waveform, export, batch, presets and mastering PCM remain stereo. The
tooltip explicitly distinguishes this from listening. Actual hardware queueing
still contributes latency; this is not a zero-latency device guarantee.

## Acceptance

- Targeted regression checks actual PCM16 writes for published, live, fixed,
  bypass and preview paths; smooth switching, exact frame count, one device
  session, source/render immutability and stereo metering are asserted.
- Centre, anti-phase, one-sided and over-range floating inputs, partial blocks,
  mono files, multiple rates and rapid transition reversals are covered.
- Compiled scope comparison against the preceding installed JAR: 193 classes
  are unchanged, including the generator/filter/DSP implementations. Changed
  owners are the player/session, Stereo settings default, pane and controller
  (including their nested-class line metadata); MonoMonitor is the sole new
  production class. DSPark JAR SHA-256 remains
  `56df525290b09435a8d8cb5e3b56508546ad11b88f4ce2e858bdb1c2f2bb77b9`.
- Real FXML/controller with By Now: default and knob reset 25%; no readout
  dialogs; correct right-panel placement; 41 monitor toggles preserve the exact
  render object, source, preset JSON, transport position and analysis generation.
  No delayed analysis after toggling. Stereo generation output agrees exactly
  with an independent cold render. Normal/compact snapshots visually reviewed.
- Silent timed device probe: unattenuated excerpts of By Now and Quiet Gold,
  22 switches and 131,073 frames each; 217,090 settled channel samples checked
  per song. Maximum PCM16 error about 0.000045, within TPDF quantization tolerance.
  Mid checked through transitions wherever device clipping cannot change it.
  Quiet Gold contains three over-range source samples in the excerpt: the
  independent oracle accounts for the existing PCM16 output clamp after folding.
  The initial probe's assumption that both inputs were below full scale was
  rejected; that log is retained. No normalization or input attenuation was added.
- Private files remain unchanged. No audio is exported or sent to Windows audio;
  numerical checks and scene snapshots are not represented as human listening.

## Final delivery

- Full clean package: **921 tests / 138 classes**, zero failures, errors or skips.
  Includes 106 musical variants and 94 passing official file-loudness readings.
- DSPark: **140 tests / 37 classes**, zero failures, errors or skips.
- Windows image generated with Temurin 25.0.4.7; all 201 image files verified
  before replacement in `C:/Program Files/QuickMaster`. The initial non-elevated
  deployment correctly refused to modify the installation; the supported
  administrator deployment then completed with rollback backup preserved.
- Installed JAR SHA-256:
  `11e121938ed36badf4586a3344c673da646e391d9fbd94966edf0f30e0051e8d`.
  All 222 packaged application classes match `target/classes` byte for byte.
- Installed executable launched successfully: clean startup and controller
  initialization logged at **16:58:35** local on 2026-09-29. Only the check's
  own application process was closed afterwards.
- **371 tests** rerun against the installed JAR passed. Installed real UI and
  both real-song silent device probes also passed, with the same assertions
  and numerical results described above. Normal and compact installed UI
  screenshots visually inspected, including selected/unselected monitor states.
- Previous portable preserved at
  `C:/Program Files/QuickMaster-backup-20260929-165833`.
- No commit, push or release requested or performed. Closure: 17:00 local.

Local logs, test reports, deployment receipt and screenshots:
`dist/mono-monitor-20260929/`. Portable:
`dist/mono-monitor-20260929/image/QuickMaster/`.
