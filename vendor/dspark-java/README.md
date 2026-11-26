# DSPark for Java

A pure-Java audio DSP library with zero runtime dependencies, ready for desktop apps,
offline batch tools and real-time playback engines.

DSPark for Java is a Java adaptation of
[DSPark](https://github.com/CristianMoresi/DSPark), maintained here with QuickMaster.
Version 0.2 incorporates audited kernels from C++ commit
`9330f1cd29164f6d33e7876bc422627a200ec919`; it does not claim feature-for-feature
parity with the entire C++ library. QuickMaster-specific offline dynamics and EQ
voicing are preserved. See the [migration and numerical validation record](../../docs/diagnostics/dspark-java-0.2-migration.md).

Version 0.2.8 adds DSPark's analog circuits of C++ `14a44cff`, complete:
`TapeMachine`, `TubePreamp`, `TransformerModel`, the 11-algorithm `Saturation`,
`MultibandSaturation` and `CrossoverFilter`, with what they stand on (the
current `Oversampling` engine, `FIRFilter`, `DryWetMixer`, `Hysteresis`,
`TapeTransport`, `AnalogRandom`, `Smoothers`, `FilterDesign`, `BiquadDesign`,
`BiquadFilter`, `BiquadCascade`, `NormalizedLadder`, `Adaa`, `Interpolation`,
the `core.wdf` tone stack, `CabinetModel`, `MidSide`, `DcBlockFilter`). Every
C++ test assertion on them is ported and passes, and independent line-by-line
reviews found no difference that changes an output. The Java-only
rational-knee voicings formerly named `Saturation` are now
`RationalKneeSaturation`, unchanged. Existing classes keep their behaviour. See
the [0.2.8 record](../../docs/diagnostics/dspark-java-0.2.8-analog-saturation.md).

Version 0.2.7 gives `TonalBalance` two shares, `setRaising` and `setLowering`:
how much of what it would raise is raised and of what it would lower is
lowered, through the balance, the detail, the offset that keeps the loudness
and the smooth line between bands. At 0 no point of the curve goes that way.
They are an addition of this library (the C++ class does not have them) and
with both whole, the defaults, every output of 0.2.6 is unchanged. See the
[0.2.7 record](../../docs/diagnostics/dspark-java-0.2.7-raising-lowering.md).

Version 0.2.6 adds `TonalBalance`, the adaptive equalizer of C++ `14a44cff`
driven by an auditory model (loudness in 72 Bark bands, the threshold in quiet,
the dominant component of the stereo image), with the classes it stands on:
`AuditoryScale`, `AuditoryBands`, `SpectralEnvelope`, `SpectralFocus`,
`DetailAnalysis`, `DetailFilter` (the block-latency minimum-phase filter),
`MinimumPhase`, `FirDesign`, `FFTRealDouble`, the asymmetric Hann window and
`DspMath.fastLog`. The port was compared line by line against the C++ by four
independent reviews; no native vectors exist for this class, so its tests state
and measure the properties it documents. Existing DSP classes keep their
behaviour. See the [tonal balance port record](../../docs/diagnostics/dspark-java-0.2.6-tonal-balance.md).

Version 0.2.5 re-syncs to C++ `14a44cff`: the `Resampler` adopts the current C++
Direct engine (a Kaiser kernel designed from each tier's passband/stopband
specification, with exact polyphase phases for rational rates such as
44.1 <-> 48 and 96 <-> 48, and a cubic-interpolated 512-phase table otherwise),
and `Dither` gains non-finite input sanitisation plus an opt-in Lipshitz5
noise-shaper. All other DSP classes are byte-identical, and a full
re-verification found the rest of the port already current with the C++ for
QuickMaster's paths. See the [0.2.5 re-sync record](../../docs/diagnostics/dspark-java-0.2.5-resync.md).

Version 0.2.2 adds `SuperFluxOnsetDetector` (offline C++ `474b7d1` path, with
stereo spectral-power pooling) and signed stateless Clipper transfer access.
The Analog curve now has the C++ unit-slope sine transfer. These paths have
compiled-native reference vectors. Legacy Java `Saturation` rational voicings
are not the C++ physical tape/transformer models and are no longer used by
QuickMaster's clip controls. See the [scope and numerical evidence](../../docs/diagnostics/dynamics-clips-audit.md).

## What's included

Version 0.2.4 adds an offline MPEG-1 Layer III float decoder, ported from C++
`a8556aa7066d52b2aa22b0078cc976f3344a4869`. Existing DSP classes are unchanged.
The port preserves floating headroom, fixes validated mono gapless tags and
intensity-stereo band selection, and rejects truncated frames/reservoirs.
See [MP3 scope, reference vectors and upstream differences](../../docs/diagnostics/mp3-decoder-port.md).

Version 0.2.3 updates `AutoGain` to the K-weighted C++ `474b7d1` algorithm,
including 400 ms integration and thread-safe parameter/gain publication.
720 native block vectors verify the port. `offlineGainDb` is an explicit
whole-file, fixed-gain extension; it is not the streaming smoother or a limiter.
See [EQ auto-gain validation](../../docs/diagnostics/eq-autogain-audit.md).

| Area | Modules |
|------|---------|
| **Core** | `DspMath`, `BiquadCoeffs` (incl. Tilt), `FilterEngine`, `RingBuffer`, `SmoothedValue`, `WindowFunctions`, `FftReal`, `FFTRealDouble`, `FirDesign`, `MinimumPhase`, `AuditoryScale`, `AuditoryBands`, `SpectralEnvelope`, `SpectralFocus`, `DetailAnalysis`, `DetailFilter`, `Dither`, `Resampler`, true-peak detection |
| **Effects** | `Normalizer`, `Limiter` (true-peak brickwall), `Equalizer` (minimum- and linear-phase), `TonalBalance` (adaptive, auditory model), `StereoWidth` (with bass-mono), `DcBlocker`, `AutoGain`, `Gain` |
| **Analysis** | `LoudnessMeter` (EBU R128: momentary / short-term / integrated + true-peak + LRA), `SpectrumAnalyzer`, `LevelFollower` |
| **I/O** | `Mp3Stream` (checked container/gapless metadata), `Mp3Decoder` (MPEG-1 Layer III to interleaved float PCM) |

Audio uses 32-bit `float`, with nominal full scale `[-1.0, +1.0]` and unclipped
internal headroom. Output gain/limiting, not the float representation, determines
the delivery ceiling.

## Design

- **Pure Java, zero dependencies**: only the JDK and (for tests) JUnit 5.
- **Prepare / process lifecycle**: sample-rate-dependent setup is done once in
  `prepare(...)`, keeping the per-sample inner loop allocation-free.
- **Offline and streaming**: effects provide block processing; source analyses
  such as `SuperFluxOnsetDetector` explicitly require the complete signal.

## Build

```
mvn install
```

Sources target Java 17. To reproduce the exact binary audited by QuickMaster,
build with Eclipse Temurin 25.0.4.7 and follow
[QuickMaster's build and delivery instructions](../../docs/VALIDATION.md).
The POM fixes the archive timestamp; the acceptance suite also checks the
dependency hash, so an arbitrary compiler rebuild must not replace that pin.

## License

[MIT](LICENSE). DSPark for Java is a port of the DSPark C++ framework, also MIT-licensed.
Attribution appreciated.
