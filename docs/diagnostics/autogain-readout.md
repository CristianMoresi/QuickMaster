# EQ Auto Gain readout — 2026-09-28

## Scope

User-requested UI clarification only. The checkbox communicates enablement;
the adjacent readout communicates applied gain. No DSP, scheduling, audio,
export or Auto Gain default changes. No additional mastering modules implemented.

One formatter is used for final publication, provisional audition and restoration
of the approved plan. Inactive, unavailable and sub-display-resolution values
(absolute gain below 0.05 dB, displayed as 0.0 dB) produce empty text. Nonzero
gain retains sign/units; provisional nonzero gain retains the approximate marker.
FXML starts empty and keeps its 68 px minimum width to avoid layout movement.

## Regression and delivery

- Five headless readout/FXML tests passed.
- The new packaged-UI regression failed against the previous installed JAR for
  the intended reason: `Initial Auto Gain readout is not blank`.
- DSPark: 140 tests passed; implementation unchanged.
- All 151 processing/playback class files are SHA-256-identical to the previous
  installed JAR. No audio-engine bytecode changed.
- Candidate real-FXML acceptance passed all EQ cases, including neutral blank
  with the checkbox enabled, disabled blank, nonzero values, preview/restoration,
  undo/redo, A/B, 4x and export. The neutral screenshot was visually inspected.
- Complete clean package: 881 application tests across 132 suites, no failures,
  errors or skips; includes 106 musical variants. Official file-loudness profile:
  94 readings passed, including packaged-JAR attestation verification.
- Portable generated with Adoptium 25.0.4.7 and deployed at 19:46 CEST to
  `C:/Program Files/QuickMaster`. All 201 image files matched; installed JAR SHA-256:
  `cbad134db59f08138722b07550774e30869f580dbcad25ede7f91d0347aa78a2`.
- Installed executable started cleanly, including a separate unelevated launch
  at 19:47. Backup: `C:/Program Files/QuickMaster-backup-20260928-194643`.
- Five readout tests passed against the installed JAR. Full real-FXML EQ audit
  passed again against that installed JAR: startup/neutral/disabled blank,
  signed nonzero compensation, preview/restoration, undo/redo, cached A/B,
  4x oversampling, output normalization and 32-/24-bit/44.1 kHz export.
  Installed neutral screenshot visually inspected; checkbox enabled, no text.
- By Now original SHA-256 unchanged:
  `dcfb61d5d419bf6044bb0dd42fd7639659f33564c4b7ef9219cbf66798bf8e91`.
  No owned test processes remain. No commit, push or release requested/published.

Evidence directory: `dist/autogain-readout-20260928/`.
The performance/macro supplemental matrices from the preceding delivery were
not repeated for this presentation-only change; no new timing claims are made.
Final summary: `dist/autogain-readout-20260928/final-summary.json`.
