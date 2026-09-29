<p align="center">
  <img src="docs/icon-master.png" alt="QuickMaster logo" width="96" />
</p>

<h1 align="center">QuickMaster</h1>

<p align="center">
  <strong>Intelligent offline mastering. Whole-track insight. Hands-on control.</strong><br />
  Shape tone, dynamics and stereo image in a portable desktop app powered by DSPark.
</p>

<p align="center">
  <a href="https://github.com/CristianMoresi/QuickMaster/releases/latest"><img src="https://img.shields.io/github/v/release/CristianMoresi/QuickMaster?style=flat-square&amp;color=4499ff" alt="Latest release" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-64c8bc?style=flat-square" alt="MIT license" /></a>
  <a href="#download"><img src="https://img.shields.io/badge/platforms-Windows%20%7C%20macOS%20%7C%20Linux-555?style=flat-square" alt="Windows, macOS and Linux" /></a>
  <a href="#build-from-source"><img src="https://img.shields.io/badge/runtime-Java%2025%20bundled-e8943a?style=flat-square" alt="Java 25 bundled in portable downloads" /></a>
</p>

<p align="center">
  <a href="https://github.com/CristianMoresi/QuickMaster/releases/latest"><strong>Download</strong></a> ·
  <a href="#quick-start">Quick start</a> ·
  <a href="#the-processing-chain">Features</a> ·
  <a href="CHANGELOG.md">Changelog</a>
</p>

![QuickMaster — EQ, Stereo Image, dynamics, metering and processed waveform](docs/gui.png)

QuickMaster analyses the entire song to shape its tone, dynamics and stereo image before export. That whole-track view lets it anticipate peaks, balance quieter passages and calibrate processing to the audio — without setting up a DAW session.

Choose how much processing you want. QuickMaster handles the analysis; you keep creative control.

## Download

**[Get QuickMaster for Windows, macOS or Linux →](https://github.com/CristianMoresi/QuickMaster/releases/latest)**

Download your system's ZIP, extract it and launch QuickMaster. **No installer or separate Java installation needed.**

## Quick start

1. **Load file** — open a WAV or MP3 for analysis.
2. Enable the modules you need and adjust their amount. Drag the chain chips to change the processing order.
3. Compare settings with **A/B**, check the original with **Bypass**, and check stereo compatibility with **Listen in mono**.
4. Set the output ceiling and **Export**, or use **Batch…** to process several songs with your chain.

## The processing chain

### EQ

Parametric and dynamic EQ with per-band **Stereo, Left, Right, Mid or Side** routing. Shape the whole mix, a single channel or just its centre or stereo information. **Auto EQ** moves the measured spectrum toward a selected tonal curve, with an Amount control for how strongly it follows that target. Optional **Auto Gain** compensates EQ level changes and manages headroom without clipping. Audition band edits while whole-track processing updates in the background. The analyzer uses a display-only **4.5 dB/octave tilt** around 1 kHz.

#### A compressor hiding inside the EQ

There is a deliberate trick in the **Gain** band. On its own it applies flat wideband gain, up or down. Turn on **Dynamic EQ** for that band and it becomes a full, hand-tunable compressor:

- Above the threshold it compresses like a classic downward compressor, with its own attack, release and ratio.
- Below the threshold it can lift the quiet parts (upward compression) or duck them (a gate).
- Both behaviours can act at the same time, each with independent timing.

So if you want a detailed, manually dialled compressor instead of the automatic ones, you already have one: the EQ Gain band.

### Stereo Image

- **Generation** combines moving differential EQ, saturation and harmonic generation to create new stereo information. Only the processing difference is mixed into the original — it does not simply turn up the existing Side.
- **Generated Stereo Low Cut** filters only that added stereo, from **Off to 5 kHz**, with linear-phase filtering. The original bass stays untouched.
- **Side Leveler** raises or lowers Side toward a Mid/Side energy balance; Amount sets how strongly it regulates the song. **Side Guard** reduces Side when it exceeds that reference plus your chosen margin. Choose a genre profile, the track's own balance or a reference file.
- **Side Gain** gives you independent control of the final Side level.

Generation, Side Leveler and Side Guard are independently switchable and start off.

### Dynamics

Four automatic processors, each working at a different musical scale. Their controls specify the result you want; analysis prepares the thresholds and gain envelopes:

- **Peak Comp** measures the highest peak and sets its ceiling from your chosen reduction: **−2 dB asks for 2 dB off the highest peak**, with other peaks above that ceiling reduced too. Look-ahead and automatic release smooth the correction. The available range is based on how far the peaks rise above the sustained body.
- **Beat Comp** detects beat transients and brings the stronger ones toward the typical beat level. You choose the **maximum reduction in dB**, not a threshold: **−2 dB allows up to 2 dB of attenuation**, with less correction for smaller differences. Release follows a selected note value at the detected or manually entered tempo.
- **Leveler** raises quieter sustained passages toward the strongest sustained RMS level, without turning louder passages down. **Leveling** sets how closely they converge and **Speed** controls the response; short-term transients retain their shape. Use **Exclude regions** to paint intros, breaks or outros you want to preserve on the waveform.
- **Punch** lifts detected attacks by the selected **boost in dB**, bringing out impact without continuously raising the body of the song.

Because QuickMaster sees the complete track, you do not need to play through it looking for the loudest peak before setting these controls.

### Clip & Limit

**Soft-Clip** and **Hard-Clip** let you specify **how many dB to clip off the track's highest peak**, rather than guessing an input-drive setting. Set **2 dB** and whole-track analysis calibrates the curve for a 2 dB program-peak reduction. Soft curves round peaks and add harmonic colour; the Hard curve cuts at a ceiling and leaves samples below it unchanged. Zero is bypass. The GR meter reports the rendered reduction; oversampling can change reconstructed peaks.

**Limit** runs a four-band linear-phase limiter into a broadband limiter, so you can control individual frequency ranges before catching the combined peaks. Its **Push** controls add drive into the measured ceiling; unlike Clip, Push is not a fixed gain-reduction target. The meter shows the actual limiting, including contributions from the preceding bands.

The final **Peak Normalizer** sets your delivery ceiling in **dBTP**. Select up to **16× oversampling** to reduce aliasing in nonlinear processing, then choose the export sample rate and bit depth.

## Listen, compare, export

- **Processed waveform**, LUFS, true peak, correlation and Mid/Side meters.
- **A/B** settings, original-signal **Bypass**, and playback-only **Listen in mono**.
- Waveform **scroll to pan**, **Ctrl + scroll to zoom** (Command on macOS), looping and non-destructive trimming.
- Reusable **presets**, undo/redo and **batch export**.
- **WAV and MP3** import/export, sample-rate conversion and 16/24-bit dither. Exports discard all source tags and create only a UTC export timestamp and “Made with QuickMaster by Cristian Moresi”.

## Build from source

Use **JDK 25** and Maven. Build the bundled DSPark Java library, then launch QuickMaster:

```bash
mvn -f vendor/dspark-java/pom.xml clean install
mvn clean javafx:run
```

For tests, official reference signals and portable packaging, see [Validation & delivery](docs/VALIDATION.md). The [changelog](CHANGELOG.md) records release changes; the [stereo reference study](docs/research/stereo-study-20260928/INFORME-RESULTADOS.md) documents the genre measurements.

## Author & DSPark

I'm **[Cristian Moresi](https://github.com/CristianMoresi)**, a backend developer, audio-software developer and music producer. QuickMaster is built on a Java port of **[DSPark](https://github.com/CristianMoresi/DSPark)**, my C++ DSP library, with whole-track analysis designed for offline mastering.

Open source under the **[MIT License](LICENSE)**.
