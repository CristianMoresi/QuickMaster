# DSPark-Java 0.2.8: the analog saturation models

Scope: port the C++ DSPark analog circuits (`14a44cff`, v1.9.0) to the vendored
Java library, for QuickMaster's saturation stage. Version 0.2.7 -> 0.2.8.

## What was ported

The effects, complete (every mode, setter and documented behaviour; the
real-time plumbing of the C++, atomics, SPSC queues, seqlocks, state blobs,
SIMD dispatch and denormal guards, has no meaning in Java and was left out):

| C++ header | Java class | What it is |
|---|---|---|
| `Effects/TapeMachine.h` | `effects.TapeMachine` | Reel-to-reel machine: push-pull Jiles-Atherton core with a four-level AC bias carrier, NAB/CCIR record and reproduce EQ per speed, playback losses, head bump and contour, Ideal and Circuit electronics, transport, hiss, hum, bleed, even harmonics, output iron, alignment |
| `Effects/TubePreamp.h` | `effects.TubePreamp`, `effects.TubePreampDetail` | Koren 12AX7 stages solved in continuous time, grid models, supply sag, the Bassman FMV tone stack in modal form, the B-spline output projection, flattening EQ and drive LUT |
| `Effects/TransformerModel.h` | `effects.TransformerModel` | Iron core saturating on flux: leaky integrator, Jiles-Atherton core, exact inverse, magnetizing corner, leakage bell |
| `Effects/Saturation.h` | `effects.Saturation`, `effects.SaturationDetail` | The 11-algorithm saturator with first and second order antialiasing, drive compensation, level tracking, comb loop, tone, M/S modes |
| `Effects/MultibandSaturation.h` | `effects.MultibandSaturation` | Saturation per crossover band |
| `Effects/CrossoverFilter.h` | `effects.CrossoverFilter` | Linkwitz-Riley crossover, IIR and linear phase |
| `Effects/Clipper.h` | `effects.OversampledClipper` | The current clipper with its oversampling, glides and mix (the older `effects.Clipper`, which QuickMaster's curves use, stays as it was) |
| `Core/CabinetModel.h` | `core.CabinetModel` | The preamp's optional cabinet |

And what they stand on, as new classes: `core.Oversampling` and
`OversamplingDryPath` (the current C++ engine: linear-phase and polyphase IIR,
Deep depth, the first stage fitted to the host rate), `FIRFilter`,
`DryWetMixer`, `DryPhase`, `Hysteresis`, `TapeTransport`, `AnalogConstants`,
`AnalogRandom`, `Smoothers`, `SmoothedParameter` (the current C++
`SmoothedValue`), `BiquadDesign` and `BiquadFilter` (the current C++ biquads in
double), `AnalogSection`, `AnalogCascade`, `FilterDesign`, `BiquadCascade`,
`NormalizedLadder`, `NormalizedLadderN`, `Adaa`, `LogCosh`, `Interpolation`,
`FFTRealDoubleStepped`, the `core.wdf` package (elements, adaptors, R-type and
the FMV tone stack), `effects.MidSide` and `effects.DcBlockFilter` (the current
C++ `DCBlocker`).

## What stays as it was

Every class QuickMaster used before computes exactly as before. The older
ports `Oversampler`, `OversamplingEngine`, `BiquadCoeffs`, `Biquad`,
`SmoothedValue`, `DcBlocker` and `FirDesign` are untouched: the new code uses
the new classes. Three changes touch existing files, all additive: `DspMath.fastSin` (the C++ fast sine) is new,
`WindowFunctions.kaiser(double[], int, double, boolean)` (the C++ symmetric or
periodic Kaiser window) is a new overload, and the Java-only rational-knee
voicings that were called `effects.Saturation` are now
`effects.RationalKneeSaturation`, with only the name changed, so the faithful
C++ port can take the C++ name. QuickMaster's stereo generator uses the
renamed class and its output is unchanged.

## How fidelity was verified

No C++ is built or run on this machine, so there are no native vectors.
Instead:

1. Every assertion the C++ test suite makes about these classes
   (`TestAnalogAudit`, `TestEffectsTone`, `TestCoreFilters`,
   `TestCoreRegressions`, `TestCoreUtilities`, `TestEffectsAdvanced`,
   `TestEffectsDynamics`, `TestQualityAudit`, ...) is ported with its own
   signals and thresholds, and passes.
2. The figures the C++ headers document are measured on the port: for
   example the tape's head bump of 2.5 dB at 22.5, 45, 90 and 180 Hz, its hiss
   offsets per formulation and track, the two-level carrier's gain rise of 3.2
   and 9.9 dB, the preamp's latencies 0/71/93/94/96 and its foldback of -95.8 to
   -98.9 dBc, its DC operating point against an independent solve within 1e-9 V,
   the transformer's latencies and its 0.000 dB branch delta, the
   saturation's antialiasing figures, the crossover's slopes and reconstruction.
3. Ten independent line-by-line reviews compared each Java file with its
   C++ header. None found a difference that changes an output sample, a
   returned value or a latency. What they found was last-bit only (the Java
   maths library against the C library, the SSE2 lane order of dot products
   against an AVX2 build, Smith's complex division, now used) or concerned
   allocation when a parameter changes.
4. Properties: identical output in blocks of 1, 37, 512 and 4096 samples,
   `reset()` equal to a fresh instance, the reported latency equal to the
   measured delay, finite output, deterministic runs.

The suite of the library has 823 tests, all passing (4 are opt-in reports).

## Where the port departs from the C++

One place, on purpose: `MultibandSaturation`'s level and pan glide. The C++
adds a step fixed at the start of each block to every sample of it, with no
stop at the target, so a block longer than the 20 ms glide overshoots and one
longer than 40 ms diverges (with 4096-sample blocks a band's level ran to
1.8e5). The port stops the glide on its target in blocks longer than the
glide; blocks of 20 ms or less run exactly the C++ arithmetic, which a test
checks to the bit. Above 16 channels the port passes the extra channels
through, as the C++'s 16-channel buffers do.

## The property QuickMaster relies on

QuickMaster renders whole songs through these circuits in parallel stretches,
each started from rest some time before its first sample. Measured with the
settings QuickMaster uses (no wow and flutter, hiss, hum or bleed, no drift),
a circuit started from rest agrees with one continuous pass, after:

| Circuit | Settling | Worst gap after it |
|---|---|---|
| TapeMachine, 64 settings (speeds, standards, electronics, heads, 44.1 and 48 kHz) | 1.5 s | 1.3e-12 of the peak |
| TubePreamp, one and two stages | 1.5 s | 4.5e-13 |
| Saturation, Tube and MultiStage | 1.5 s | 6.0e-10 |
| Saturation, Transformer, drive -36 to +36 dB | 1.5 s | 7.2e-8 |
| TransformerModel | 6 s | 1.0e-7 |

The transformer's core forgets where it started in proportion to how far the
programme swings it, so a lightly driven core needs about 6 s. QuickMaster
checks every join regardless.

## Why QuickMaster's Transformer is the Saturation effect's

`TransformerModel` saturates on flux and differentiates back to voltage, as a
real core does, so its saturation does not limit the voltage's peaks: on a
finished master (By Now, 48 kHz) its crest rises with the drive, by 4.1 dB
against its own linear response at +24 dB. That is the physics of a
saturating core, and its sound, but it cannot take peak off a song.
QuickMaster's saturation stage, whose one control is how many dB of peak it
takes off, therefore uses the Saturation effect's `TRANSFORMER` algorithm,
whose core saturation lowers the peaks steadily as the drive rises (by up to
10 dB on the same master).

## Cost

Per stereo frame at 48 kHz, after warm-up, with QuickMaster's settings:
TapeMachine about 3 us (32 Jiles-Atherton solves per frame), TubePreamp about
0.9 us, TransformerModel about 0.5 us, Saturation Tube 0.2 us and MultiStage
about 1 us.

## Notes for the C++

Points the porting found in the C++ itself, left as they are there:

- `TapeMachine.h:909-913`: the THD figures 0.16 / 0.31 / 0.60 % were measured
  with the two-level carrier; the default four levels give 0.114 / 0.227 /
  0.456 %.
- `TransformerModel.h:50-53`: the folded-harmonic figures were measured on an
  earlier core; the current one gives -37.19 and -61.03 dB at 1x.
- `Core/detail/Adaa.h:345`: the rational knee's antiderivative is wrong for an
  input exactly on the knee.
- `Core/Oversampling.h:1008-1013`: three stopband figures of the stage table
  do not match their designs.
- `Core/AnalogRandom.h:642`: the tempo-synced rates look inverted.
- `Effects/MultibandSaturation.h:522-523, 544-545`: the level and pan glide has
  no stop at its target (see above).
