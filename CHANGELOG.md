# Changelog

All notable changes to QuickMaster are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and the project follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
