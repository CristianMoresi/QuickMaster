# Auto EQ on the DSPark tonal balance

Scope: replace the slope-matching Auto EQ with the DSPark `TonalBalance`
(ported in [DSPark-Java 0.2.6](dspark-java-0.2.6-tonal-balance.md), with the
shares of [0.2.7](dspark-java-0.2.7-raising-lowering.md)), keep it the first
stage of the EQ block, and deliver it through the same offline render, cache
and position playback the chain already relies on.

## Two controls

An automatic module is driven by two controls: one that says how much, from
nothing to all of it, and one choice. The tonal balance has a dozen
parameters; the Auto EQ shows two and decides the rest.

| Control | Range | Default | What it does |
|---|---|---|---|
| Amount | 0 to 100 % | 25 % | How much the Auto EQ does. At 0 % the sound is untouched; every correction, and the most a band can move, grows with it |
| Cut/Boost | 100/0 to 0/100 | 100/100 | What the correction is made of: the share of what is in excess that is lowered, and the share of what is lacking that is raised |

What follows from them (`AutoEqProcessor.settings()`), with `a` the amount:

| Parameter of the tonal balance | Value |
|---|---|
| Balance (share of the way the broad tone moves) | a |
| Trim peaks | a |
| Fill dips | 0.6 a |
| Range (largest correction of a band) | 12 dB times a |
| Lowering (share of what it would lower) | 100 % from Cut to the middle, falling to 0 % at Boost |
| Raising (share of what it would raise) | 0 % at Cut, rising to 100 % at the middle and from there on |
| Detail | 1/4 octave |
| Tilt, knees, floor, frequency range, response | the tonal balance's own defaults |

### Amount

Amount is the share of everything, so what the Auto EQ does at an amount is
that share of what it does at full, and at zero it is nothing: the output is
the input to the bit and no curve is drawn, wherever Cut/Boost stands. Half
the amount is exactly the tonal balance's defaults and its "Gentle" preset
(half way, peaks by half, dips by three tenths, 6 dB at most); all of it is
its "Strong" preset in substance (all the way, 12 dB at most). The default is
a quarter: a mastering tool should start gently.

Measured through the real interface on a 280 s master (the lowest and the
highest correction drawn anywhere along the track, from 20 Hz to 20 kHz):

| Amount | Drawn | Largest allowed |
|---|---|---|
| 0 % | nothing; published audio identical to the source | 0 dB |
| 25 % | -3.00 to +2.52 dB | 3 dB |
| 50 % | -6.00 to +4.99 dB | 6 dB |
| 100 % | -12.00 to +9.77 dB | 12 dB |

### Cut/Boost

The correction lowers what is in excess and raises what is lacking. Cut/Boost
gives each of the two its share, and the face of the control shows both, in
percent: 100/100 in the middle, 100/0 at Cut, 0/100 at Boost. One of the two
is always whole. Each share reaches everything that goes its way
(`TonalBalance.setRaising`, `setLowering`): the lift or the cut of the broad
tone, the filling of dips or the trimming of peaks, and the offset with which
the tonal balance keeps the loudness, which raises when the corrections took
loudness away and lowers when they added some.

- **In the middle** it does both and the loudness the model hears is kept: the
  correction redistributes.
- **Toward Cut** what it raises fades out. At 100/0 no point of the curve is
  over zero: the Auto EQ only lowers, and the level goes down with it.
- **Toward Boost** what it lowers fades out. At 0/100 no point of the curve is
  under zero: it only raises, and the level goes up.

Measured on the same master at an amount of 100 %:

| Cut/Boost | Drawn |
|---|---|
| 100/0 (Cut) | -12.00 to 0.00 dB |
| 100/50 | -12.00 to +5.09 dB |
| 100/100 | -12.00 to +9.77 dB |
| 50/100 | -6.95 to +8.35 dB |
| 0/100 (Boost) | 0.00 to +8.15 dB |

The balance of the tonal balance has an asymmetry of its own
(`setBalanceAsymmetry`), which shifts the work between lifting and cutting
but leaves the offset whole: lowering alone, the offset gives the loudness
back by raising everything else, and the curve still stood 2.7 dB over zero
on this master. The shares are what makes the two ends what they say.

The slope-matching Auto EQ had four controls (Amount, Attack, Release and a
target curve) and knew nothing of how loud a band is heard. Its Amount is the
Amount of this one; the rest has no counterpart.

No gain is hidden in the correction and the render is not scaled afterwards.

## Heard at once

A whole render takes seconds, so a control moved is first heard on a
provisional window: the interactive preview that already serves the manual EQ
and the Stereo Image now covers the Auto EQ. While the whole render is on its
way, each half second ahead of the playhead is balanced on its own with the
new settings (`AutoEqProcessor.renderPreview`, one stream at realtime quality
that settles on the 1.5 s before the window) and played by position, at the
base rate and in the oversampled pass alike. It is an audition, never an
export: the whole render replaces it as soon as it is published.

Measured through the real interface on a 280 s master: the first window
reaches the player 145 ms after the controls move.

## The offline render

`TonalBalance` is a streaming processor with 774 samples of latency (at 44.1
and 48 kHz). `AutoEqProcessor` renders the whole file through it once, at
render quality, and plays the result back by position, as before.
`TonalBalanceRender` adds what an offline render allows:

- **No latency.** The filter's delay is taken off; the render is as long as the
  source and aligned with it.
- **A settled opening.** The stream starts fifteen seconds before the first
  frame, on the opening played backwards, so the correction at the first frame
  is already that of the frames after it. Past the end the programme is
  mirrored in the same way while the filter flushes.
- **Mono as it is heard.** A mono programme is analysed as the same signal in
  both channels, so it takes exactly the correction its stereo copy takes.
- **Stretches in parallel, checked.** See below.

### Stretches, and the check at every join

One stream renders stereo at 48 kHz at about 12 times real time: 19 to 26 s
for the masters below. The programme is therefore cut into stretches of about
20 s, each rendered on its own thread after a warm-up on the 15 s of programme
before it. Every stretch starts on a grid of sixteen curve hops counted from
the start of the programme, so all of them read and draw at the very frames
one stream would.

A warm-up is enough only while the analysis has forgotten what came before it.
It forgets within seconds in running music, but not in a quiet passage: there
the stream that came from the loud section still holds a decaying memory of it
in bands that are now silent, and a stretch that begins inside the passage
never heard it. When the music comes back the two would correct it differently
for a moment (measured before the check existed: 27 dB under the programme at
the worst sample, on a breakdown of one of the masters below).

So every join is checked. The stretch before runs 0.1 s past the join, and the
curves it draws there are compared with those the next stretch draws at the
same frames:

- Where they agree within 0.005 dB, the two renders are the same to far under
  the programme and a 100 ms crossfade joins them.
- Where they do not, the next stretch is discarded and the stream before
  carries on through it, exactly as one stream would. The join after it is
  then checked against that stream.

The joins are settled in order from the start of the programme, so the stream
that carries on is always one whose own render stands: the first stream is
the one stream's own beginning, and every stretch after it either agrees with
a stream that stands or is rendered by it. Two joins in a row that fail are
therefore taken one after the other by the same stream. Joins of passages
apart are settled in the same pass, each on a thread of its own, on the
assumption that the stream before each of them stands; where it turns out not
to, the stretch is taken again in its turn.

On real masters the two cases stand apart: of the 46 joins of the four masters
below, the 43 that agree draw curves 0.0034 dB apart at most (0.0002 to
0.0007 dB at the median of each master), and the three that do not, 0.013,
0.18 and 0.24 dB.

The cut and the checks depend on the programme alone, never on the threads at
hand (every logical processor but two, a step under normal priority so playback
and the interface keep their turn): a render is the same bits on one thread as
on thirty.

## Measured on real masters

`tools/diagnostics/AutoEqTrackAudit.java`, Amount 50 % with Cut/Boost in the
middle (the tonal balance's own defaults), four finished stereo masters at
48 kHz. Source files read only; nothing rendered is written.

| Master | Length | Integrated loudness | True peak | 8 kHz against 200 Hz |
|---|---|---|---|---|
| By Now | 280.0 s | -12.75 to -13.52 LUFS (-0.77 LU) | -0.10 to +0.47 dBTP (+0.57) | -22.7 to -20.6 dB |
| Quiet Gold | 206.3 s | -11.74 to -11.94 LUFS (-0.20 LU) | +0.49 to +3.62 dBTP (+3.13) | -20.0 to -17.5 dB |
| Billie Jean (80s Glam Metal) | 217.9 s | -10.30 to -10.76 LUFS (-0.46 LU) | -0.00 to +1.23 dBTP (+1.23) | -23.2 to -20.8 dB |
| Wicked Game (80s Synthwave) | 307.7 s | -15.17 to -15.91 LUFS (-0.73 LU) | -4.34 to -3.57 dBTP (+0.77) | -20.4 to -18.2 dB |

The parallel render against the same track rendered as one stream on one
thread (dB under the programme):

| Master | Joins | Carried on | Passes | Whole track | Worst half second after a join | Worst sample | Render | One stream |
|---|---|---|---|---|---|---|---|---|
| By Now | 13 | 0 | 0 | 100.2 | 83.7 | 66.5 | 4.7 s | 24.4 s |
| Quiet Gold | 9 | 1 | 1 | 101.3 | 80.0 | 70.8 | 6.7 s | 18.2 s |
| Billie Jean | 10 | 0 | 0 | 117.5 | 100.7 | 84.6 | 4.9 s | 19.7 s |
| Wicked Game | 14 | 2 | 1 | 105.0 | 86.0 | 74.9 | 7.6 s | 26.7 s |

Measured on the packaged application. Render times on a Ryzen 9 9950X3D (16
cores, 32 threads). In every case the render was aligned with its source, no
correction passed the range and the first stretch was the one stream's own
beginning, bit for bit. The two stretches carried on in Wicked Game lie one
stretch apart and were settled in the same pass.

What the numbers say:

- **Tone.** All four masters are darker than even loudness across the hearing
  range, so the balance lowers the low end by about 1 to 2 dB and raises the
  top by about 1 dB on average; the level at 8 kHz against 200 Hz rises by 2.1
  to 2.5 dB. The curve moves with the music: at some moments a band reaches
  the range.
- **Loudness.** The model keeps its own loudness (it reports a change of
  0.000 dB), but that is not BS.1770: lowering the low end, which carries most
  of the energy, and raising the top costs 0.2 to 0.8 LU of integrated
  loudness. Nothing compensates it in secret.
- **True peak.** An equalizer rotates phase, and on a master that was already
  limited or clipped that raises the peaks: +0.6 to +3.1 dB here, the most on
  the one that arrives over full scale. The Auto EQ does not scale its render
  to hide it; the Peak Normalizer and the limiters after it see the real peak
  and set the ceiling, as they do after the manual EQ.
- **Threads.** With several threads at work each one renders about a third
  slower than alone on this machine (two streams in one process run at 7.2
  times real time each against 12.3 alone, two processes at 10.5 each), which
  is the operating system's placement of threads on cores, not contention in
  the program. Twenty-second stretches use the machine best: a pass that
  carries stretches on costs about 2 s, whether it carries one or several
  that lie apart.

### The default and the two ends of Cut/Boost

The same four masters on the packaged application, at the default amount
(25 %, the middle of Cut/Boost) and at an amount of 50 % at each end of
Cut/Boost: integrated loudness and true peak against the source, and the
lowest and highest correction drawn anywhere along the track.

| Master | 25 %, 100/100 | 50 %, Cut (100/0) | 50 %, Boost (0/100) |
|---|---|---|---|
| By Now | -0.43 LU, +0.29 dB TP, -3.00 to +2.52 dB | -1.62 LU, -0.46 dB TP, -6.00 to 0.00 dB | +0.09 LU, +0.50 dB TP, 0.00 to +4.07 dB |
| Quiet Gold | -0.13 LU, +1.44 dB TP, -3.00 to +2.39 dB | -0.71 LU, +0.29 dB TP, -6.00 to 0.00 dB | +0.21 LU, +2.01 dB TP, 0.00 to +4.47 dB |
| Billie Jean | -0.27 LU, +0.31 dB TP, -2.97 to +1.69 dB | -1.02 LU, -0.02 dB TP, -6.00 to 0.00 dB | +0.14 LU, +0.38 dB TP, 0.00 to +2.94 dB |
| Wicked Game | -0.42 LU, +0.29 dB TP, -3.00 to +3.00 dB | -1.31 LU, +0.02 dB TP, -6.00 to 0.00 dB | +0.13 LU, +0.62 dB TP, 0.00 to +4.47 dB |

At Cut nothing is raised and the level goes down; at Boost nothing is lowered
and it goes up a little. Lowering only also keeps the true peak where it was
or lowers it, where the middle raised it by phase rotation. Every render was
aligned with its source and stood 100 to 123 dB under the programme from the
same track rendered as one stream; at 50 % in the middle the four masters
give exactly the numbers of the tables above.

Through the real interface (`tools/diagnostics/AutoEqUiAudit.java`, By Now):
a control moved is heard after 144 to 158 ms on the provisional window, and the
whole chain is published again 6.3 to 8.4 s later, 1.7 s of which is the rest
of the chain. After every edit the published audio was bit-identical to an
independent render of the Auto EQ with the same two settings.

## Presets

The schema version stays at 1; new fields take defaults when absent, as with
earlier additions. `autoEqAmount` keeps its name and `autoEqCutBoost` is new
(-1 Cut, 0 the middle, 1 Boost).

Presets saved by earlier versions still load: their Amount is the Amount, and
Cut/Boost takes the middle. The target curve, Attack and Release of the
slope-matching Auto EQ have no counterpart (the reference is the balance of
the ear's model, whose own time behaviour replaces them) and are not read; an
amount out of range is rejected before anything is applied.

## Tests

In `com.quickmaster.processing.eq`, `com.quickmaster.processing`,
`com.quickmaster.config` and `com.quickmaster.ui`:

- `TonalBalanceRenderTest`: the render is the same bits on 1, 4 or 64 threads,
  carry-on rounds included; stretches joined by crossfades alone against one
  stream (106 dB under the programme, first stretch bit-exact, one to the
  precision of the samples 16 s after a join); a programme with a long quiet
  passage, where the unchecked stretches leave the one stream and the checked
  ones do not; with no two stretches allowed to agree, the first stream takes
  every stretch in its turn and the render is the one stream's, bit for bit;
  two quiet passages apart, each with two failing joins in a row, are settled
  in two passes and stand 102 dB under the programme from the one stream;
  mono equals its stereo copy to the bit; the render is aligned
  with its source; the opening is balanced from its first frame; a curve is
  kept about twelve times a second across the joins; cancellation on one
  thread and on several; files of a few frames; the mirrored positions.
- `AutoEqProcessorTest`: what the two controls ask of the tonal balance at
  every amount and every Cut/Boost position; more Amount darkens a bright
  programme further, with its level kept in the middle; the correction at an
  amount is that share of the correction at full, down to 2 %; Amount lowers
  a tone standing over the noise, never by more than 12 dB; at Cut no point
  of the curve is over zero and the level goes down, at Boost none is under
  and it goes up, half way lies between; Amount at zero returns the input to
  the bit and draws nothing wherever Cut/Boost stands; the correction shown
  stays within the range and follows the playhead; when a render is awaited;
  a provisional window is played by position, close to the whole render, at
  the base rate and at an oversampled one.
- `PreviewWindowRendererTest`: an Auto EQ of new settings is auditioned on the
  window at once, mono and stereo, with and without oversampling; one that
  holds the render of its settings is played by position; switched off it is
  not heard.
- `AutoEqAuditTest`: polarity and dual mono leave the linked correction
  unchanged to the bit at eight rates from 8 to 192 kHz; non-finite parameters
  and PCM are rejected; an empty analysis cannot replay an old track; a render
  in progress ends when its token is cancelled or its thread interrupted.
- `AutoEqCacheTest`: every sample, the rate and both controls are part of the
  cache key.
- `PresetValidationTest`: the bounds of the two controls; a preset of the
  slope-matching Auto EQ keeps its amount; a preset saved now holds the two
  controls and nothing of earlier Auto EQs.
- `KnobTypedValueTest`: a share shown in percent is typed in percent.
- `CutBoostTextTest`: Cut/Boost is written as its two shares, read back as
  the same position, and typed as two shares or as a lean in percent.

`tools/diagnostics/AutoEqTrackAudit.java` measures a real track without writing
audio, and `tools/diagnostics/AutoEqUiAudit.java` drives the real controls of
the interface and compares the published audio with an independent render.
