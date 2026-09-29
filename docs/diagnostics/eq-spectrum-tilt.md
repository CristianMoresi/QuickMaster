# EQ spectrum display tilt

The analyzer's default display slope is **4.5 dB/octave around 1 kHz**, matching
the documented default in the [FabFilter Pro-Q 4 manual, Spectrum analyzer](https://www.fabfilter.com/downloads/pdf/help/ffproq4-manual.pdf).
This is not a 6 dB/octave audio filter and not a change to the EQ transfer curve.

`SpectrumDisplay` applies `4.5 * log2(f / 1000)` only when projecting measured
spectrum levels to canvas coordinates. The same path handles live and stopped
spectra. Plot headroom follows the tilted peak, with the prior slow live release;
silence and above-Nyquist bins stay at the bottom. No extra track analysis,
audio filter or thread is required.

Six unit tests verify the octave values, 1 kHz pivot, expected pink-noise slope,
plot range, live/static agreement, silence, release and unchanged PCM/analysis.
`SpectrumTiltUiAudit` loads the actual FXML/controller, imports a full-track MP3,
checks the 4.5 setting, snapshots 1360x900 and 1100x760 layouts, and asserts that
redrawing does not alter source/render/preset/measurements or schedule analysis.
No sound device is opened. Local evidence: `dist/mp3-port-20260929/ui-preview/`.

Delivery verified on 2026-09-29: full 936-test QuickMaster and 171-test DSPark
suites pass; 386 regression tests also pass against the installed JARs.
The installed-image UI probe passes at both sizes, with snapshots under
`dist/mp3-port-20260929/installed-ui/`. The Windows EXE started cleanly at
18:47:02. Build/installed JAR SHA-256:
`b93f7d49255cded28fc6535f19638adc8d5ee3bb2cfc8f224eddc7d43ac4e1cb`.
See the [combined local delivery record](mp3-decoder-port.md#final-local-delivery).

Pink noise falls roughly 3 dB/octave in this mean-bin-power analyzer, so a 4.5
display tilt deliberately leaves an approximately 1.5 dB/octave rise. Matching
the requested Pro-Q default does not mean forcing all pink-noise plots flat.
