# DSPark-Java 0.2.6: the TonalBalance port

Scope: port the adaptive equalizer `TonalBalance` of the current C++ DSPark
(`14a44cff`, v1.9.0, `Effects/TonalBalance.h`) and everything it stands on to
the vendored Java library (`vendor/dspark-java`), so QuickMaster's Auto EQ can
use it. Version 0.2.5 -> 0.2.6. The C++ repository was read, never modified,
compiled or run.

## What was ported

| Java class | C++ source | Role |
|---|---|---|
| `effects.TonalBalance` | `Effects/TonalBalance.h` | The processor: broad balance, peaks and dips, loudness-conserving offset |
| `core.AuditoryScale` | `Core/AuditoryBands.h`, `Core/DspMath.h` | Bark scale, critical bandwidth, threshold in quiet, loudness exponent, dominant stereo power |
| `core.AuditoryBands` | `Core/AuditoryBands.h` | 72 Bark bands over a power spectrum, specific and total loudness |
| `core.SpectralEnvelope` | `Core/SpectralEnvelope.h` | Smoothing across frequency with a width that follows frequency |
| `core.SpectralFocus` | `Core/SpectralEnvelope.h` | A frame read through a window that shortens as frequency rises |
| `core.DetailAnalysis` | `Core/DetailAnalysis.h` | Power at 1024 log-frequency points through windows of 21 to 341 ms |
| `core.DetailFilter` | `Core/DetailFilter.h` | The time-varying minimum-phase filter that plays the curve (block-latency form) |
| `core.MinimumPhase` | `Core/SpectralFilter.h` | Minimum-phase kernel of a log magnitude by its real cepstrum |
| `core.FirDesign` | `Core/FIRFilter.h`, `Core/WindowFunctions.h` | Kaiser windowed-sinc low-pass of the rate changers |
| `core.FFTRealDouble` | `Core/FFT.h` | Double-precision real transform (same definition and scaling) |
| `core.WindowFunctions.asymmetricHann` | `Core/WindowFunctions.h` | The model's low-latency analysis window |
| `core.DspMath.fastLog` | `Core/DspMath.h` | The series logarithm the detail reading uses |
| `core.FFTComplex` (double path added) | `Core/FFT.h` | Transform under `FFTRealDouble`; the float path's arithmetic is unchanged |

Latency is that of the C++: 774 samples at 44.1 and 48 kHz, doubling by octaves
with the rate, with a curve drawn every 256 samples.

## Deliberate differences

- Java works in double throughout. It equals `TonalBalance<double>`; against a
  float instantiation of the C++ it differs at float rounding.
- One or two channels are analysed; no template parameter, SIMD or atomics.
- Not ported, none of it on the default audio path: the external key input,
  state save/restore, the frame-latency variant of `DetailFilter`, hold, the
  short kernel, removal (delta) listen and the played-response display. Java
  adds a `CurveListener` that hands over each curve as it is drawn.
- Java has no `log2` or `exp2`: `Math.log(x) / Math.log(2)` and `Math.pow(2, x)`
  differ from them by at most an ulp. No size or rounding decision depends on
  that for any standard sample rate.

## Fidelity review

No native vectors exist for this class: nothing of the C++ may be compiled or
run on this machine, and the DSPark Studio catalogue has no tonal balance
module. Fidelity was therefore established by reading. Four independent
reviewers, each given only the C++ file and its Java port with no conclusion to
confirm, compared them function by function (prepare, reset, every setter with
its clamp and default, the feed, the model's hop, the loudness-offset solver,
the detail reading and contrast, the filter's rate change, split, kernel design,
crossfade and partitioned convolution, the analysis' windows, decimators and
schedule). Their verdicts: faithful in all four, with these findings, all
applied before the build:

| Finding | Size | Resolution |
|---|---|---|
| `Math.log` where C++ uses `fastLog` (three places in the detail reading) | under 1e-6 dB on the curve | `DspMath.fastLog` ported and used |
| `FirDesign` summed the Bessel series to convergence; C++ stops at a part in 1e12 | about 4e-14 relative on the taps | The C++ series, operation for operation |
| `Math.max(0, NaN)` is NaN where `std::max(0.0, NaN)` is 0 (dominant power, specific loudness) | non-finite input only | C++ semantics |
| `Math.max(line, band)` with a NaN band (`DetailAnalysis`) | overflowed power only | C++ semantics |
| `WindowFunctions` Bessel loop bound `<= 50` against `< 50` | none in practice | C++ bound |

Left as they are: `WindowFunctions.hann` computes `2 pi i / N` as `(2 pi i) / N`
where C++ computes `(2 pi / N) i`. Both are exact for the power-of-two sizes
every caller here uses, and changing it would move existing QuickMaster paths by
an ulp at other sizes for no gain.

## Tests

96 new tests in the library (271 in all, green), one class per ported class.
They state the properties each class documents and measure them:

- `FFTRealDouble`: against the direct DFT in every bin; exact inverse to 1e-12
  up to 16384 points; Parseval; offset variants.
- `FirDesign`: the rate changer's design is flat within 0.001 dB in its passband
  and over 97 dB down from the slow Nyquist; Kaiser's beta; Bessel values.
- `AuditoryScale`, `AuditoryBands`: the published values of the formulas; round
  trip of the Bark scale; a line keeps its power; narrow and wide bands read
  their own width of a flat spectrum; the reference level moves the thresholds.
- `MinimumPhase`: a flat magnitude is a unit impulse; the kernel has the
  magnitude asked to 1e-9; a boost times its cut is an impulse.
- `DetailAnalysis`: a full-scale sine reads 1 at its point within 0.4 dB at
  every frequency and width (the window's own scalloping between bins); a noise
  reads the power of its band; any feeding pattern gives the same bits.
- `DetailFilter`: a flat curve returns the programme to the bit at every rate,
  late by exactly the latency; a constant gain within 0.05 dB from 40 Hz to
  17 kHz; a three-bell curve within 0.15 dB across the crossover; a twelfth of
  an octave dip at 100 Hz within 0.5 dB; any block cut gives the same bits;
  a moving curve never steps; nothing sounds before the latency.
- `TonalBalance`: with nothing asked the programme returns to the bit; a white
  noise is darkened and a second pass asks for under a fifth of the first; tilt
  and asymmetry move the result as documented; no band or curve point passes
  the range; the loudness the model reports is kept to 1e-6 dB and a second
  reading of the output agrees within 0.12 dB; a peak is trimmed and a dip
  filled only where they are; a slope is neither; both channels take the same
  curve; a source reads the same anywhere in the stereo image; any block cut
  gives the same bits; non-finite samples are taken as silence.

## Measured cost

Single thread, Ryzen 9 9950X3D, Temurin 25.0.4.7, stereo at 48 kHz: realtime
quality x15, render quality x11.7 (85 ms of processor time per second of
audio). Just over half of it is the double-precision transforms, about a tenth
the minimum-phase kernel design. QuickMaster renders offline in parallel
stretches; see [the Auto EQ record](auto-eq-tonal-balance.md).

## Reproducible binary

Built with Eclipse Temurin 25.0.4.7 and the POM's fixed timestamp
(`2026-11-25T00:00:00Z`); two builds give the same bytes.

`libs/dspark-0.2.6.jar` SHA-256:
`a51149ba34b9863f3c9775e79551c7f2c65fbeb9bfce678cc66134b1105c52a4`

The hash is pinned in `ActiveLevelerGuardContract`. The historical jars in
`libs/` are kept.

## An observation for the C++ (not changed here)

`SpectralFocus` runs its one-pole smoothing across the bins with nothing beyond
the two ends of the spectrum, so within about one smoothing width of Nyquist a
noise reads up to 2.1 times its power (1.33 times at DC), measured on the port.
It only matters with the optional time focus on, which is off by default and
which QuickMaster does not use. The port reproduces the C++ here; mirroring the
spectrum at both ends would remove it upstream.
