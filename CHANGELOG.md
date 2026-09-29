# Changelog

All notable changes to QuickMaster are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and the project follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.3.4] - 2026-09-29

### Changed

- EQ spectrum display uses a 4.5 dB/octave tilt around 1 kHz in live and
  stopped views. Audio, EQ response, Auto EQ and metering remain unchanged.
- MP3 import uses the DSPark MPEG-1 float decoder port, preserving quiet
  detail and floating headroom. Validated gapless metadata removes delay and
  padding; malformed/truncated streams are rejected. MPEG-2/2.5 retain a
  separate float-output compatibility path. Public import/export APIs and
  MP3 encoding are unchanged.
- README explains the dB targets of Peak Comp, Beat Comp and Clip more clearly.

## [1.3.3] - 2026-09-29

### Added

- Stereo Image, before Dynamics by default: independent differential-EQ
  Generation, offline Side Leveler and Side Guard. Generation adds new Side
  while preserving Mid, with automatic delta-only DSPark harmonics. All three
  sections start off; Generation's initial/reset amount is 25%.
- Generated Stereo Low Cut, Off by default through 5 kHz, using a compensated
  linear-phase filter only on the generated difference. Side Gain independently
  adjusts the final combined Side from -12 to +12 dB.
- Auto (This Track), ten measured genre-energy references and local reference
  import. The documented study covers 50 recordings across ten families; it
  does not claim an ideal universal stereo balance. Presets, undo, cached A/B,
  chain order, processed waveform and output energy include Stereo Image.
- Listen in mono below Mid/Side: smoothly switched playback-only half-sum for
  preview, Bypass and both A/B slots, with no reanalysis or export changes.
- Interactive EQ and Stereo Image audition through a separate, cancellable
  short-window worker while the exact whole-track master is recalculated.
  Phase-aligned previews are labelled as provisional and never used for export
  or the definitive A/B cache.

### Fixed

- Optional EQ Auto Gain uses the updated DSPark K-weighted port and a fixed
  offline gain, preventing EQ-only overload without saturation. Its on/off state
  survives presets, A/B and undo/redo; processed waveform and meters use the
  same compensated audio. Final delivery protection stays scoped to EQ, without
  silently normalizing new stereo generated after it.
- Keep the EQ Auto Gain readout blank when no compensation is displayed, instead
  of a misleading off/zero status. Actual and provisional gains remain visible.
- Source/A-B/final-render barriers reject stale preview windows; tempo and
  Leveler-exclusion changes invalidate the prepared context.
- Correct weak stereo generation and misleading empty-meter states. Keep
  passing-audio measurements active when all sections are off and show explicit
  overload warnings instead of silently attenuating the full mix.
- Peak Comp contains boundary peaks with its complete offline attack and
  calibrated release; the shared forward-minimum no longer retains stale tail minima.
- Punch uses DSPark SuperFlux plus waveform attack refinement, retaining quiet
  attacks without the stationary-tone false positives of local flux normalization.
- Clip calibration is continuous near zero, level-invariant and free of hidden
  slew, stereo drift, DC filtering and first-sample averaging. GR meters measure
  rendered stage audio rather than a knob-capped estimate.

### Changed

- Cache full-quality stereo synthesis and filtering across compatible edits,
  with bounded memory, cancellation and exact cold-render equivalence. Remove
  the duplicate generation-only analysis pass; the initial render still needs
  offline computation.
- Align Stereo Image controls and remove slider-value popup dialogs. Generate
  all frequencies by default; old presets retain their former bass filtering.
  Output headroom protection remains opt-in through Limit / Peak Normalizer.
- Soft-Clip uses native-verified symmetric DSPark curves: Analog, Soft (tanh)
  and Golden knee. Legacy Tube/Tape/Transformer preset IDs map to those slots;
  the sound intentionally changes from the old asymmetric Java models.
- Hard-Clip exposes its saved Hard/Soft curve. Nonlinear antialiasing uses the
  explicit pipeline oversampling setting; 1x does not claim alias-free output.
- DSPark Java 0.2.3 includes K-weighted Auto Gain, canonical clipping and offline
  SuperFlux with compiled-native reference tests.

Validation of the Windows development build on 2026-09-29: 921 application
tests, 140 DSPark tests and 371 additional installed-JAR tests passed, plus
installed UI/audio checks. See [delivery evidence](docs/diagnostics/mono-monitor.md).
Release packaging is separately verified against the exact validated core JAR;
see the [1.3.3 release validation](docs/releases/1.3.3-validation.md).

## [1.3.2] - 2026-09-28

### Fixed
- Multiband now feeds the broadband limiter against a shared pre-multiband
  peak reference. Band drive no longer raises the broadband ceiling and then
  gets cancelled by output normalization. Broadband Push remains independent;
  its GR meter reports actual attenuation, including upstream band drive.
- Offline limiting uses smooth full-lookahead attack and calibrated release,
  with verification of the resulting float true peaks, including file tails.
- A/B preparation reuses only exact approved audio and invalidates both slots
  after shared tempo or Leveler exclusion changes.

### Added
- Upward-only macro RMS Leveler with editable red waveform exclusions,
  undo/redo, per-source persistence and crop/delete-aware region mapping.
- Waveform follows the published processed audio, including limiting; Bypass
  displays the original and cached A/B switches restore the matching waveform.

### Changed
- Portable packages use the validated core JAR and Java 25 runtime. This release
  supersedes the withdrawn 1.3.1 release; it also includes the intervening DSP,
  playback, export and analysis corrections verified in the local product audit.

## [1.3.1] - 2026-09-27

### Added
- Horizontal waveform navigation: scroll to pan the view without affecting
  playback; Ctrl + wheel (Command on macOS) zooms around the pointer.

### Changed
- The offline Leveler compares sustained, musically similar sections and uses
  smooth, bounded gain transitions. It protects intros, outros, breaks and
  build-ups, and leaves ambiguous or peak-unsafe material unchanged.
- Fade-curve editing now uses Alt + wheel over a fade handle, leaving the plain
  wheel available for waveform navigation.
- Portable releases share the exact JAR validated against the official loudness
  test signals. Packaging checks its SHA-256, version and bound conformance
  report; test audio and personal authorizations are not distributed.
- **DSPark Java 0.2 updates the kernels used by QuickMaster**, with compiled C++
  reference checks for FFT and filter responses. It improves FFT, polyphase
  decimation, smoothing and true-peak processing while preserving existing EQ
  voicing and custom offline dynamics. This does not claim complete feature
  parity with DSPark C++. Loudness measurement now uses the exact ITU-R
  BS.1770-5 K-weighting (readings were about 0.26 LU low before) with the
  loudness range sampled per the EBU Tech 3342 cadence, and true-peak detection
  uses the official BS.1770-5 Annex 2 interpolator everywhere (meters,
  normalizer and limiter share one implementation). The sample-rate converter
  fixes a sub-sample timing error in its kernel and gains per-quality
  anti-alias windows, so rate-converted exports are cleaner. Dither now shapes
  the total requantization error and lands exactly on the integer grid of the
  target bit depth. Parameter and signal validation rejects non-finite values.
  The DSPark suite contains 122 tests; the packaged application passes 94 official
  ITU/EBU file-loudness measurements.
- Updated audio becomes available before output statistics finish. Analysis is
  bounded and cancelable, obsolete edits are superseded, bypassed stages avoid
  unnecessary work, and rendering reuses buffers and exact-content caches.

### Fixed
- Beat Comp respects its maximum gain reduction, including parameter changes
  while analysis is pending, with continuous stereo-linked processing.
- Tempo detection no longer cancels out-of-phase stereo attacks or overweights
  weak subdivisions. Uncertain estimates are marked `auto?`; incompatible
  tempo sections do not force a false global BPM, and manual BPM remains available.
- Leveler analysis avoids unnecessary allocations and uses sparse gain schedules
  to keep memory usage bounded for long tracks.
- Leveler no longer protects an entire song because a sustained plateau was
  mistaken for a continuous build-up. Comparable body sections of different
  lengths can be corrected while intentional macro-dynamics remain protected.
- Rapid edits no longer accumulate full-track analysis jobs or exhaust the
  validation heap. Audio publication and meter updates reject stale results.
- Failed replacement loads retain the editable current track and manual tempo;
  Stop cancels pending Play, and closing prevents late analysis publication.
- Auto EQ cache keys include every PCM sample and the audio format. Fade shapes
  are retained in processing snapshots.

## [1.3.0] - 2026-07-21

### Added
- **Proportional whole-window scaling on Windows.** QuickMaster keeps its fixed
  1360 x 830 design ratio and scales the complete interface as one unit from any
  native edge or corner, with DPI-aware limits for high-resolution displays.
- **Exact Peak Normalizer target entry.** Double-click the target slider or its
  value to type a dBTP ceiling; Shift-drag uses 0.1 dB steps and Ctrl-click resets
  the control to its default.

### Changed
- **The Leveler now preserves musical dynamics.** It ignores short events, leaves
  sections close to the song median untouched, rolls off boosts for intentionally
  quiet passages, never pumps a fade or outro back up, and caps boosts so leveling
  cannot raise the track peak or consume the normalizer's headroom.
- The Peak Normalizer's default delivery ceiling is now **-0.1 dBTP**. Lower
  ceilings such as -1 dBTP remain available when lossy-codec safety margin is
  preferred.
- The main layout is wider and more compact, with icon-only undo/redo controls and
  a centred Correlation/Mid/Side meter panel. Its full labels and persistent English
  tooltips explain how to interpret stereo phase and Mid/Side energy.
- Individual and batch WAV exports now default to **48 kHz / 24-bit** instead of
  inheriting the source encoding; every previously supported format remains selectable.
- Individual and batch exports keep each source file's basename instead of
  appending `-mastered`; choosing another output format changes only the extension.
- **Export, preset and file dialogs now start on your desktop** instead of the
  process working directory, resolved per platform: `%USERPROFILE%\Desktop` or its
  OneDrive redirection on Windows, `~/Desktop` on macOS, and the XDG desktop
  directory (including localised names such as `~/Escritorio`) on Linux, falling
  back to the home directory where no desktop exists. The folder of the last
  export is still remembered, and is replaced by the desktop when it no longer
  exists. Configurations written by earlier versions are migrated once.
- JavaFX is updated to the latest JavaFX 21 LTS patch release (21.0.11).

### Fixed
- Live proportional resizing no longer flashes black or clipped frames. During a
  resize, a stable snapshot represents the interface and the live scene is restored
  immediately when the pointer is released.
- Corner resizing no longer oscillates between horizontal and vertical sizes; the
  pointer is projected continuously onto the window's aspect-ratio diagonal.
- Native Windows sizing now converts correctly between JavaFX logical coordinates
  and Win32 physical pixels, including 200% display scaling on 5K monitors.
- Moving the window without resizing no longer hides or clips the bottom controls.
- LUFS, LRA, true peak, correlation, Mid/Side levels and the stopped-state spectrum
  are now measured exclusively from the fully post-processed render. Bypass no longer
  switches live meters to the raw source, Normalizer edits trigger a fresh output
  analysis, and stale background measurements cannot overwrite newer results.
- Batch export retries transient source I/O failures, isolates failures per song
  so the rest of the folder continues, and reports a final per-file error summary.
- Application logs now retain complete nested exception causes, and batch logs
  identify every file as it starts and completes.
- WAV loading now selects the runtime's dedicated WAV readers directly, preventing
  mp3spi from aborting valid float WAV files with `Resetting to invalid mark`.

## [1.2.3] - 2026-06-23

### Fixed
- **The A/B settings comparison now works: instant, gapless, no re-analysis.** The single
  "Set A"/"Set B" toggle (whose label changed on every click and was easy to misread as a command)
  is replaced by a joined **A | B** switch where exactly one slot is highlighted as active.
  Switching stores the edits you are leaving in their slot and recalls the other, so neither
  configuration is lost. Each slot is rendered once to a buffer (the first time you compare it,
  shown behind the progress overlay); after that, toggling A/B hands the player the other slot's
  render instantly - no playback stop, no several-seconds re-analysis. Editing a slot drops its
  render and returns to live processing so the edit is heard at once. Undo/redo and loading a
  preset also keep playback running from the same position instead of resetting to the start.

## [1.2.2] - 2026-06-11

### Changed
- **The transport's comparison toggle is now called "Bypass"** - it bypasses the chain to hear
  the original audio, so it now says exactly that, leaving "Set A"/"Set B" (top bar) as the only
  control named A/B: the one that switches between two complete chain configurations.

## [1.2.1] - 2026-06-11

### Changed
- **Batch export is folder-based** - pick the folder with the songs (instead of a barely
  discoverable multi-file selection) and one dialog now states everything up front: the source
  folder and song count, the destination folder (visible and changeable, defaulting to a
  "mastered" subfolder), the encoding, the output naming (`<name>-mastered.<ext>`) and that the
  chain is applied exactly as currently configured.
- **The two A/B controls are now distinguishable** - the top-bar settings toggle reads
  "Set A"/"Set B" and both it and the transport's A/B carry tooltips: the transport A/B compares
  the processed master against the ORIGINAL audio, while Set A/B switches between two complete
  chain configurations to compare two masters.

## [1.2.0] - 2026-06-11

### Fixed
- **Auto EQ with oversampling produced corrupted audio** - the pre-rendered Auto EQ output was
  indexed by high-rate frame positions, so at any oversampling factor it played back sped up and
  then dropped out entirely. The render is now positioned by time (with smooth interpolation),
  so Auto EQ is correct at every oversampling factor, live and on export.
- **Active multiband limiter shifted the master by ~23 ms** - the linear-phase crossover's
  latency was reported as zero at the moment the offline renderer asked for it, so every export
  with the Limit module on came out delayed by the crossover length and lost its tail. The
  crossover is now prepared eagerly and its latency always compensated; a regression test
  renders through the full pipeline and asserts sample alignment.
- **EQ edits now re-analyse the chain** - dragging a band, using the wheel on the curve, editing
  band knobs, adding/removing bands or dragging the fade handles triggers the same debounced
  re-analysis as every other control, so what plays always matches what exports.
- **Playback is latency-compensated** - the cursor and meters now show the audio that is
  actually sounding, the chain's tail is flushed when the song ends (the last instants are no
  longer cut off), and the A/B comparison runs the original through a matching delay so toggling
  no longer jumps in time.
- **Export sample-rate conversion is anti-aliased** - the previous cubic interpolator aliased on
  downsampling; conversion now uses DSPark's polyphase Kaiser windowed-sinc resampler at its
  highest quality, and the true-peak ceiling is re-measured and re-anchored at the delivery rate.
- The Leveler now measures loudness as the per-channel K-weighted power sum (BS.1770) instead of
  a mono fold-down, so wide mixes are no longer underweighted.
- The broadband limiter's true-peak map is aligned with the detector's group delay.
- Oversampled totals no longer overflow on very long, high-rate files (sample counts are 64-bit
  throughout the processing API).

### Added
- **TPDF dither** on every 16 and 24-bit render (WAV and the MP3 encoder feed) and on the
  16-bit monitoring path, replacing truncation distortion with a flat noise floor.
- **Chain presets** - save and load the entire chain as a JSON `.qmpreset` file.
- **A/B settings slots** - two full chain configurations switchable from the top bar, for
  comparing two complete masters of the same song.
- **Undo/redo for parameters** - knob gestures, band edits and module toggles join crop/delete
  in the undo history (one entry per gesture); the history is also bounded by memory so long
  files cannot exhaust the heap.
- **Batch export** - master a set of files with the current chain in one go, each with its own
  tempo/onset analysis, with progress and cancellation.
- **Metadata preservation** - same-container exports keep the source's ID3 tags (MP3) and
  LIST-INFO/bext chunks (WAV).
- **Loop playback** - loop the waveform selection (or the whole track) sample-accurately while
  dialling the chain.
- **Stereo image metering** - phase correlation, M/S levels and a goniometer beside the
  loudness meters; the PEAK readout is now a true-peak (dBTP) measurement, live and offline.

### Changed
- **One-pass analysis** - the whole-chain analysis now renders the file once (instead of once
  per analysis stage), the output meters reuse that same render, and an EQ-unchanged gesture
  starts from a cached post-EQ signal, so parameter changes settle several times faster.
- **Rate-independent linear-phase kernels** - the linear-phase EQ and the multiband crossover
  scale their FIR length with the processing rate (constant time span), so the realized curves
  are identical in Hz at 44.1 to 192 kHz and at any oversampling factor.
- **ADAA clipping** - all four hard-clip curves are antiderivative anti-aliased, so clipping no
  longer folds harmonics back as aliasing even without oversampling.

## [1.1.0] - 2026-06-07

### Added
- **Export progress overlay** - exporting now shows a modal progress box (percentage, elapsed
  and remaining time) with a Cancel button, and locks the rest of the window while the master
  is rendered, so the export cannot collide with playback.

### Fixed
- **Garbled audio when exporting during playback** - export now stops playback and renders on
  an independent copy of the processing chain, so the live audio engine and the offline render
  no longer share state. Previously, exporting while the audio was playing could break the
  real-time output into loud noise.

## [1.0.1] - 2026-06-07

### Added
- **Drag and drop loading** - drop a WAV or MP3 anywhere on the window to load it, as an
  alternative to the "Load file" button. The window highlights while a loadable file hovers.

## [1.0.0] - 2026-06-07

### Added
- **Multi-band EQ** - parametric (bell, low/high shelf, low/high cut at 6-48 dB/oct, notch,
  tilt) with per-band channel routing (Stereo / Left / Right / Mid / Side) and per-band linear
  or minimum phase, over a live FFT spectrum analyser.
- **Dynamic EQ** on any band, including a deliberate trick: a **Gain band** with Dynamic EQ
  turned on becomes a full, hand-tunable compressor. Above the threshold it compresses (attack,
  release, ratio); below it lifts the quiet parts (upward compression) or ducks them (gate); and
  both can act at once, each with independent timing.
- **Auto EQ** - offline, linear-phase spectral match toward a selectable target curve
  (Deep / Brown / Pink / White / Blue); pink is a good start for a pop master.
- **Automatic Dynamics** - four offline look-ahead processors, each precomputed from the
  analysed transients and peak map and recomputed live as upstream stages change: **Peak Comp**
  (tames only the loudest transients, micro level), **Beat Comp** (glue, levels transients beat
  to beat), **Leveler** (evens loudness across sections, macro level) and **Punch** (raises only
  the transients). You dial the result in dB and the analysis derives the rest.
- **Clip** - **Soft-Clip** saturation that shaves peaks by a chosen amount in dB while keeping
  the punch (the peak energy moves into harmonics rather than blunting the attack), with Tube,
  Tape and Transformer colour; and **Hard-Clip** for the same reduction with no colour. Live
  gain-reduction metering.
- **Limit** - automatic two-stage true-peak limiter: a linear-phase multiband stage (four
  bands, per-band Push) and a broadband true-peak brickwall (ITU-R BS.1770), fully oversampled.
- **True-peak Peak Normalizer** - normalizes to a target dBTP ceiling (default -1 dBTP).
- **Movable chain** - drag the chips to reorder the modules (put saturation before or after the
  EQ, move the dynamics earlier or later), reorder the compressors inside Dynamics, and toggle
  any module on or off.
- Selectable oversampling (up to 16x) on playback and export.
- **Portable builds** with a bundled Java runtime for Windows, macOS and Linux (no Java needed);
  settings and logs live in the per-user data directory, so nothing is left in the app folder.
- MIT license, contributor guide and product-facing documentation.
- Audio engine provided by the DSPark for Java DSP library (a port of the DSPark C++ library).

### Changed
- The "Peak Maximizer" module is renamed to **Normalizer** to match what it does
  (peak normalization to a target level).

### Fixed
- Mono files no longer break playback and export - the Mid/Side stage now passes
  non-stereo input through unchanged instead of throwing.
- Export no longer touches the JavaFX canvas from a background thread.
- MP3 export now flushes the final frames and LAME info tag (previously the file was
  silently truncated) and uses the encoder's correct output buffer size.
- The audio thread reads A/B bypass through a dedicated volatile flag rather than a
  JavaFX property.

## [0.1.0]

Baseline desktop application:

- Load and export WAV (16/24/32-bit integer, 32-bit float) and MP3.
- L/R volume, Mid/Side, fade in/out and peak normalization.
- Integrated LUFS measurement (ITU-R BS.1770).
- Real-time streaming playback with play/pause/stop/seek and A/B comparison.
- Interactive waveform with click-to-seek and drag-to-scrub.
- Non-destructive trim, JSON-persisted configuration and application logging.
