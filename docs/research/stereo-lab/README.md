# Stereo and tonal research tools

This is isolated research tooling, not QuickMaster application DSP. Current
scope: 50 preselected recordings, ten broad musical families, five distinct
artists per family. No app Stereo Image or genre-EQ implementation is enabled by
these tools. See `../stereo-campaign-state-20260928.md` for the active campaign.

## Silent capture

`launch.mjs --watch JOB.json OUTPUT` opens normal public playback in the tool's
own hidden Electron window. No Chrome extension, user browser profile, account,
microphone, desktop loopback, downloader or media URL extraction is used.
Optional cookies are rejected. Ordinary available skip controls may be used;
advertising requests are never intercepted. Access barriers stop the attempt.

Both windows are muted before page loading. Only the owned source webContents
can be captured. The receiver uses `sinkId: {type: 'none'}`; its worklet emits
zeros to its output. The source stays muted after capture stops. PCM exists only
transiently in memory and a pipe to the analyzer. The saved files contain numbers
and provenance, never an audio recording.

The Windows silent context requires `latencyHint: 'playback'` on the tested
runtime. Interactive buffering was observed advancing at roughly 0.65–0.70×;
those attempts were quarantined. A synthetic route fixture and continuous frame
clock checks protect against recurrence. Do not infer data quality from a zero
process exit code. `capture_quality.py` independently audits each candidate.

Content is played from zero to approximately 250 ms before its end to avoid
post-roll contamination. The exact boundary is saved; coverage must exceed
99.5%. Short silent route padding is included in the capture. These are streaming
delivery descriptors, NOT a sample-exact copy of the original production master.

## Extra data for a future spectral processor

`../tonal_features.py` derives new descriptors from the saved linear FFT powers;
no additional playback or audio decoding is required:

- Normalized spectrum of stereo, Left, Right, Mid and Side.
- Both power-weighted pooling and equal-time normalized spectral means. These
  answer different questions; a loud chorus cannot silently dominate the latter.
- Window percentiles and 15-second temporal evolution; these are time bins, not
  recognized verses or choruses.
- Exact broad bands at 120 Hz, 300 Hz, 2 kHz and 10 kHz, plus the saved finer
  third-octave-like FFT grid. No unmeasured sub-band is invented by interpolation.
- Approximate normalized power density per Hz, with reliability flags for
  near-empty bands; do not turn codec roll-off or numerical leakage into EQ gain.
- Normalized spectral-change descriptors, 400 ms RMS percentile range and a
  defined sample-crest measurement.

Common constant player attenuation cancels from normalized spectra, Side/Mid,
crest and percentile ranges. Absolute dBFS is NOT comparable between tracks.
Platform loudness processing, lossy encoding and resampling are limitations;
these spectra do not prove the exact EQ of a studio WAV. Stable-volume settings,
constant player gain, channel count and route processing are checked separately.

LUFS, EBU LRA, true peak, magnitude-squared coherence and semantic musical sections
are **not** calculated. RMS percentile range is not relabeled as LRA. Future EQ
targets will require bounded/smoothed gain, reliable bands, user control and
listening validation; a genre mean is neither an ideal curve nor an instruction
to flatten every recording.

## Reproduce and inspect

Tested local runtimes: Node 24.18.0, Electron 44.4.5, Python 3.14.6,
NumPy 2.5.1 and SciPy 1.18.1 (SciPy is used in local-file test fixtures).
Electron is installed under the ignored campaign directory, not as a system
application. Each capture records its Electron/Chromium runtime; each final
assembly records its Python/NumPy versions and hashes of its method files.

From the canonical QuickMaster repository root:

```powershell
python -B -m unittest discover -s docs/research -p "test_stereo*.py" -q
node --test docs/research/stereo-lab/test-lab.mjs
node docs/research/stereo-lab/launch.mjs
node docs/research/stereo-lab/capture-queue.mjs JOBS_DIR OUTPUT_DIR 4
python -B docs/research/tonal_features.py --input measurement.json --output tonal.json
python -B docs/research/assemble_stereo_study.py --jobs JOBS_DIR --root CAPTURE_ROOT --output NEW_SNAPSHOT_DIR
```

Only repeat failed captures after reviewing their cause. Preserve successful
capture results. A `STOP` file in a queue output directory stops dispatch of new
work while allowing active recordings to finish. Each assembly creates a new
snapshot and refuses to overwrite one. Its compressed JSON records preserve
power timelines and provenance for later calculations without audio. Every song
is one observation in family summaries; overlapping windows do not inflate the
number of independent references. Five recordings are exploratory references,
not a population estimate or a demonstrated perceptual optimum.
