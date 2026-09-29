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

Parametric and dynamic EQ with per-band **Stereo, Left, Right, Mid or Side** routing. **Auto EQ** shapes tone toward a selected target curve; optional **Auto Gain** compensates level changes and manages EQ headroom. Audition band edits while whole-track processing updates in the background.

#### A compressor hiding inside the EQ

There is a deliberate trick in the **Gain** band. On its own it applies flat wideband gain, up or down. Turn on **Dynamic EQ** for that band and it becomes a full, hand-tunable compressor:

- Above the threshold it compresses like a classic downward compressor, with its own attack, release and ratio.
- Below the threshold it can lift the quiet parts (upward compression) or duck them (a gate).
- Both behaviours can act at the same time, each with independent timing.

So if you want a detailed, manually dialled compressor instead of the automatic ones, you already have one: the EQ Gain band.

### Stereo Image

- **Generation** combines moving differential EQ, saturation and harmonic generation to create new stereo information. Only the processing difference is mixed into the original — it does not simply turn up the existing Side.
- **Generated Stereo Low Cut** filters only that added stereo, from **Off to 5 kHz**, with linear-phase filtering. The original bass stays untouched.
- **Side Leveler** balances Side energy relative to Mid; **Side Guard** controls excessive width. Choose a genre profile, the track's own balance or a reference file.
- **Side Gain** gives you independent control of the final Side level.

Generation, Side Leveler and Side Guard are independently switchable and start off.

### Dynamics

Four automatic processors, each working at a different musical scale:

- **Peak Comp** controls short peaks.
- **Beat Comp** reduces strong transients with tempo-aware timing.
- **Leveler** raises quieter passages toward the strongest sustained RMS level. Use **Exclude regions** to paint intros, breaks or outros you want to preserve on the waveform.
- **Punch** brings out attacks.

Set the amount; whole-track analysis prepares the gain envelopes.

### Clip & Limit

**Soft-Clip** and **Hard-Clip** shape peaks and add colour, calibrated to your requested reduction. **Limit** combines a four-band linear-phase limiter with a broadband stage in series. The final **Peak Normalizer** sets your delivery ceiling in dBTP. Select up to **16× oversampling** for nonlinear processing.

## Listen, compare, export

- **Processed waveform**, LUFS, true peak, correlation and Mid/Side meters.
- **A/B** settings, original-signal **Bypass**, and playback-only **Listen in mono**.
- Waveform **scroll to pan**, **Ctrl + scroll to zoom** (Command on macOS), looping and non-destructive trimming.
- Reusable **presets**, undo/redo and **batch export**.
- **WAV and MP3** import/export, sample-rate conversion, 16/24-bit dither and same-format metadata preservation.

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
