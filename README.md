# QuickMaster

**QuickMaster uses whole-track analysis to prepare its processing before playback.** It measures loudness, peaks and spectral content, estimates tempo, and prepares gain envelopes with advance knowledge of the signal. The Leveler follows macro RMS across musical passages; it does not need to classify a passage as a verse or chorus before correcting its level.

From that, it masters: shape tone and stereo image, compress and level dynamics, clip and saturate, and limit to a true-peak ceiling, then export the result without opening a full DAW. QuickMaster is a JavaFX desktop application.

![QuickMaster](docs/gui.png)

Built by **Cristian Moresi**, backend developer, audio-software developer and music producer. It is a young, open-source project and it will keep growing; today it already covers the essentials for quick, intelligent masters.

## Why offline?

A real-time effect only knows the past and the present; it has to guess about the future and pay for any look-ahead with latency. QuickMaster has no such constraint:

- **Precomputed look-ahead.** Offline gain envelopes can anticipate peaks without adding a real-time look-ahead buffer. Analysis takes time up front; filters and the audio device still have their own latency.
- **Whole-file measurement.** Loudness, peaks and the long-term spectrum use the whole track. Onsets and tempo are estimated from that signal, with uncertainty exposed in the UI.
- **Shared processing.** Playback and export use the same processors and position-indexed gain maps. Changing the sample rate or oversampling factor can intentionally change the result; it is not a promise of bit-identical audio across different render settings.

## Using QuickMaster

The chain runs `EQ -> Dynamics -> Clip -> Limit -> Peak Normalizer` by default, but the order is yours: **drag the chips in the processing-chain bar to reorder it** (put the saturation before or after the EQ, move the dynamics earlier or later, whatever suits the track), reorder the individual compressors inside Dynamics, and toggle any module on or off. The Peak Normalizer stays last so it always sets the final ceiling. Here is what each stage is for and how to get the most out of it.

### EQ (and a compressor hiding inside it)

A multi-band parametric and dynamic equalizer: bell, low and high shelf, low and high cut (6 to 48 dB/oct), notch and tilt. Each band can be routed to **Stereo, Left, Right, Mid or Side**, so the same EQ also handles L/R balance, Mid/Side tone and stereo width.

There is a deliberate trick in the **Gain** band. On its own it applies flat wideband gain, up or down. Turn on **Dynamic EQ** for that band and it becomes a full, hand-tunable compressor:

- Above the threshold it compresses like a classic downward compressor, with its own attack, release and ratio.
- Below the threshold it can lift the quiet parts (upward compression) or duck them (a gate).
- Both behaviours can act at the same time, each with independent timing.

So if you want a detailed, manually dialled compressor instead of the automatic ones, you already have one: the EQ Gain band.

**Auto EQ** matches the track's tone toward a target curve. For a typical pop master, pulling toward the **pink** curve is a good starting point; choose deeper or brighter targets (Deep, Brown, White, Blue) to taste. One **Amount** knob sets how strongly the tone is pulled.

### Dynamics (automatic, analysis-driven compression)

Four automatic look-ahead compressors. QuickMaster analyses the track and prepares their envelopes in the background. Upstream changes invalidate affected analysis; superseded requests are cancelled and only the latest completed plan can become current. Each compressor works at a different time scale:

- **Peak Comp (micro-dynamics).** The classic "shave the peaks" compressor: a fast attack and a short release (around 60 ms, short but long enough to avoid distortion). Since the transients are known in advance, it touches **only** the loudest transients and leaves the body untouched.
- **Beat Comp (beat-level).** Turns down transients louder than the median transient, with a stereo-linked gain envelope. The reduction target is a maximum, including while a previous analysis is being replaced. Release follows the selected note value and detected or manual BPM. `auto?` marks an uncertain estimate; disable Auto to enter the intended musical tempo. If no reliable single tempo is available, release falls back to 250 ms.
- **Leveler (macro-dynamics).** Raises quieter sustained passages toward the strongest 3-second RMS in the input, never attenuating them or boosting above that macro RMS reference. **Leveling** scales the intended correction in dB; 100% makes sustained passages converge where the activity floor, +24 dB boost limit and smooth transitions permit. **Speed** changes the macro response (6-second context at minimum, 2 seconds at maximum). It does not flatten transients or normalize silence/noise. To preserve an intro, break or outro, use **Exclude regions** and paint it red on the waveform (see below). Peaks can require extra headroom: use the separate Limit/Peak Normalizer stages for a delivery ceiling. The Leveler does not hide a global attenuation or a peak limiter. RMS is measured over seconds and does not imply identical LUFS for different spectra.
- **Punch.** Raises **only** the transients (transient expansion), which is only possible because the onsets are declared up front.

You dial the dB of reduction (or boost) you want; the analysis derives the thresholds, sensitivity and timing.

### Analysis and responsiveness

The first load needs source and structural analysis. Later edits reuse valid
features, skip unused stages and keep only the latest pending analysis request.
The current audio plan becomes available before presentation statistics finish;
stopped-state meters show `…` while those statistics are pending. Pressing Play
while the plan is being prepared queues playback; Stop cancels that request.
Analysis is still offline computation, not an instantaneous operation. Measured
latencies, test conditions and limits are recorded in
[the performance validation](docs/diagnostics/performance-p1-validation.md).

The A/B settings switch reuses each slot's completed, source-aligned PCM. A new
slot with identical settings shares the already approved audio immediately.
When a variant needs rendering, its unchanged EQ/fade prefix is reused; the
remaining stages and any oversampling still run at full quality. Changing the
source, parameters or exclusion regions invalidates the affected cached audio.
No lower-quality preview is substituted for either slot.

### Waveform zoom

The waveform shows the **actual processed audio**, updated when a completed
render becomes audible. It follows the active A/B render; **Bypass** shows the
original. While new settings are being computed, the last approved waveform
remains visible with an updating label. The vertical scale stays fixed at
0 dBFS so gain and limiting changes remain visible; red tips mark samples
above that level. Zoom/pan redraws reuse peak indexes, without rerunning DSP.

Scroll over the waveform to move the visible timeline horizontally, without seeking, pausing or changing playback. Wheel up reveals earlier audio; wheel down reveals later audio. Panning follows the current zoom level and stops at the track edges. Hold **Ctrl** while scrolling to zoom in or out on Windows/Linux; use **Command** on macOS. The time under the pointer stays anchored. Selection, playhead and fade handles use the same view coordinates. Zoom outward to return to the full track. **Alt + wheel** over a fade handle changes its curve; the unmodified wheel only moves the view, including over handles.

### Excluding passages from the Leveler

In Dynamics → Leveler, enable **Exclude regions**, then drag over the waveform.
Red regions receive exactly zero Leveler gain; EQ and the other effects still
apply. Drag a region edge to resize it, or right-click for **Edit exclusion
times…** and **Remove exclusion**. **Clear all** removes every region; undo/redo
restores edits. Press **Esc** to cancel a drag or leave exclusion mode. Exclusion
gestures do not seek or change the loop, and waveform pan/zoom still work.

Regions belong to the track and are shared by A/B. They are saved separately in
your user settings and restored when the unchanged source file is loaded again;
the original audio is never edited. Crop/delete remap regions on the edited
timeline, and undo restores them. Edited timelines remain session-only. Generic
chain presets and batch processing do not carry exclusions into unrelated tracks.

### Clip (saturation and hard clip)

Saturation is one of the most useful mastering tools, for two reasons: shaving peaks **without losing punch**, and adding colour. When you tame a peak with saturation you are not blunting the attack of a kick or snare; you are moving that peak's energy into harmonics that spread across the spectrum. The peak comes down, but the sense of punch stays. It also fills the sound out and gives it character.

- **Soft-Clip.** You say how many dB to bring the loudest peak down, and it applies just enough saturation to hit that, exactly like the compressors solve their gain from the peak map. **Tube, Tape and Transformer** give progressively different colour and character.
- **Hard-Clip.** The same peak reduction with no colour, a hard ceiling. Use it for a clean, transparent shave or for extra loudness.

### Limit (the final touch)

An offline two-stage limiter: a linear-phase multiband stage (four bands, each with its own Push) feeds the broadband true-peak-aware limiter **in series**. Both use smooth lookahead and calibrated release. Broadband's ceiling is anchored to the signal **before Multiband**, so band edits cannot raise its reference and cancel their own effect through output normalization.

Band **Push** adds drive while holding each band's original peak. Broadband **Push** adds input gain to the already processed multiband signal; it is not a maximum-GR setting. Its knob never changes when bands change, and its meter shows the actual attenuation, which can exceed Push when the bands have already added drive. At zero broadband Push the stage still catches multiband peaks above the shared reference. The base-rate float output is checked for newly created inter-sample peaks; the separate **Peak Normalizer** measures the final waveform after oversampling/sample-rate conversion and sets the delivery ceiling. Multiband processing can change phase cancellation and timbre, so instantaneous GR is signal-dependent, not required to increase at every sample.

### Output

The **Peak Normalizer** sets the delivery ceiling in dBTP (default -0.1 dBTP) using true-peak measurement, and you can render with selectable **oversampling** up to 16x. Double-click its slider or value to type an exact ceiling; use a lower value such as -1 dBTP when you want extra lossy-codec safety margin. Export dialogs open on your **desktop** (afterwards on the folder of your last export) and default to the standard delivery format of **48 kHz / 24-bit**, while retaining every other supported rate and depth as an option.

Exports are delivery-grade end to end: 16 and 24-bit renders are **TPDF-dithered**, sample-rate conversion uses a polyphase windowed-sinc resampler (anti-aliased, with the true-peak ceiling re-anchored at the delivery rate), and same-container exports keep the source's **metadata** (ID3 tags on MP3, LIST-INFO and bext chunks on WAV). The whole chain can be saved and loaded as a **preset**, compared via a two-slot **A/B settings switch** (two full chain configurations, switchable while playing, while the transport's **Bypass** compares against the original audio), and applied to a folder of songs at once with **batch export**.

## Features at a glance

- Multi-band parametric and dynamic EQ with per-band channel routing and linear or minimum phase. The linear-phase kernels span a constant time window, so the realized curve is identical at any rate or oversampling factor.
- Spectral **Auto EQ** with selectable target curves, over a live FFT analyser.
- Four automatic offline look-ahead dynamics processors with live gain-reduction metering.
- **Clip**: Tube/Tape/Transformer saturation and hard clip, dialled in dB, all antiderivative anti-aliased (ADAA).
- **Limit**: two-stage true-peak limiter, fully oversampled.
- True-peak Peak Normalizer and oversampling up to 16x.
- Proportional whole-window scaling on Windows: the fixed interface resizes as one unit, without horizontal or vertical stretching, clipping or black frames during live resize.
- Real-time playback with latency-compensated cursor and meters, a time-aligned **Bypass** (instant original-vs-master comparison), selection **looping**, an interactive waveform, non-destructive crop and trim.
- Integrated **LUFS** metering (ITU-R BS.1770), true-peak readout, phase correlation and M/S levels, all measured from the fully post-processed master even while Bypass is auditioning the source, plus a draggable processing-chain bar.
- Chain **presets** (JSON), a two-slot **A/B settings switch** for comparing two full chain configurations, and undo/redo that also covers parameter changes.
- **Batch export**: master a whole folder with the current chain.
- WAV (16, 24 and 32-bit integer, 32-bit float) and MP3 (decode and encode), with TPDF dither on reduced-depth renders and metadata preservation.

## Known limitations

- MP3 decoding goes through the decoder's 16-bit PCM output (an mp3spi limitation), so an imported MP3 carries a -96 dB quantisation floor before processing. WAV sources are decoded at their full depth.

## Download

Portable builds with a bundled Java runtime (you do **not** need Java installed) are published on the [Releases](https://github.com/CristianMoresi/QuickMaster/releases) page, one per system (Windows, macOS, Linux). Download the one for your system, unzip it and run QuickMaster. Nothing is installed: to remove it, just delete the folder. Your settings and logs are kept in your user data directory, not in the app folder.

## Build from source

QuickMaster is a Maven project requiring **JDK 17 or newer**.

Audited Windows builds use Temurin **25.0.4.7**, including the DSPark build;
the exact dependency hash is checked by the acceptance suite. See `docs/VALIDATION.md`.

The DSPark Java sources and tests are versioned in `vendor/dspark-java/`. Build and install them first (the audited binary is also retained in `libs/`):

```bash
mvn -f vendor/dspark-java/pom.xml clean install
```

Then, from the project root:

```bash
mvn clean javafx:run   # build and launch the app
mvn test               # requires the official corpus properties documented below
```

The complete test and package workflow requires the external ITU/EBU test signals and the applicable EBU usage authorization. See [validation and portable delivery](docs/VALIDATION.md) for the required Maven properties and Windows app-image commands. The official audio is not redistributed in this repository.

## Built on DSPark

QuickMaster's audio engine is a Java port of **[DSPark](https://github.com/CristianMoresi/DSPark)**, my own C++ DSP library, packaged here as the `com.dspark:dspark` dependency. The port keeps DSPark's filter, FFT, loudness, oversampling and equalizer primitives and adds the analysis pieces this app needs (onset detection and tempo estimation) on top of them.

Java 0.2 incorporates an audited set of kernels from C++ commit `9330f1c`,
including FFT, oversampling and finite-file true-peak improvements. It preserves
QuickMaster's existing EQ voicing and offline dynamics; it is not a complete
feature-for-feature port of every C++ effect. See the
[migration record](docs/diagnostics/dspark-java-0.2-migration.md).

## Architecture

```
com.quickmaster
  audio                audio model and WAV/MP3 file I/O (format auto-detected)
  processing           the DSP pipeline and the AudioProcessor lifecycle
  processing/eq        parametric EQ and offline Auto EQ
  processing/dynamics  the four look-ahead dynamics processors
  processing/clip      soft-clip saturation and hard-clip
  processing/limit     the true-peak multiband and broadband limiter
  processing/analysis  spectrum, tempo and onset analysis
  playback             real-time streaming playback engine
  config               configuration persistence and logging
  ui                   JavaFX controller, FXML view and stylesheet
```

Audio flows through a configurable **pipeline** of processors, each following a `prepare`, `analyze`, `process` lifecycle borrowed from professional audio plug-in APIs. The analysis-driven modules precompute their control data in `analyze` and apply it by absolute playback position. Partition-equivalence tests cover block processing at fixed settings; oversampling and sample-rate conversion have separate numeric and signal tests.

## Author

**Cristian Moresi**, backend developer, audio-software developer and music producer.
[github.com/CristianMoresi](https://github.com/CristianMoresi)

## License

Released under the [MIT License](LICENSE).
