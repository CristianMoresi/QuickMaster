# DSPark for Java

A pure-Java audio DSP library — zero runtime dependencies, ready for desktop apps,
offline batch tools and real-time playback engines.

DSPark for Java is a Java adaptation of
[DSPark](https://github.com/CristianMoresi/DSPark), maintained here with QuickMaster.
Version 0.2 incorporates audited kernels from C++ commit
`9330f1cd29164f6d33e7876bc422627a200ec919`; it does not claim feature-for-feature
parity with the entire C++ library. QuickMaster-specific offline dynamics and EQ
voicing are preserved. See the [migration and numerical validation record](../../docs/diagnostics/dspark-java-0.2-migration.md).

Version 0.2.2 adds `SuperFluxOnsetDetector` (offline C++ `474b7d1` path, with
stereo spectral-power pooling) and signed stateless Clipper transfer access.
The Analog curve now has the C++ unit-slope sine transfer. These paths have
compiled-native reference vectors. Legacy Java `Saturation` rational voicings
are not the C++ physical tape/transformer models and are no longer used by
QuickMaster's clip controls. See the [scope and numerical evidence](../../docs/diagnostics/dynamics-clips-audit.md).

## What's included

Version 0.2.3 updates `AutoGain` to the K-weighted C++ `474b7d1` algorithm,
including 400 ms integration and thread-safe parameter/gain publication.
720 native block vectors verify the port. `offlineGainDb` is an explicit
whole-file, fixed-gain extension; it is not the streaming smoother or a limiter.
See [EQ auto-gain validation](../../docs/diagnostics/eq-autogain-audit.md).

| Area | Modules |
|------|---------|
| **Core** | `DspMath`, `BiquadCoeffs` (incl. Tilt), `FilterEngine`, `RingBuffer`, `SmoothedValue`, `WindowFunctions`, `FftReal`, `Dither`, `Resampler`, true-peak detection |
| **Effects** | `Normalizer`, `Limiter` (true-peak brickwall), `Equalizer` (minimum- and linear-phase), `StereoWidth` (with bass-mono), `DcBlocker`, `AutoGain`, `Gain` |
| **Analysis** | `LoudnessMeter` (EBU R128: momentary / short-term / integrated + true-peak + LRA), `SpectrumAnalyzer`, `LevelFollower` |

Audio uses 32-bit `float`, with nominal full scale `[-1.0, +1.0]` and unclipped
internal headroom. Output gain/limiting, not the float representation, determines
the delivery ceiling.

## Design

- **Pure Java, zero dependencies** — only the JDK and (for tests) JUnit 5.
- **Prepare / process lifecycle** — sample-rate-dependent setup is done once in
  `prepare(...)`, keeping the per-sample inner loop allocation-free.
- **Offline and streaming** — effects provide block processing; source analyses
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
