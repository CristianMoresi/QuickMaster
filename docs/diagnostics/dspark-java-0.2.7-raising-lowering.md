# DSPark-Java 0.2.7: what the tonal balance raises and what it lowers

Scope: give `TonalBalance` two shares, one for everything it raises and one
for everything it lowers, so a host can ask for a correction that only lowers
or only raises. Version 0.2.6 -> 0.2.7. This is an addition of the Java
library: the C++ `TonalBalance` (`14a44cff`, v1.9.0) does not have it. With
both shares whole, the defaults, every output of 0.2.6 is unchanged.

## Why the class needed it

QuickMaster's Auto EQ has a control, Cut/Boost, that chooses how much of the
correction is lowering and how much is raising
(see [the Auto EQ record](auto-eq-tonal-balance.md)). The class already lets
the balance lift and cut by different shares (`setBalanceAsymmetry`) and the
detail fill dips and trim peaks by different amounts, but two things escape
them:

- **The offset that keeps the loudness.** After the corrections the class
  shifts every band so the total loudness does not change. A balance that only
  cuts takes loudness away, so the offset raises everything else. Measured on
  a real master with the asymmetry at -1 and no filling of dips: the curve
  still stood 2.7 dB over zero.
- **The smooth line between the bands.** The broad curve runs through the
  bands' corrections as a Catmull-Rom line, which swings past two bands where
  the correction changes fast. Next to a cut of 12 dB it stood 0.74 dB over
  zero.

## What was added

`TonalBalance.setRaising(double share)` and `setLowering(double share)`, each
0 to 1, default 1, with `getRaising()` and `getLowering()`.

| Where | Raising scales | Lowering scales |
|---|---|---|
| The balance | the lift of what is lacking | the cut of what is in excess |
| The detail | the filling of dips | the trimming of peaks |
| The loudness offset | the offset when it raises | the offset when it lowers |
| The line between bands | what it swings over its two bands | what it swings under them |

With raising at 0 no point of the curve is over zero; with lowering at 0, none
is under. Both are exact, not approximate (`setListeningCompensation`, a fixed
contour, stands apart). Under a whole share the offset no longer keeps the
loudness when it goes that way; `getLoudnessChange()` tells by how much.

With both shares at 1 nothing is computed differently: the multiplications are
by one, and the two branches that change a value run only for a share under
one. The class's own tests of 0.2.6 pass unchanged.

## Tests

`TonalBalanceTest` (27 tests, 272 in the library):

- `raisingAndLowering`: on a white noise with a resonance and a hole, with the
  balance all the way and peaks and dips in full, the curve goes both ways;
  with raising at 0 the highest value of every curve drawn is exactly 0, no
  band is raised, the loudness goes down and the share of loudness raised is
  0; with lowering at 0 the same the other way; half the raising lies between;
  both whole give the same output as a processor that was never told.
- `controls`: both shares are clamped to 0 to 1 and a value that is not a
  number is ignored.

## Reproducible binary

Built with Eclipse Temurin 25.0.4.7 and the POM's fixed timestamp
(`2026-11-25T00:00:00Z`); two builds give the same bytes.

`libs/dspark-0.2.7.jar` SHA-256:
`94a7700335a7513ff1c2fa69ecb6970a23577dacae5a26da628ffdfb6abd9c6b`

The hash is pinned in `ActiveLevelerGuardContract`. The historical jars in
`libs/` are kept.

## For the C++

The two shares are a small, self-contained addition (four multiplications and
two branches) that would port back to `Effects/TonalBalance.h` as it is. The
C++ repository was not touched.
