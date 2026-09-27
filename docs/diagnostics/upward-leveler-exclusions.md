# Upward Leveler and waveform exclusions

Status: implemented, deployed and installed functional/performance acceptance
passed on 28 September 2026. Includes the later processed-waveform request.

## Revised contract

The strongest sustained 3-second input RMS is the musical reference, not the
original sample/true peak. The Leveler only adds gain (0 to +24 dB), never
attenuates a passage or applies a global output trim. Amount scales the intended
difference in dB. A conservative 3-second energy bound retracts excess boost
where necessary to prevent exceeding the strongest macro RMS. Smooth gain and
stereo linking preserve the macro nature of the operation.

Float DSP can consequently require output peak headroom: the card explicitly
reports this. The separate Limit/Peak Normalizer controls remain responsible for
the delivery ceiling; the Leveler does not silently turn them on or clip/limit
transients to satisfy its RMS target. With those stages bypassed, overload can
clip at the physical PCM output. Do not confuse RMS convergence with peak safety.

## UX and lifecycle

- English `Exclude regions` toggle in the Leveler card; red transparent overlay,
  border handles, `LEVELER EXCLUDED` labels and an explicit waveform mode banner.
- Drag creates regions; drag edges resizes; Esc cancels a draft or exits mode;
  right-click offers `Edit exclusion times…` and `Remove exclusion`; `Clear all`.
- Dedicated gestures do not seek, crop, or change loops. Wheel pan and modifier
  zoom remain available. No DSP recomputation during drag; commit at release.
- Exact unit gain inside source-frame regions. Feathering happens outside them.
  Regions exclude only Leveler processing, not EQ/other compressors/limiting.
- Track state, shared across A/B settings; not part of generic chain presets or
  inherited by batch tracks. All A/B renders are invalidated on region edits.
- Undo/redo includes masks; crop/delete remap intervals; reset clears edits.
- Metadata stored separately in the user settings directory, keyed by SHA-256
  of the original audio plus validated sample rate/frame count. Source WAVs are
  read-only. Edited timelines are not saved over the original file's mask.
- Exclusion editing disabled while a replacement file is loading; stale loads
  cannot overwrite the current source or leave Clear all racing with reload.

## Verification gates

1. Unit oracles: nonnegative gain, RMS ceiling, reference independent of masks,
   protected PCM exactness, boundaries, source clocks, finite extremes, interval
   transforms and metadata validation.
2. Actual FXML asynchronous UI probe: create/resize/cancel, undo/redo, zoom,
   persisted reload, clear/undo, batch isolation and published PCM; inspect
   screenshots at normal and compact window sizes. Crop/delete and undo must
   remap/restore regions while leaving the source file unchanged.
3. By Now plus the four-track corpus, Amount/Speed matrix; update obsolete
   bidirectional test expectations explicitly, retain macro-contrast checks.
4. Full clean test/package, Java 25 app image, deployment to Program Files,
   exact hash comparison and clean EXE startup. Repeat installed acceptance.

Current source base: local commit 7edfe36 in QuickMaster-Integration. Preserve
the primary checkout and its pre-existing user changes. No public release/push.

## Installed delivery

- Complete Java 25 clean package: **831 tests, 0 failures, 0 errors, 0 skipped**,
  including all 106 musical PCM regression variants. Separate DSPark suite:
  **129 passed**. Official file-loudness evidence: 94 checks, profile
  `QM-OFFICIAL-LOUDNESS-FILE-V1`; not a claim of complete EBU Mode certification.
- Windows portable copied to `C:/Program Files/QuickMaster`; all **201 files**
  match the generated image. Both deployment and normal-user EXE startup have
  clean `Application starting (JavaFX)` / `Controller initialised` log entries.
- Installed JAR SHA-256:
  `0000ce9c21a4c38f47c361ea41e3762cfd50b22622240a9b9cef728f26f9079a`.
  Previous portable preserved at
  `C:/Program Files/QuickMaster-backup-20260928-005026`.
- **29 installed functional probes passed**, including 281 packaged JUnit
  tests, 9 raw-bit-exact A/B cache isolation variants, shared-tempo invalidation,
  processed-waveform pixel peaks and real-file exclusion editing/persistence.
- **48 installed corpus cases passed**: four songs × four Amount values ×
  three Speed values. By Now at 100% / Speed 50% repeats the 9.954282 →
  0.809429 dB central RMS spread and 159 windows lifted >1 dB; strongest input
  and output 3-second RMS remain −11.561008 dBFS, with zero global trim.
- With the UI-painted exclusions, **4,032,000 protected samples are raw-exact**
  while 12,433,321 unprotected samples exceed 1.12× original amplitude
  (about +1 dB). Crop/delete/numeric
  boundary variants also pass. Original source file hash is unchanged.
- Installed screenshots inspected at normal and compact sizes; strong limiting
  changes the waveform, Bypass restores original, and red exclusions remain
  aligned with the timeline. These are automated real JavaFX/FXML checks,
  not claims of manual listening or native mouse testing.
- The first installed runner stopped despite a passing audio probe because
  its log-marker regex did not accept the new two-line diagnostic. The runner
  now checks the two explicit success markers separately; the complete
  functional run was repeated successfully. No application change or rebuild
  was needed for that test-runner correction; both receipts are retained.

Build, delivery receipts, installed test logs, compact matrix measurements and
screenshots are in [installed evidence](upward-leveler-evidence/installed/).

### Final installed performance

All four performance probes passed, run serially without another test suite
competing for CPU. By Now, 280 seconds, 48 kHz stereo, 4 GiB Java heap:

| Interaction | Time until approved audio is available |
| --- | ---: |
| First B identical to already prepared A | 0.009182 s, shared PCM; no render |
| Cached A recalls | 0.004188–0.006270 s |
| Uncached B, dynamics edit | median 4.637547 s (3 runs) |
| Leveler-only parameter edit | median 1.348900 s (3 runs) |
| Full-chain parameter edit | median 4.758314 s (3 runs) |

Full configuration and individual measurements are recorded in the logs.
Meters finish after audio publication, so waiting for every meter is not the
same as waiting to hear the edit. Thirty repeated full-chain edits retain
1,276.348–1,276.368 MiB after forced GC checkpoints, with no accumulating
render queue. Forced GC is used only in the memory probe, not timing probes.
Published PCM is raw-bit exact to fresh-controller cold rendering. This is
machine/configuration-specific evidence, not an instant-render promise for
every chain, oversampling factor or hardware configuration.

Remaining limits are explicit: processing remains offline; waveform refresh
occurs when the new complete PCM is published, not on every intermediate knob
value. Short transitions, the activity floor, 24 dB cap and exclusions prevent
literal equality at every instant. A Leveler-only float render may need the
separate output limiter for peak headroom. No human listening or universal
perfection claim is made.

## Preflight observations (not final acceptance)

- 47 targeted tests pass on 28 September 2026; DSPark's 129 tests also pass.
- By Now, 100% / Speed 50%, no mask: central RMS spread 9.954282 → 0.809429 dB;
  159 overlapping 3 s windows rise >1 dB. Strongest input/output RMS both
  −11.561008 dBFS. No negative gain; global trim 0. Output true peak +5.328060 dBTP
  before separate output processing, disclosed rather than hidden.
- UI draft: protected samples raw-exact and unprotected boosts verified.
  Initial reload probe accepted the previous loaded generation too early;
  added explicit loading-state exclusion lock and corrected the probe's wait.
  Full FXML probe now passes create, resize, undo/redo, cancel, numeric bounds,
  zoom, clear, persisted reload, A/B, crop/delete/undo and batch isolation.
- Fixed a separate oversampling regression: negative FIR preroll previously
  returned without advancing the Leveler cursor, preventing it from ever
  reaching positive musical time. Red/green tests cover 2x, 4x and 8x streaming
  rendering and protected samples at the final decimated output.
- The first 48-case preflight found excessive suppression at Amount 100% /
  Speed 0 on By Now (2.005 dB central spread). Whole-smoothing-radius erosion
  propagated a local RMS bound into neighbouring passages. Replaced it with
  one-control-knot erosion plus smoothing projected below the proven bound:
  1.142 dB in that case, without raising the RMS ceiling or weakening the
  acceptance gate. All 48 revised corpus cases now pass in preflight.

## Exact A/B preparation

- Identical approved slots share immutable PCM, snapshot, waveform and meters.
  Pending controls, another source and changed regions are never eligible.
- Live edits and uncached A/B use one rendering path; an exact serialized tonal
  parameter key and source identity authorize reuse of the EQ/fade prefix.
  High-rate oversampling still runs the full chain at the requested quality.
- By Now preflight before waveform addition: first identical B 4.829900 s →
  0.007492 s; uncached dynamics-only B median 4.772300 s → 4.274022 s; cached
  switches ~3–4 ms. Three repeated uncached preparations of the same variant,
  same song/settings, isolated processes (modules enabled, Auto EQ off, no EQ
  bands, oversampling off, Leveling A 80% / B 40%, 4 GiB Java heap).
  These are preparation latencies, not claims about DAC response or all hardware.
- Nine synthetic isolation variants (dynamics, Auto EQ, EQ, fade, 2x/8x OS,
  regions, same-length source replacement and chain reorder) are raw-bit exact
  against a fresh controller/full render. Initial sharing, pending edits and
  cancellation are tested through real slot actions.
- A red/green slot test also reproduced a pre-existing shared-tempo invalidation
  defect. Manual BPM and returning to Auto now invalidate both renders, not just
  the active slot. The other slot cannot retain audio made with an older tempo.

## Waveform follows published audio

- Peak index built once on the render worker from final PCM, then atomically
  selected with its audible snapshot; no extra render and no full PCM copy.
- The original has its own index for Bypass. Cached A/B switches reuse their
  respective indexes. Pending edits retain the audible plan's waveform and
  caption, rather than falsely displaying a future result.
- Fixed 0 dBFS scale, red overload tips, `Processed A/B` / `Original · Bypass`
  captions. Zoom/pan/selection/exclusions retain their source-aligned timeline.
- Actual FXML probe compares every pixel peak against an independent scan of
  the published buffer, including strong limiting, Bypass, pending work, cached
  A/B and zoom. Screenshots are inspected; no human listening is claimed.
- The earlier full build was intentionally stopped when the user added this
  application-code change. Only the new complete run can qualify final delivery.
  A second incomplete run was superseded by the reproduced tempo-cache fix;
  neither interrupted run is counted as a passing complete suite.
