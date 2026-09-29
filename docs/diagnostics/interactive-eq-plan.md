# Interactive EQ audition — 2026-09-28

Status: COMPLETE. Final corrected candidate built, delivered and verified;
interactive, functional, macro and isolated performance acceptance all passed.
The first candidate's historical records below are explicitly separate from the
final candidate. No release or commit requested.

The offline worker already runs outside FX. The defect is the publication
barrier: playback cannot hear changed EQ until an entire new master exists.

## Implementation contract

1. Independent latest-request preview worker. Render a short source-aligned
   window around the playback cursor, never the complete track. Publish only
   immutable PCM with source and request identity. Coalesce queued edits, but
   allow the bounded in-flight window to finish: cancelling every drag event
   would starve audition. Publications advance monotonically. Source/A-B/final
   render barriers cancel even in-flight work.
2. Preserve actual chain order, selected EQ phase/routing and oversampling.
   Reuse the last completed downstream analysis, never apply an extra EQ over
   an already mastered buffer. Use bounded preceding audio to warm filters.
3. Estimate EQ Auto Gain from the local audition context once per edit.
   Protect each ahead-of-playback window with a linked linear trim when needed.
   This is explicitly provisional monitoring, not the final whole-track gain.
   No hidden waveshaper; retain manual headroom when Auto Gain/normalizer are off.
4. Source-clock window overlap and short crossfades; no seek, playback restart,
   filter rebuild, whole-track analysis or wait on the audio/FX thread. A/B,
   source changes and close invalidate preview ownership.
5. Keep the exact offline render and export path. On completion transition to
   it and discard previews. Indicate Preview/Updating honestly; waveform of
   the last complete render must not masquerade as a complete preview render.

## Verification and delivery

- Independent signal and fake-device tests: changed PCM before final render,
  phase/clock alignment, routes, downstream order, finite safe windows,
  crossfade/seek/loop, stale rejection, cancellation and source immutability.
- Actual FXML controller and By Now, measuring request-to-preview and actual
  consumption (not merely Ready). Goal below 100 ms for ordinary EQ at 1x;
  report costs at higher oversampling and bounded-warmup limitations honestly.
- Whole suite including official corpora; package/image on supported JDK;
  copy to Program Files, hashes, clean installed EXE startup and acceptance.

Keep the existing dirty main checkout and all preceding audit changes intact.

## Architecture and limits

- `InteractivePreview` owns a separate daemon worker. An 8 ms FX throttle
  coalesces edits without waiting for gesture end. No whole-song DSP runs on
  FX or the audio device thread. The exact worker retains its 220 ms debounce.
- `PreviewWindowRenderer` renders source-aligned 0.5 s windows with bounded
  history and right padding; the next window overlaps by 0.1 s and is prepared
  ahead of the device cursor. Loops up to two seconds use one complete window.
  The player retains two windows, fades over 20 ms, and never changes transport
  position to publish a new EQ. Device-clock markers distinguish publication
  from actual device advancement.
- Selected phase, channel routing, chain order and oversampling are preserved.
  At high rates the preview EQ prepares capacity for a full oversampled block
  instead of paying the same large FFT cost in many small subdivisions. The
  filters/kernel duration do not change. Reference comparisons cover this.
- Downstream offline envelopes/profiles are reused from the last completed
  plan, not recalculated over each local snippet. Therefore this is an EQ
  audition, NOT an exact preview of the final reanalysed full dynamics chain.
  Other-module, source, A/B and oversampling changes cannot reuse this path.
- EQ Auto Gain is calibrated once per edit from local context. Additional
  monitoring protection uses a linked linear trim with a -1 dBTP margin,
  held or decreased between windows. Both remain provisional; their readouts
  show `≈`. The final whole-track gain can differ. With Auto Gain and the peak
  normalizer off, manual headroom remains manual; no hidden clipper is added.
- FIR histories are finite. Very long IIR/dynamic detector histories are
  approximated with bounded warm-up (up to two seconds for static minimum
  phase, at least 1.5 seconds for dynamic bands). Export is never approximated.
- Whole-track waveform/statistics update when the final render is ready. While
  listening to the local preview, the caption explicitly says waveform updating.
  Final export and A/B caches never contain preview PCM.

## Preflight evidence (not installed acceptance)

Directory: `dist/interactive-eq-20260928/`. Actual FXML controls and Windows
audio device, private By Now read-only. No human-listening claim.

| Case | Warm median to device advancement | Sustained drag | Exact final PCM |
| --- | ---: | --- | --- |
| EQ, full track, 1x | 97.049 ms | passed | bit-identical |
| Complete chain, full track, 1x | 128.180 ms | 18 heard generations | bit-identical |
| Complete chain, **30 s excerpt**, 4x | 315.218 ms | 3 heard generations | bit-identical |

These are machine/preset-specific, not a universal sub-100 ms guarantee.
Single-edit tests deliberately hold the final debounce to prove the changed
audio comes from preview; installed acceptance additionally allows the normal
full worker to run concurrently. Source hashes are checked before/after.

Rejected intermediate experiments are retained in the evidence directory:
the first full-track 4x initial render exceeded the 180 s diagnostic timeout;
the initial 4x excerpt had insufficient sustained-drag progress until the FFT
block optimization. This task removes the interactive EQ publication barrier;
it does not claim to have solved the cost of arbitrary full-track 4x masters.
Initial device tests also caught an end-of-window transition click and a
generation-marker overwrite; both have dedicated passing regressions.

DSPark suite rerun: 140 tests, zero failures/errors/skips. No vendor source
change is part of this interactive-preview implementation.

## Verified local delivery

The following delivery is the FIRST candidate, before the context-revision fix.

Complete clean package passed at 17:57 CEST: 876 application tests in 131
suites, no failures/errors/skips; all 106 musical variants. The official
file-loudness profile passed 94 ITU/EBU readings and reopened-JAR verification
(not a claim of full EBU Mode/live/LRA/true-peak certification).

Portable generated with Eclipse Temurin 25.0.4.7, jpackage application image
1.3.3 (application 1.3.3-SNAPSHOT). Copied to `C:/Program Files/QuickMaster`
at 17:58; all 201 files match the generated image. Elevated and independent
unprivileged EXE launches both logged a clean JavaFX/controller startup.

- Application JAR SHA256:
  `fb54356df62389414d12ee1bc22f536867b2718b39bbb2c01d2944290c5c8505`.
- Unchanged DSPark 0.2.3 SHA256:
  `56df525290b09435a8d8cb5e3b56508546ad11b88f4ce2e858bdb1c2f2bb77b9`.
- Previous portable preserved at
  `C:/Program Files/QuickMaster-backup-20260928-175817`.
- Build/image/startup records: `dist/interactive-eq-20260928/`.

## Installed interactive acceptance

All three probes passed against **only the installed production JARs**, with
no competing benchmark. Actual FXML/controller and Windows audio device;
timings measure device frame advancement, not simply worker completion.

| Case | Warm median to device | Drag generations heard | Normal debounce path |
| --- | ---: | ---: | ---: |
| EQ, full By Now, 1x | 96.569 ms | 16 | 115.093 ms |
| Full chain, full By Now, 1x | 117.624 ms | 17 | 113.051 ms |
| Full chain, **30 s excerpt**, 4x | 209.134 ms | 5 | 204.629 ms |

Each also passed seek/short-loop/paused completion, safe provisional windows,
changed audio before full publication, source identity before/after, and final
PCM **bit-identical** to a newly rendered cold reference. The normal-debounce
probe leaves the full worker enabled and checks continuing transport while it
finalizes. The optional private excerpt is deleted in the diagnostic's finally
block; original songs are never modified or published.

Evidence: `dist/installed-audit-interactive-20260928-175912-0eb8cb4a/`.
Its summary is copied into the task evidence directory. The actual installed
`eq-interactive/preview.png` and `final.png` were visually inspected: English
labels fit, local gain uses `≈`, waveform clearly says updating during preview,
and these provisional indicators disappear after final publication. This is
mechanical audio/UI validation, not a human-listening claim.

## Context-invalidation follow-up

Review found that Leveler exclusions and manual tempo are track-scoped, not
fields of `ChainPreset`. Comparing only the preset could reuse a preceding
downstream plan if an EQ edit immediately followed either change. A new actual
FXML/controller regression reproduces the exclusion failure on the installed
first candidate (`context-red.log`).

The controller now increments a track-context revision whenever all slot renders
are invalidated. Snapshots retain the captured revision. EQ preview requires it
to match, in addition to preset/source compatibility; a newly completed plan
re-enables audition. This also covers manual tempo changes without reading a
mutated shared analysis object's old values. The regression checks exclusions,
manual tempo, re-enabling after completion and source immutability. It is part
of installed functional acceptance (`eq-preview-context`). Targeted 36 tests
passed; `context-green.log` passes all four checks with the candidate classes.
A new complete clean package passed (`final-clean-package.log`), again with
876 application tests, 131 suites, 106 musical variants and 94 official readings.
DSPark was independently rerun: 140 tests, no failures/errors/skips.

## Final candidate (context revision included)

- Final JAR SHA256:
  `8463d8eb3ed2715b6a7e5f5d672cdac0e2fcc0d9044ff8b0fb3dab02c359dccd`.
- Image: `dist/interactive-eq-20260928/verified-image/QuickMaster`.
- Program Files copy verified at 18:46 CEST; independent unprivileged EXE
  startup verified at 18:47. All 201 image files match. Deployment records:
  `final-deployment-result.json`, `final-unprivileged-startup.json` in the task
  evidence directory.
- Immediate previous portable preserved at
  `C:/Program Files/QuickMaster-backup-20260928-184615`; the pre-preview version
  also remains at the earlier 17:58 backup.
- Final installed interactive evidence:
  `dist/installed-audit-interactive-20260928-184708-b230d02f/`.
  All three probes passed, including source immutability, seek/loop, normal
  background-worker path and final PCM bit identity. Preview/final screenshots
  were visually inspected again on this final artifact.

| Final installed case | Warm median to device | Drag generations | Normal debounce path |
| --- | ---: | ---: | ---: |
| EQ, full By Now, 1x | 97.088 ms | 16 | 114.096 ms |
| Full chain, full By Now, 1x | 116.597 ms | 16 | 116.607 ms |
| Full chain, **30 s excerpt**, 4x | 207.679 ms | 5 | 220.653 ms |

Final installed functional acceptance passed: 49 probes, including 326 JUnit
tests against installed production classes. This includes the actual context
regression (exclusions, manual tempo, re-enable after completion), Auto Gain UI,
640 EQ combinations, source races, waveform/pan/zoom, A/B isolation, all four
private songs, dynamics/clips/limiter routing and export/SRC. Evidence:
`dist/installed-audit-functional-20260928-184913-3bd20278/`.
Final macro matrix passed all 48 combinations:
`dist/installed-audit-macromatrix-20260928-184923-4980ea79/`.
Both final summary JSONs are copied into the task evidence directory.

All four final isolated memory/performance/A-B probes passed:
`dist/installed-audit-performance-20260928-185931-77259225/`.
No other acceptance or benchmark process ran concurrently. These are whole-plan
publication timings, distinct from the interactive device measurements above:

- Three-edit median, Leveler: 1.456696 s; complete chain: 6.047273 s.
- First identical B: 11.406 ms, without rendering. Cached A: 4.047–7.273 ms.
  Uncached B median: 5.914844 s; independent cold reference bit-identical.
- Thirty completed full-chain edits: retained heap after GC 1329.178–1329.215
  MiB, final six-edit burst 1329.217 MiB. No growing analysis queue; final PCM
  bit-identical. Forced GC is diagnostic-only, not part of application behavior
  or the separate timing probes.

The final performance summary is copied into the task evidence directory.
Closing checks reconfirmed all 201 installed files, application and DSPark JAR
hashes, and the unchanged original hashes of all four private songs. No owned
test Java/QuickMaster process remains. `git diff --check` passed. The existing
dirty primary checkout was not overwritten; work remains in the integration
checkout. No commit, push, release, tag change or source-audio distribution was
performed. There are no remaining required steps for this scoped EQ audition
change; the stated preview and high-oversampling full-render limits still apply.
