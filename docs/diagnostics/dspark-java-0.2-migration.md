# DSPark Java 0.2: audited migration

2026-09-27. Library and complete QuickMaster suite qualified; final portable
delivered to Program Files at 09:10:57 after renewed user authorization.
Full-tree hashes, normal EXE startup and installed acceptance pass. No release.

Sources and existing 114 tests copied from `E:/Code/Libs/java/dsp`, previously
unversioned, into `vendor/dspark-java`. The external Java and C++ worktrees remain
untouched. Upstream reference is Cristian Moresi's DSPark C++ main commit
`9330f1cd29164f6d33e7876bc422627a200ec919`; the Java version is not a claim of
whole-library parity or a C++ release number. Native SIMD, reverbs, synthesizers
and plugin adapters are not imported.

## Audited changes and compatibility

- FFTComplex: Stockham radix-4 autosort plus closing radix-2, split reusable
  buffers, inverse by conjugation. Uses double internal arithmetic with float
  public I/O. This deliberately exceeds the precision of the C++ float path:
  the initial all-float port failed the predeclared coherent-tone tolerance at
  N=16384. Tolerances were not relaxed. FFTReal public packing is unchanged.
- OversamplingEngine: contiguous even/odd decimation histories; no convolution
  of mathematically zero half-band taps. Same kernel, phase, latency and up path.
  Tiny-block latency measurement now scans the whole impulse tail, not one block.
- SmoothedValue: initialized defaults, per-jump linear duration, mid-ramp timing
  updates and zero-time immediate behavior. Unlike the inspected C++ code,
  zero time explicitly applies to CHASE too, matching the documented semantics.
- BiquadCoeffs.peakMatched: Vicanek design copied from the pinned C++ equations.
  QuickMaster's existing MasterEqualizer continues to use the RBJ `peak` factory;
  no silent change of saved EQ presets to a different bell algorithm.
- DspMath: exp/log conversions, retaining the Java nonpositive gain floor and
  standard IEEE NaN/infinity behavior; no approximate logarithms.
- TruePeak: same official Annex 2 coefficients and per-phase accumulation order,
  mirrored history and four accumulators reduce ring-index work. Finite file
  measurement now flushes the FIR tail. This intentionally fixes under-reporting
  at hard endings, not a change of measurement standard or coefficient table.
- LimiterEnvelope: backward pass overwrites instantaneous requirements after
  reading them; removes one whole-track double array with identical arithmetic.

No new files, threads, executors, callbacks, native calls, or retained source PCM
are reachable through Leveler's allowed FFTReal/DspMath/TruePeak boundary. FFT
scratch is per instance. The existing allowed API call tuples are unchanged.
Historical 0.1 JAR and M004 manifests/pins remain intact; only the active successor
uses `5a9e6d8e3797bc55d4dcbf462927db6918176c5f06beb942c9ecc2e271d2edc0`.
The executing JAR is separately checked against that literal, not merely against
an observed hash in a mutable fixture. Rebuilds use a fixed output timestamp.

Other upstream changes were inspected, not copied solely because of a class name.
Java Saturation already keeps its ADAA history, antiderivative and quotient in
double; it is a ceiling/knee mastering processor, not the C++ tanh/wavefolder API.
QuickMaster's Peak/Beat/Leveler use offline gain schedules rather than the native
C++ Compressor/AutoGain/Limiter classes. Its MasterEqualizer is a custom combined
static/dynamic engine; this update preserves its RBJ/rectified-detector voicing,
not the native DynamicEQ's new Hilbert detector or its changed default bell.
Therefore 0.2 denotes the explicitly audited kernel migration above, not a
feature-for-feature port of the entire C++ 1.8 library or new plugin automation.

## Independent checks already run

- Existing library suite plus new direct DFT / analytic endpoint oracle through
  65536 points. Complex relative RMS <= 1e-6, absolute error <= 3e-6 sqrt(N),
  round-trip <= 2e-6, fixed before migration.
- Actual MSVC-compiled upstream headers generated 9566 complex bins and 240 bell
  coefficient vectors. Stored fixture `upstream-9330f1c.txt`; generator
  `tools/diagnostics/DsparkCppVectors.cpp`. Bell vectors additionally check pole
  stability and centre gain; not just self-comparison of two Java routines.
- 84 old/new oversampling comparisons, stereo, nonlinear clipping, four qualities,
  factors 1..64 and blocks 1/17/256: max sample difference zero in that corpus.
- 60 old/new limiter-envelope cases (empty, tiny and long arrays, several
  thresholds and attacks) and 30000 streaming true-peak samples are bitexact.
- New partition tests compare whole versus 1/17/64-frame blocks, reset, phase,
  stereo isolation and reported latencies for all factors/qualities.
- Finite-tail true peak tests explicitly establish that their fixtures fail the
  unflushed path before comparing the complete measure to a padded stream.
- Packaged old/new mastering-chain comparison: twelve mono/stereo 44.1/48/96k
  cases, static bell +2.5 dB in both phase modes, Auto EQ, fades, dynamics, clips,
  multiband and normalizer. Maximum absolute error 8.345e-7, RMS 8.732e-8 against
  fixed 1e-5/1e-6 limits. Broadband is excluded from this equivalence claim
  because its finite-tail/time-alignment fix is intentional; the separate
  full-chain current-preset oracle includes it and passes bitexact.

Final isolated warm kernel diagnostic, one sequential run, Ryzen 9950X3D,
Temurin 25 (`dspark-kernels-final.log`): FFT forward+inverse old/new microseconds:
1024=10.046/6.236, 8192=99.668/56.005, 16384=214.125/140.321,
65536=1087.308/619.932. 8x stereo oversampling of 1024 frames=583.645/374.487
microseconds. Not p95 or a promise for every CPU. Repeated whole-application
timings and measured memory limits are in [P1 validation](performance-p1-validation.md).

Final clean QuickMaster build passes 715 tests in 102 reports, with zero
failures/errors/skips, 106 musical variants and 94 official readings in the
reopened package. JAR SHA-256:
`ec80915fe0e5fc773de33fffde3eb82f9ccaf640e0f38ee2fc137f7c112ca73a`.
All 170 application classes match the benchmark candidate. Real-song candidate
acceptance, repeated performance/memory measurements and Windows app-image
validation are complete. Program Files contains that exact JAR and the pinned
DSPark 0.2 dependency; all 201 files match the validated portable. Installed
real-track/UI/Beat/source-error/full-chain acceptance passes, including zero raw
bit differences against a fresh-controller render after rapid parameter changes.
The previous P0 image is retained in `C:/Program Files/QuickMaster-backup-20260927-091050`.

The first P1 full-suite attempt was deliberately stopped (PACKAGE_EXIT=1, not a
passing build) after source review exposed a bypass adoption path still able to
remap limiters on FX. Current-snapshot publication now has an explicit no-remap
API, with a regression test including retained peaks and changed off-state knobs.
This attempt remains in `p1-full-package.log`; it will not be counted as complete.
