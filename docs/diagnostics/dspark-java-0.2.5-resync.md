# DSPark-Java 0.2.5: re-sync to the current C++ DSPark

Scope: bring the vendored Java DSP port (`vendor/dspark-java`) up to the current
C++ DSPark (`14a44cff`, v1.9.0) for every path QuickMaster actually uses, and
clean up obsolete artifacts. Version 0.2.4 -> 0.2.5.

## Finding: the port was already current for almost everything QuickMaster uses

A full module-by-module re-verification against the current C++ (one direct pass
plus three parallel source sweeps, reading the C++ headers, the Java port files,
and QuickMaster's call sites) found the port **already faithful to the current
C++** for every path QuickMaster uses:

- **TruePeak**: the port's detector is the official BS.1770-5 Annex 2 4x
  interpolator; its coefficients and group delay are identical to the current
  C++ `TruePeakDetector`. The C++ additions (`TruePeakEstimator`,
  `TruePeakAnalyzer` 8x) are new classes for higher-precision metering and the
  limiter's high-precision mode, which QuickMaster does not use.
- **LoudnessMeter**: conformant to EBU Tech 3341 (case-3 gating within 0.1 LU)
  and Tech 3342 (LRA), with NaN-block recovery and a no-op before prepare.
- **MasterEqualizer / Biquad / BiquadCoeffs (including the TILT band)**: the
  EQ voicing is preserved by mandate; the C++ "constant tilt ceiling" fix
  (`f1fe20d2`) applies to a `ConstantTilt` (dB/octave) band the port does not
  implement, while QuickMaster's first-order TILT is bounded by construction.
- **MultibandCrossover**: a telescoping linear-phase design with perfect
  reconstruction and constant latency by construction (block-size independent);
  the C++ redesigns are new features / perf, not correctness fixes.
- **Oversampling**: already block-boundary independent (the port's own
  partition test is bit-exact across block sizes); the C++ changes since the
  pin are opt-in (host-rate first stage, Deep depth, polyphase-IIR) or perf.
- **FFTReal / FFTComplex**: the C++ changes are SIMD/perf only, bit-preserving;
  the Java transform is numerically current (pinned to upstream FFT vectors).
- **Clipper**: QuickMaster uses the stateless transfer (Hard/Tanh/Sine/Golden)
  and owns oversampling in the pipeline, so the C++ offline 1x alias work does
  not apply; the curves are unchanged.
- **AutoGain**: functionally frozen since its pin (`474b7d1`); only a doc
  comment was added upstream.
- **MP3 (MPEG-1 Layer III)**: the intensity-stereo rule the C++ corrected
  (`43adaa0c`) is already implemented the correct, band-granular way in the
  port and validated against an independent 16-fixture ffmpeg corpus. Layer
  I/II, free format and range decode are new capabilities QuickMaster does not
  need (MPEG-2 LSF already routes to QuickMaster's own decoder).
- **TempoEstimator**: current; its nominal C++ counterpart was created after
  the pins, so the port never fell behind an existing class.
- Unused by QuickMaster: `SmoothedValue`, `Normalizer`, `StereoWidth`,
  `DcBlocker`, `LevelFollower`, and the plain `Equalizer`.

## The two genuine deltas on live QuickMaster paths (adopted in 0.2.5)

### Dither (`core/Dither.java`)
Added entry sanitisation of non-finite input (NaN becomes silence, infinities
clip to full scale), so one bad sample can no longer poison the error-feedback
loop until `reset()`. Also added the opt-in 5-tap Lipshitz/Vanderkooy/Wannamaker
noise-shaper (`Shaper.LIPSHITZ5`), default `FIRST_ORDER`. QuickMaster never
enables shaping and clamps before dithering, so its exported bytes are
unchanged; this is faithfulness and robustness.

### Resampler (`core/Resampler.java`)
Replaced the older fixed-tap / 256-phase linear-interpolation design with the
current C++ Direct engine:

- The kernel is a Kaiser-windowed sinc **designed from the tier specification**
  (passband edge as a fraction of the lower Nyquist, stopband attenuation in
  dB; the length and beta follow from Kaiser's formulas): Draft 0.80 / 60 dB,
  Normal 0.90 / 100 dB, High 0.91 / 140 dB, **Ultra 0.915 / 210 dB**.
- For a **rational** rate ratio - every standard conversion, e.g. 44.1 <-> 48
  (160/147) and 96 <-> 48 (1/2) - each output reads one **exact polyphase
  phase**, so there is no phase-interpolation step and none of its images. For
  an unusual ratio the kernel is tabulated at 512 phases and read with cubic
  (Lagrange) interpolation, whose error lies below the Ultra stopband.

For QuickMaster this upgrades the **export sample-rate conversion** (which only
engages when the export rate differs from the source rate; same-rate exports are
untouched). Against the old design it removes the ~-90 dB phase-interpolation
images and reaches the designed stopband (210 dB at Ultra). It is a quality
improvement, not a bug fix.

**Deliberate deviation from the C++** (best for an offline exporter): an extreme
or impossible ratio (past about 24:1 at Ultra, e.g. a 0.001 Hz target) is
**rejected** with `IllegalArgumentException` rather than silently widening the
transition and passing a degraded conversion off as the tier, preserving the
port's existing fail-fast delivery contract (`ResamplerDeliveryTest`).

## Validation

- **dspark-java: 175 tests green.** New Resampler tests pin the upgrade:
  common audio rates resolve to exact polyphase; 44.1 -> 48 is sample-accurate
  to near the float floor (was ~1e-4 with linear phase interpolation); content
  above the target Nyquist is rejected below -80 dB on downsample; High/Ultra
  keep 20 kHz flat while Draft trades the top. New Dither tests pin NaN/inf
  sanitisation and the Lipshitz5 shaper.
- **No local C++ build** was used (owner's standing order: no C++ compiles on
  this machine). Instead the Java Resampler replicates the C++ `Resampler.h`
  Direct-engine design formulas (Kaiser spec, rational reduction, exact
  polyphase, cubic table) so the filter matches the C++ by construction, and
  the tests pin the resulting specification (passband flatness, stopband
  rejection, exact-phase accuracy).
- **Reproducible jar** under Eclipse Temurin 25.0.4.7 (compiler release 17,
  fixed `outputTimestamp`): SHA-256
  `177ea6ba4e80875f4a7f369977835795af08fdf9a656b50c11e4a33b53c63f85`, committed
  at `libs/dspark-0.2.5.jar` and pinned in
  `src/test/java/com/quickmaster/processing/dynamics/leveler/ActiveLevelerGuardContract.java`.
  All other DSPark classes are byte-identical to 0.2.4; only `Dither`,
  `Resampler` and their phrased test variants change.
- The full QuickMaster suite and the official ITU/EBU loudness attestation are
  run against the installed 0.2.5 dependency as part of delivery
  (`docs/VALIDATION.md`).

## Not adopted (offered for the owner's A/B)

- **More-sensitive onset / SuperFlux** (gated peak picker, quarter-frame flux,
  content-release recall, default delta 0.03 -> 0.015): a character change that
  shifts which transients Punch and Beat Comp fire on. Left for an A/B.
- New C++ capabilities QuickMaster does not need: `BeatTracker` (causal beat
  grid), `TruePeakAnalyzer` 8x metering, MP3 Layer I/II + free format, and the
  new EQ / Saturation tonal curves.

## Cleanup

The obsolete standalone port at `E:\Code\Libs\java\dsp` (0.1.0, not a git repo,
every class superseded by `vendor/dspark-java`) was deleted. The historical
`libs/dspark-0.2.0..0.2.4` jars are kept as release history.
