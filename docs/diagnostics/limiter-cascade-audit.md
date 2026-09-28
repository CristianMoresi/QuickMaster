# Limiter cascade audit and 1.3.2 delivery plan

Status: 1.3.2 published and delivered; all installed acceptance passed.
Erroneous release 1.3.1 removed after the replacement was verified; its tag and
verified local backup are retained. Completed 2026-09-28 13:36 +02:00.

## Reproduction and cause

Installed baseline: 08ff1ec, JAR
`0000ce9c21a4c38f47c361ea41e3762cfd50b22622240a9b9cef728f26f9079a`.
Read-only By Now, broadband Push 3 dB, all bands at 0/1/3/6 dB.
The actual route is multiband -> broadband -> optional Peak Normalizer.
There is no control assignment from a band knob to the broadband knob.
At 0 -> 3 dB multiband, broadband's measured input reference follows the
multiband output from -0.1 to +2.9 dBTP. The normalizer then removes that gain;
normalized RMS actually falls from -12.399035 to -12.493369 dBFS. Broadband
mean GR drops from 0.024139 to 0.012099 dB although its requested Push stays 3.

The Java offline processors use `LimiterEnvelope`, not DSPark's realtime
`Limiter`. Its 80 dB-per-window slew makes a 3 dB attack last only 3/80 of
the stated 1.5 ms, and recovery only 3/80 of 80 ms. This is not a calibrated
lookahead / release. C++ DSPark main 9e4bba6 was inspected read-only: its current
gain computer uses a sliding minimum, one-pole release and double moving
average attack. Updating unused realtime classes would not fix this app route.

## Implementation plan and gates

1. Keep the two-stage route explicit. Anchor broadband's limiting ceiling to
   the **Limit module input**, before multiband, while detecting/reducing the
   actual post-multiband signal. A standalone broadband stage uses its own
   input reference. Recompute the anchor with source/upstream changes; never
   carry one from an unrelated track or previous render.
2. Push is input drive, not a promise of a fixed instantaneous GR. The knob
   never changes when bands change; real broadband GR may exceed its Push
   when multiband has already added drive. Zero broadband Push must still
   contain an active multiband signal above the shared ceiling. No meter cap
   may hide the actual attenuation. Keep final true-peak normalization separate.
3. Replace the application's legacy envelope with an offline, source-aligned
   minimum-hold / smooth-lookahead / calibrated-release gain computer. Preserve
   stereo linking, sample bounds, source alignment, cancellation and bounded
   memory. Audit FIR reconstruction and verify final true peak independently.
4. Red/green tests: band changes keep the ceiling and requested broadband Push;
   serial versus explicit staged rendering; bypass/zero/finite parameters;
   cold versus cached, source changes, A/B and real UI; sample-rate/channel/
   oversampling and block partition coverage. Quantify real songs separately
   from synthetic bounds. Do not promise monotonic instantaneous GR for every
   possible multiband waveform (phase cancellation can legitimately change it).
5. Complete DSPark and application suites, version 1.3.2 package, Java 25
   portable, Program Files deployment, matching files and clean installed EXE
   startup; installed musical/UI/performance gates before publication.
6. Commit with Cristian Moresi as sole author, push the verified history to
   remote main without touching the old dirty primary checkout. Prepare and
   verify the new release assets before removing erroneous release v1.3.1.
   Preserve its metadata/assets for recovery; no other release or tag deletion.

## Sources

- [DSPark C++ Limiter, pinned source](https://github.com/CristianMoresi/DSPark/blob/9e4bba6b3241efebcb932a9199bd6399207e0096/Effects/Limiter.h)
- Local DSPark Java `LimiterEnvelope`, `MultibandCrossover`, `TruePeak` and
  QuickMaster's limiter wrappers/pipeline; executable measurements take
  precedence over comments describing the intended behavior.

## Preflight results and explicit limits

- Three new cascade tests fail on the previous code: moving/overshooting
  ceiling, zero Push incorrectly bypassing multiband peaks, and non-finite
  broadband parameters accepted. They pass after the correction.
- The new application-owned `OfflineLimiterEnvelope` is tested against a
  separate, deliberately brute-force window/convolution oracle. Attack spans
  the full 1.5 ms; release is an 80 ms time constant, tested at 44.1/48/96 kHz
  and 1/3/9/18 dB depth. No change to the pinned DSPark JAR is required:
  crossover and official true-peak kernels remain the audited 0.2.1 bytes.
- The old make-up test placed impulses every 25 ms but required >10% RMS gain,
  depending on the incorrect ~4 ms effective release. Its isolated-peak fixture
  now uses 250 ms spacing and keeps the same >10% assertion. A separate dense
  25 ms train is compared sample by sample to the calibrated oracle; no claim
  is made that an 80 ms release fully recovers between 25 ms transients.
- Final output tests cover 30 combinations of 44.1/48/96 kHz, mono/stereo and
  1/2/4/8/16x oversampling, including a last-frame transient. Independent
  measurement verifies -1 dBTP after final normalization and unchanged length.
- The base-rate broadband float waveform is re-measured, including FIR tail,
  after applying its envelope. Any new ISP excess receives a small disclosed
  scalar safety trim; meters include it. The normalizer still measures final
  post-decimation/post-SRC PCM. The sidechain alone is not advertised as a
  mathematical bound on every possible DAC reconstruction.
- By Now: broadband Push remains 3 dB at all band settings. With bands 0 ->
  3 dB, normalized RMS changes -12.944009 -> -11.789786 dBFS, an improvement of
  1.154223 dB. Broadband deepest GR changes ~3 -> 5.98 dB: that is real
  attenuation of the additional band drive, not a rewritten Push knob.
- Actual asynchronous FXML UI: fixed reference, controls unchanged, exact
  cold-render PCM, cached A/B, latest of rapid edits, -1 dBTP and unchanged
  source hash all pass. Screenshot inspected. No human listening is claimed.

## Complete build

- Application: 841 tests in 125 suites, zero failures/errors/skips; all 106
  musical regression variants passed. Clean package completed 2026-09-28
  02:10:45 +02:00 (28:33 minutes).
- DSPark dependency: 129 tests passed, unchanged audited JAR hash.
- Official file-loudness profile: 94 ITU/EBU readings passed. The bound report
  was re-opened and verified from the packaged JAR; this is not a claim of
  complete EBU Mode, live, LRA or true-peak certification.
- Version 1.3.2 core JAR:
  `ca98526f824cb76fd6e984e0fcde766bc6f120592a42646a6f34d9b7fac64ad4`.
  `VerifyReleaseJar` independently confirms hash, version and bound evidence.

## Delivery checkpoint — 2026-09-28 02:14 +02:00

Historical checkpoint, resolved by the user's authorization to retry below.

- The complete Windows Java 25 portable was generated at
  `dist/limiter-1.3.2-20260928/image/QuickMaster`.
- Windows canceled the UAC elevation before `Deploy-Portable.ps1` could run.
  The deployment was not retried or bypassed. The installed JAR remains
  `0000ce9c21a4c38f47c361ea41e3762cfd50b22622240a9b9cef728f26f9079a`.
- Candidate-package limiter probes on By Now, Quiet Gold, Billie Jean and Wicked Game
  passed all four band-drive settings with fixed reference, unchanged broadband
  Push and finite output. These checks do not substitute for installed acceptance.
  All 201 source-image files are present; `IMAGE_VALIDATED` is a source-image
  check, not a successful deployment receipt.
- No commit, push, new tag/release, or previous-release removal has occurred.
  All five previous release assets and metadata remain backed up in
  `dist/release-backup-v1.3.1-20260928`, with verified SHA-256 digests.

Resume only with user direction to retry elevation. Run the deployment with
the core hash above, then compare the installed image, launch the installed EXE
and confirm clean startup. Run `Verify-InstalledAudit.ps1` Functional (including
the four limiter songs and UI), MacroMatrix, and Performance (alone). Archive
results and inspect the installed UI screenshot. Then finish the release plan
above: sole-author commit/push to main, draft/core upload, version tag and CI,
verify three bundles, deliver/launch the downloaded Windows bundle, publish
1.3.2, and only then remove release 1.3.1 while preserving its tag and backup.

## Resumed delivery — 2026-09-28 13:16 +02:00

The user authorized retrying Windows elevation. Deployment succeeded, retaining
the previous image at `C:/Program Files/QuickMaster-backup-20260928-131527`.
All 201 installed files match the generated portable; installed JAR SHA-256
matches the release record. Both elevated and independent normal-user EXE
launches produced clean startup logs. Installed functional/musical acceptance
has completed; no release has been published or removed yet.

- Functional acceptance: all 34 probes passed, including 291 tests executed
  against the installed JAR, four real-track limiter probes (16 drive cases),
  async UI/cold-render equality, A/B, rapid edits, source hashes, waveform,
  exclusions, export and oversampling ceilings.
- The installed limiter UI repeats the measured By Now result exactly:
  bands 0 -> 3 dB at broadband Push 3 dB increase normalized RMS by 1.154223 dB
  while retaining -1 dBTP. The installed screenshot was visually inspected.
- Macro regression: all 48 track/amount/speed combinations passed.
- All four performance probes passed, run separately without another suite.
  Thirty repeated full-chain edits retain 1328.417–1328.437 MiB after GC;
  latest published audio is raw-bit identical to a fresh controller/render.
  Leveler-only repeated-edit audio median: 1.444136 s. Full-chain median:
  7.168189 s (three samples: 12.363139, 7.168189, 6.113353 s).
  First identical B: 0.009973 s; cached A: 0.004335–0.006587 s. Uncached B:
  median 7.537144 s (6.212419–8.458694 s), with exact cold-reference equality.
- These uncached full-chain/A-B timings are higher than the previous delivery's
  4.758/4.638 s medians. This is not a controlled same-run before/after benchmark,
  so no isolated attribution is claimed. The corrected zero-Push broadband
  stage now actually contains multiband peaks and verifies rendered true peaks;
  fidelity was not traded away to meet a timing figure. Cached switching remains
  immediate; no uncached speed-up or human listening is claimed.
- Complete logs, summaries, screenshot and measured timing samples are under
  `limiter-cascade-evidence/installed/`; build/preflight evidence is alongside it.

## Published release and final Windows delivery

- [QuickMaster 1.3.2](https://github.com/CristianMoresi/QuickMaster/releases/tag/v1.3.2)
  is public/latest, with three portable ZIPs, the qualified core JAR and SHA-256
  checksums. Tag source: `c83e1e16374ad75f0002258293d974a583654122`, pushed to
  `main` with Cristian Moresi as sole author/committer.
- [Release workflow 36416101957](https://github.com/CristianMoresi/QuickMaster/actions/runs/36416101957)
  passed Windows, macOS and Linux packaging and bound-JAR checks. All three
  downloaded ZIPs match GitHub asset digests and contain the exact qualified
  core and DSPark JARs. CI used Temurin 25.0.4.1 runtime; the audited core was
  compiled/tested using the pinned local Temurin 25.0.4.7 build.
- The **downloaded Windows release**, not merely the local staging image, is
  now in `C:/Program Files/QuickMaster`. All 201 files match its ZIP; all 19
  application/dependency JARs also match the locally tested portable. Both
  elevated and independent normal-user EXE starts are clean. Backup of the
  preceding local image: `C:/Program Files/QuickMaster-backup-20260928-133426`.
- Release 1.3.1 (ID 397553232) was deleted only after 1.3.2 publication and
  asset verification. Its API now returns 404; historical tag `v1.3.1` remains.
  All five old assets and metadata are recoverable from
  `dist/release-backup-v1.3.1-20260928` and were rechecked before deletion.
- Receipts, bundle hashes and clean-startup logs are under
  `limiter-cascade-evidence/release/`. Native interactive macOS/Linux operation
  and human listening were not performed. CI reports maintenance warnings for
  older checkout/setup-java actions; all packaging jobs completed successfully.
