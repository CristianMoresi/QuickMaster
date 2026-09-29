# Stereo Image controls and cached rendering — 2026-09-29

Status: application delivered and verified; workspace consolidation completed.

## Product changes

- The approved generator, harmonic quality and 0–100 strength are unchanged.
- New sessions: EQ → Stereo Image → Dynamics → Clip → Limit. Explicit saved
  five-module orders are preserved. All three stereo sections still start off.
- Generated Stereo Low Cut: Off by default; logarithmic 20 Hz–5 kHz, -6 dB
  center frequency, compensated linear-phase filtering of the generated delta
  only. Original Mid and Side are untouched. Old bass-exclusion presets migrate
  to 175 Hz, retaining their 150–200 Hz transition. A cutoff at/near Nyquist
  rejects all generated content instead of allocating an unbounded FIR.
- Side Gain: independent final -12…+12 dB gain of combined Side after Leveler and
  Guard. Mid remains unchanged. A positive manual gain can exceed the Guard
  target: this is explicit, not hidden compensation. No whole-mix normalization.
- Three equal cards, aligned section switches and knobs, compact balance meter,
  common two-slider row, numeric entry and double-click reset. English UI.

## Architecture and fidelity

`OfflineRenderProcessor` provides a fused analysis/render path. Stereo retains
one immutable raw harmonic delta and one filtered variant, shared across snapshot
forks, keyed by exact immutable input-array identity and sample rate. It never
shares mutable synthesis state. Upstream/source changes invalidate reuse.

The raw cache keeps the causal harmonic stream, latency and flush tail. A changed
cutoff filters that stream and compensates latency exactly as streaming does;
it does not approximate the filter at track boundaries. Amount, Side Gain and
regulation changes reuse synthesis. Canceled/obsolete calculations cannot publish
partial cache entries. Retained delta storage is capped at 256 MiB (not a total
application or transient allocation limit); oversized tracks use the existing
uncached path. Global oversampling still renders the full high-rate chain.

Unit acceptance covers exact cached/cold output, streaming boundaries, several
cutoffs and rates, filter symmetry/response, Mid preservation, independent final
Side gain, source/rate invalidation, cancellation, preset migration and preview
alignment. The numerical oracles do not substitute for subjective listening.

The provisional window path also synthesizes once, retaining double-precision
Mid/Side for local planning and rendering. Its new regression compares the
complete window (including boundaries, absolute modulation, regulation and trim)
bit-for-bit against the prior two-pass algorithm at five cutoff settings.

## Comparable whole-track measurements

`StereoWarmEditAudit` used the same legacy API and unchanged private By Now.wav
(48 kHz stereo, 13,440,001 frames) against the preceding installed build, then
the changed source. Times include Stereo DSP, not UI loudness/waveform work.
The full regression suite and UI acceptance also ran in the background; these
are observed workstation results, not universal latency guarantees.

| Operation | Previous build | Cached implementation |
|---|---:|---:|
| Initial full-frequency generation | 13.9832 s | 15.6950 s |
| Amount 100 → 50% | 15.8374 s | 0.1016 s |
| Enable Side Leveler + Guard | 34.7181 s | 0.3007 s |
| Low Cut Off → 175 Hz, regulation off | 19.2175 s | 0.8082 s |
| Enable regulation with same filter | 38.9415 s | 0.2313 s |

The first calculation is not faster in this run. The improvement addresses
repeated edits, without reducing harmonic oversampling or filter quality.
The actual controller's first acceptance run completed cutoff edits, final
statistics and publication in 2.30–3.36 s. Its published whole-track PCM matched
an independent cold render exactly at Off, 175 Hz, 1 kHz and 5 kHz and after
Side Gain / section changes. Immediate audition remains a separate window worker.

The initial preview benchmark exposed a remaining duplicate analysis/render
synthesis with regulation: observed p95 294–322 ms at 1x exceeded its 250 ms
budget. Those failed runs are retained. After fusing that path, the unchanged
benchmark passes: p95 56.05 ms Generation-only / 107.89 ms with regulation at 1x;
149.29 / 310.54 ms respectively at global 4x. A 12-edit timed PCM16 sink test
with concurrent full-track synthesis consumed changes in at most 171.73 ms,
335,872 checked samples, maximum quantization error 0.00004501; no Windows audio.
The full suite was deliberately restarted after this extra application change.

## Evidence

Local, non-distributed logs and screenshots: `dist/stereo-interaction-20260929/`.
Private tracks are read-only; no processed audio is exported or sent to Windows
audio. The timed playback probe uses an explicitly documented -18.0618 dB input
trim solely to keep its PCM16 test sink out of clipping. Unscaled musical
acceptance separately verifies power and gain ownership.

## Completed delivery

- Full suite: **913 tests / 136 classes**, zero failures/errors/skips, all reports
  fresh for the final run. Includes 106 musical variants. DSPark: **140 passing tests**.
- Package loudness gate: **PASSED, 94 readings**, scoped to
  `QM-OFFICIAL-LOUDNESS-FILE-V1`, not blanket EBU/live/LRA certification.
- JDK: Eclipse Temurin 25.0.4.7. New Windows portable deployed to
  `C:/Program Files/QuickMaster`, **201 matching files**. Installed EXE clean
  startup: 15:44:16–17 local time. Previous image preserved at
  `C:/Program Files/QuickMaster-backup-20260929-154414`.
- JAR SHA-256: `20fe4d4d7bbb0484a7fbab784cf2e31b0707de57fb673c0cbdc893be0d84a2a1`.
- Installed JAR: **363 tests passed**, eight unscaled whole-song power renders
  over four recordings, six 1x/4x routing cases, no hidden normalization.
- Installed real FXML/controller: all new controls, numeric cutoff values,
  inactive meters, exact full PCM, cached A/B, Undo, reference loading and 30 rapid
  edits passed. Last publication belongs to the last settings. Normal/compact
  scene snapshots visually inspected. Files in `installed-ui/`.
- Installed cold synthesis 17.2786 s; warm Amount/regulation 0.1241–0.3466 s;
  changed filter 1.1854 s. UI cutoff edits with final stats: 1.82–3.69 s under
  concurrent acceptance load. Preview p95 59.33/113.88 ms (Generation/all at 1x),
  199.47/390.79 ms (4x). Timed PCM16 consumption max 217.01 ms with a concurrent
  full render; controller preview publication 138.53 ms. These are different
  measurements and must not be presented as interchangeable.
- Source acceptance also passed 20 whole-song renders (five modes × four songs).
  Private files remained unchanged and no sound was sent to Windows audio.

## Workspace consolidation

Completed at 16:15:51 local time. `E:/Code/Projects/JA-DAW` contains only
`QuickMaster`, the sole checkout on `main`. Current uncommitted development is
preserved there; no release, commit or push was requested.

- All 1,227 current files matched SHA-256 before the duplicate Integration
  checkout was retired; every remaining file was independently enumerated too.
- Original checkout backup: 61,419 verified files. Rapid (107,610), Recovery
  (402) and Helper (201) files retained under `.archive`, verified before/after
  same-volume moves. Official signals and their metadata (120 files) retained
  unchanged under `test-data`; no audio or authorization is added to Git.
- Complete pre-consolidation Git bundle and recovery stash retained. Two old
  fixture junctions are preserved with their original, now obsolete absolute
  targets; metadata is recorded. No junction target was recursively deleted.
- Historical research, reports and build outputs are under this project's
  `dist`. The local report server was restarted at its new path on port 8767;
  its page returned HTTP 200. Existing installed application files are unchanged.
- Canonical-path recompile/regression: 39 passing tests. The same packaged JAR
  reopens with `PASSED`; all 201 installed image files still match.

Receipts and inventories are in `dist/stereo-interaction-20260929/`, notably
`consolidation-result.json` and `consolidation-verification.json`. Earlier logs
retain their original paths as provenance. See `WORKSPACE.md` for the only active
workspace and recovery locations. Subsequent organization edits affect only
documentation/Git ignores, not application code, resources or packaging.
