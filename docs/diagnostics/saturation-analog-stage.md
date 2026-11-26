# The saturation stage and its analog models

The first card of Clip, Saturation, takes exactly as many decibels of peak off
the song as its one knob says, no more and no less. What does it is chosen in
its menu:

| Entry | What it is | How the amount is reached |
|---|---|---|
| Soft clip: Sine, Tanh, Golden knee | DSPark's memoryless curves | Closed form against the song's peak (unchanged from earlier versions) |
| Tube preamp | One 12AX7 stage solved as a circuit, the supply's sag, the Bassman tone stack at its reference (flattened), no cabinet | Solved drive |
| Transformer | The Saturation effect's core saturation: the low end saturates before the top | Solved drive |
| Multi-stage | The Saturation effect's tube, tape and transformer in series | Solved drive |
| Tape machine | A reel-to-reel machine, AC-biased Jiles-Atherton core, in one of six alignments | Solved drive |

Hard clipping is the Hard-Clip card's, next to it. All analog models run fully
wet, with their oversampling chosen from the rate they run at (an oversampled
chain lowers it), nothing random, and second-order antialiasing where the model
has it. Zero is a bit-exact bypass for every entry.

## What the amount means

A memoryless curve takes the most off the song's loudest sample and less off
every quieter one, so "the peak loses X dB" and "no moment loses more than X
dB" say the same. An analog model differs in two ways, both measured on By Now,
a finished, limited master:

1. **Its own colour moves the peaks.** A model's equalization, filters and
   phase change a song's peaks before anything saturates: the linear response
   of the tape alignments raises the crest of By Now by 1.4 to 1.9 dB, the
   preamp's by 1.4 dB. That is colour, not clipping, so the reduction is
   measured against the model's **linear response**: the same model at its
   least drive with the song 40 dB below its alignment, brought to the song's
   loudness.
2. **Memory spreads the saturation unevenly.** A tape's record pre-emphasis
   saturates bright transients first, and the preamp's sag and the tape's
   compression act on whole phrases: with the song's peak 1 dB below the
   linear response's, single moments of By Now lost 3 to 4.5 dB.

So the amount is **the most any moment of the song loses to the saturation**,
each moment a hop of 1024 frames whose peak is compared with the same hop of
the linear response. The stage's meter reads exactly that comparison, so its
deepest reading is the knob's, at the most saturated moment, and it reads less
everywhere else, as it does for a curve at the song's peak. Hops more than 60
dB below the linear response's peak are not measured: nothing saturates there.

The `TransformerModel` circuit is not offered: it saturates on flux and
differentiates back to voltage, as a real core does, so its crest rises with
the drive (by 4.1 dB against its linear response at +24 dB on By Now) and it
cannot take peak off a song. The Saturation effect's transformer lowers the
peaks steadily with the drive, by up to 10 dB on the same master.

## The tape alignments

| Preset | Speed | EQ | Tape | Track | Bias | Losses | Head bump | Head | Electronics | Even harm. | Output iron | Operating level |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 30 ips Clean master | 30 ips | AES/CCIR | High output | Half | 0.50 | 0.25 | 0.25 | Bump | Ideal | 0 | 0 | 0 dB |
| 15 ips Master | 15 ips | CCIR | Classic | Half | 0.50 | 0.50 | 0.50 | Contour | Circuit | 0 | 0 | 0 dB |
| 15 ips Colored | 15 ips | NAB | Classic | Half | 0.45 | 0.60 | 0.80 | Contour | Circuit | 0.25 | 0.30 | +3 dB |
| 7.5 ips Warm | 7.5 ips | NAB | Classic | Half | 0.50 | 0.75 | 0.80 | Contour | Circuit | 0.20 | 0.40 | 0 dB |
| 3.75 ips Vintage | 3.75 ips | NAB | Vintage | Quarter | 0.45 | 1.00 | 1.00 | Contour | Circuit | 0.40 | 0.50 | +3 dB |
| 30 ips Fat | 30 ips | NAB | Classic | Full | 0.50 | 0.40 | 1.00 | Contour | Circuit | 0.15 | 0.30 | 0 dB |

None moves the pitch or adds noise: wow and flutter, hiss, hum, the bleed
between channels and the channels' alignment tolerance stay off, as a
mastering transfer keeps them. 15 ips Master is the default.

## How a model reaches the amount

1. **Alignment.** The song's integrated loudness is brought to the model's
   0 VU (the level its own calibration uses: -12 LUFS for the tape and the
   preamp, -16 LUFS for the Saturation effect's models), so the same song
   saturates alike whatever level it arrives at. The output is taken back by
   the same gain.
2. **The linear response.** The whole song is rendered once through the model
   at its least drive, 40 dB below its alignment, and brought to the song's
   loudness. It depends on the song and the model alone, so it is kept while
   neither changes (the song is recognised by a hash of every sample).
3. **The sample.** The 24 loudest moments of the song, the 8 loudest of the
   linear response, the 8 moments each whole render saturated most, and 8
   second-long windows spread over the song's loudness are rendered, each
   after 0.4 s of what precedes it, at a drive. The amount they predict is the
   most any of their hops loses against the linear response once the loudness
   the windows measure is kept, corrected by what the last whole render
   measured of the loudness beyond the windows.
4. **The drive.** It is solved on the sample by regula falsi (Illinois) over
   the model's drive range, to 0.005 dB. The range reaches 24 dB below the
   circuit's own least drive, the level into the circuit lowered by the
   difference and raised back after it, because the least drive of some tape
   alignments already saturates bright transients by more than 1 dB.
5. **The whole render.** The song is rendered through the model in stretches
   of 10 s, in parallel, each started from rest 1.5 s earlier. Every join is
   checked against the stretch before it to 1e-6 of the peak; a join that
   failed would be rendered again with four times the run-in, then the whole
   song in one pass. The stretches follow the song's length alone, so the
   result is the same, to the bit, on any machine.
6. **The gain.** The output gain makes the most saturated hop lose exactly the
   amount. The loudness then equals the input's to within 0.05 dB; if not, the
   drive is solved again with what the render measured, the moments it
   saturated most added to the sample, within a bracket of the drives already
   rendered.
7. **The bounds.** When even the least drive saturates further than asked, the
   least drive is used and the output raised to the amount. When the most
   drive cannot reach it, the most is used and DSPark's sine knee, after the
   model, takes the rest with the loudness kept.

Nothing from an earlier render enters a whole render but the linear response
of the same song and model, so the published and exported audio depends on the
song and the settings alone. A provisional preview of a changed knob uses the
sample alone, with what the last whole render measured, while the whole song
is rendered. At an oversampled rate, and in the preview, the model streams with
the solved drive, gain and knee, its delay reported to the chain.

## Measured on four finished masters

`tools/diagnostics/SaturationTrackAudit.java` on the packaged 1.4.0 image
(`quickmaster.jar` `3091b03a...`, DSPark Java 0.2.8 `fc89da3d...`), By Now,
Quiet Gold, Billie Jean (80s Glam Metal) and Wicked Game (80s Synthwave), 48
kHz stereo, 3.4 to 5.1 minutes, every analog model and tape alignment at 1, 3
and 6 dB: 108 cases, all passing. In every case the meter never showed a gain
and its deepest reading was the knob's to 0.0001 dB, every second render was
identical to the bit and every source file unchanged. Times on a 16-core
desktop, from a fresh stage, so they include the linear response, which the
application keeps between edits of the same song and model.

| Entry | Time per edit (s) | Whole renders | Error of the deepest reading (dB) | Worst loudness change (dB) | Drive at 1 dB (dB) | Sine knee |
|---|---|---|---|---|---|---|
| Tube preamp | 7.8 to 12.3 | 1 to 3 | 0.0000 | 0.023 | -5.9 to -3.4 | 0 of 12 |
| Transformer | 1.4 to 2.8 | 1 to 2 | 0.0000 | 0.041 | -2.2 to +0.9 | 0 of 12 |
| Multi-stage | 11.5 to 15.5 | 1 to 2 | 0.0000 | 0.038 | -2.5 to -1.2 | 0 of 12 |
| Tape 30 ips Clean master | 6.0 to 12.3 | 1 to 2 | 0.0000 | 0.036 | -1.2 to +2.9 | 0 of 12 |
| Tape 15 ips Master | 6.8 to 13.2 | 1 to 2 | 0.0000 | 0.026 | -6.0 to -2.5 | 0 of 12 |
| Tape 15 ips Colored | 10.2 to 13.8 | 2 | 0.0000 | 0.042 | -15.7 to -10.6 | 0 of 12 |
| Tape 7.5 ips Warm | 10.0 to 12.0 | 2 | 0.0000 | 0.037 | -12.3 to -5.5 | 0 of 12 |
| Tape 3.75 ips Vintage | 6.5 to 11.8 | 1 to 2 | 0.0000 | 0.044 | -18.9 to -14.6 | 0 of 12 |
| Tape 30 ips Fat | 10.3 to 14.3 | 2 to 3 | 0.0000 | 0.013 | -5.6 to +0.2 | 0 of 12 |

Every model reached every amount by itself: the sine knee was never needed.
The coloured alignments need drives below the tape's own least drive at 1 dB,
because their pre-emphasis saturates bright transients early.

The song's own peak follows from the model's colour and from where the
saturation acts. At 1 dB it lies 0 to 1 dB below the linear response's peak
(the most saturated moment is seldom the loudest), at 6 dB 0.9 to 5.7 dB
below. Against the input, the tape alignments' equalization raises a limited
master's peak: at 1 dB the output peak lies 0.7 to 3.2 dB above the input's,
at 6 dB from 1.2 dB below to 2.1 dB above. That is the colour of the
alignment, and the Hard-Clip card and the limiter after it take care of it.

`tools/diagnostics/ClipsUiAudit.java` on the same image, Quiet Gold: the menu
holds Soft clip (Sine, Tanh, Golden knee), Tube preamp, Transformer,
Multi-stage and Tape machine (its six alignments), names a legacy preset's
curve, and the published PCM equals a cold render to the bit for the sine and
golden curves and for 15 ips Colored at 3 dB, with the meters on the audible
plan and the A/B caches kept.

## Tests

- `AnalogSaturationTest`: on circuits with memory, filters and delay, the
  linear response equals an independent one-pass render hop by hop, an
  all-pass ahead of the curve raises a limited song's peak and the circuit is
  still driven only as far as it saturates, the most saturated moment loses
  exactly the amount with the loudness kept, the stretches join into one
  continuous pass, a level that rises with the drive is corrected by the
  renders, zero and silence leave the input alone, the same song saturates
  alike at any level, and a least drive that saturates too much still gives
  the exact amount.
- `SaturationProcessorTest`: in the real pipeline, the curves render as before
  to the bit; every model and every tape alignment takes off exactly the
  amount against an independent linear response, with the loudness kept and
  the meter's deepest reading the knob's; on a limited song the Transformer
  and the tape alignments that raise its peak most reach 1 dB without the knee
  and far below their most drive; the streamed model plays what the whole
  render made; zero is a bypass for every type; older presets keep their curve.
- `tools/diagnostics/SaturationTrackAudit.java`: the same on whole real songs
  through the packaged classes, with time, renders, drive, knee, the meter, the
  song's peak against the linear response and the input, determinism and the
  source unchanged.
- `tools/diagnostics/ClipsUiAudit.java`: the real controls, the menu and its
  submenus, and the published PCM equal to a cold render.
