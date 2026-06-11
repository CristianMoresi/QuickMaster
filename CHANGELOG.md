# Changelog

All notable changes to QuickMaster are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and the project follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
