# Stereo / tonal measurement coverage

## Informe de resultados

[Leer el informe completo en español](INFORME-RESULTADOS.md): resultados por familia, las 50 canciones con sus fuentes y mediciones, distribución espectral, dinámica, método y límites.

El informe se genera desde el estudio verificado con `node docs/research/build_stereo_report.mjs` (desde la raíz del repositorio). La vista local está en `http://127.0.0.1:8767/?view=1` mientras está activo su servidor. El proyecto del visor y su exportación HTML portátil se conservan bajo `dist/stereo-report-20260928/`; el documento de arriba no depende de ese servidor.

Accepted: 50/50. Complete: True.

These are public streaming delivery measurements. No audio is included. A recording is one observation; overlapping time windows are not independent masters. Absolute browser dBFS is not compared between songs.

| Family | Accepted / selected | Coverage complete |
|---|---:|---|
| acoustic-folk-country | 5 / 5 | True |
| electronic | 5 / 5 | True |
| hip-hop | 5 / 5 | True |
| jazz-blues | 5 / 5 | True |
| latin | 5 / 5 | True |
| metal | 5 / 5 | True |
| orchestral-cinematic | 5 / 5 | True |
| pop | 5 / 5 | True |
| rnb-soul-funk | 5 / 5 | True |
| rock | 5 / 5 | True |

See `study.json` for source URLs, exact editions, capture checks, metric definitions and normalized features. Compressed `records/*.json.gz` preserve the powers/timelines and provenance, not PCM.

No universal or perceptually ideal Mid/Side ratio or EQ curve is claimed.

## Additional data for a future style-oriented EQ

The same captures also provide normalized Left/Right/Mid/Side spectra,
power-weighted and equal-time normalized means, window percentiles, broad-band
energy, 15-second tonal evolution, low-energy reliability flags, normalized
spectral change, 400 ms RMS percentile ranges and sample crest. The stored powers
allow these calculations to be repeated without playing the recordings again.
This is not an implementation of a new QuickMaster processing module.

`verification.json` records the final recomputation and integrity check: 50/50
accepted, ten families, five recordings each. Reference durations total
14,920.01 seconds. Forty-eight captures have per-second sample-clock evidence;
the first two use their preserved 30-second progress events. All fifty have
captured source identity; 49 additionally retain the selected public catalogue
item. The D'Angelo reference has direct official UMG watch-page identity instead.

The source boundary is intentionally stopped approximately 250 ms before
post-roll (20 ms for the first two Pop references); coverage exceeds 99.5% and
short silent capture padding is documented. Lossy platform delivery, resampling,
different release eras and a live orchestral reference limit interpretation.
Common constant player gain cancels from normalized descriptors, but absolute
browser dBFS is not comparable between tracks. No LUFS, EBU LRA, true peak,
magnitude-squared coherence or semantic verse/chorus classification is claimed.

The final research checks passed: 58 Python and 15 Node tests, plus actual silent
capture fixtures with known timing, spectral powers and a separate interfering
source that did not enter the selected capture. Earlier failed captures are
excluded, not averaged into the reference distributions.
