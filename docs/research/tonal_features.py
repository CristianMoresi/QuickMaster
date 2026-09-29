"""Additional tonal/dynamic descriptors from saved research powers, never audio.

These are linear-power/FFT measurements of a delivered recording. They are not
LUFS, perceptual EQ targets, IEC filters, true peak or musical section labels.
The input retains the finer timeline so future hypotheses can be tested without
replaying the recording. JSON has no NaNs and exact-null components stay null.
"""
import argparse
import json
from pathlib import Path
import numpy as np
from stereo_probe import ratio_db, sha256

VERSION = 1
PERCENTILES = [10, 50, 90]


def _fractions(values):
    total = float(values.sum())
    return (values / total).tolist() if total > 0 else None


def _db(value):
    return ratio_db(float(value), 1.)


def expand_tonal(result):
    edges = np.asarray(result['band_edges_hz'], dtype=float)
    if edges.ndim != 1 or not np.isfinite(edges).all() or np.any(np.diff(edges) <= 0):
        raise ValueError('Invalid band edges')
    windows = [r for r in result['long_windows'] if not r['partial']
               and 'spectral_lr_cross_powers' in r]
    base = {'version': VERSION, 'not_computed': [
        'BS.1770 integrated LUFS', 'EBU LRA', 'true peak',
        'magnitude-squared coherence', 'semantic sections', 'ideal EQ curve'],
        'definition': 'Power: (L^2+R^2)/2 = M^2+S^2; M=(L+R)/2, S=(L-R)/2',
        'spectral_weighting': 'Full 3 s periodic Hann FFT windows, hop 1.5 s',
        'normalization': 'Each window divided by its own total stereo power; '
            'pooled power-weighted and equal-time normalized means both retained',
        'reference_reliability': 'Component-band power at least -100 dBFS and '
            'at least -60 dB relative to that window total stereo power. '
            'Raw near-empty bands remain stored but are not trustworthy EQ targets.',
        'gate': 'At least -80 dBFS stereo power and at least P95 minus 40 dB'}
    if not windows:
        return {**base, 'status': 'no_full_active_windows', 'windows': 0}
    powers = np.asarray([r['mid_power'] + r['side_power'] for r in windows])
    if not np.isfinite(powers).all() or np.any(powers < 0):
        raise ValueError('Invalid time-domain powers')
    threshold = max(1e-8, float(np.percentile(powers, 95)) * 1e-4)
    windows = [r for r, p in zip(windows, powers) if p >= threshold]
    if not windows:
        return {**base, 'status': 'no_full_active_windows', 'windows': 0}
    values = np.asarray([r['spectral_lr_cross_powers'] for r in windows], dtype=float)
    if values.shape != (len(windows), len(edges)-1, 3) or not np.isfinite(values).all():
        raise ValueError('Malformed spectral powers')
    left, right, cross = values[:,:,0], values[:,:,1], values[:,:,2]
    if np.any(left < 0) or np.any(right < 0):
        raise ValueError('Negative spectral power')
    # The real cross term must satisfy the Cauchy-Schwarz bound. Tolerate only
    # floating point cancellation, not corrupt inputs masked by max(0,...).
    if np.any(np.abs(cross) > np.sqrt(left*right) + (left+right)*1e-12):
        raise ValueError('Impossible cross-power')
    mid = np.maximum(0., (left+right+2*cross)/4)
    side = np.maximum(0., (left+right-2*cross)/4)
    if result['full_file']['mid_power'] == 0: mid[:] = 0
    if result['full_file']['side_power'] == 0: side[:] = 0
    components = {'stereo': (left+right)/2, 'left': left, 'right': right,
                  'mid': mid, 'side': side}
    stereo = components['stereo']
    totals = stereo.sum(axis=1)
    if np.any(totals <= 0):
        raise ValueError('Active time-domain window has no spectral energy')
    normalized = stereo / totals[:,None]
    component_fractions = {name: _fractions(v.mean(axis=0)) for name,v in components.items()}
    mean_shape = normalized.mean(axis=0)
    bands = []
    for i,(lo,hi) in enumerate(zip(edges[:-1],edges[1:])):
        percentiles = np.percentile(normalized[:,i], PERCENTILES)
        bands.append({'lo_hz': float(lo), 'hi_hz': float(hi),
            'pooled_fraction_by_component': {name: f[i] if f is not None else None
                                           for name,f in component_fractions.items()},
            'equal_time_mean_fraction': float(mean_shape[i]),
            'reliable_window_fraction_by_component': {name:float(np.mean(
                v[:,i] >= np.maximum(1e-10,totals*1e-6))) for name,v in components.items()},
            'window_fraction_p10_p50_p90': percentiles.tolist(),
            'window_fraction_p10_p50_p90_db': [_db(v) for v in percentiles],
            'approx_normalized_density_db_per_hz': _db(mean_shape[i]/(hi-lo))})
    # Only use exact edges present in the original measurements. Never invent
    # 60/500/1000 Hz splits by claiming a narrow FFT band can be subdivided.
    broad_edges = [0., *[x for x in (120.,300.,2000.,10000.) if x < edges[-1]], edges[-1]]
    broad = []
    for lo,hi in zip(broad_edges[:-1],broad_edges[1:]):
        if lo not in edges or hi not in edges:
            raise ValueError('Required broad-band edge absent')
        mask = (edges[:-1] >= lo) & (edges[1:] <= hi)
        local = normalized[:,mask].sum(axis=1)
        broad.append({'lo_hz': lo, 'hi_hz': float(hi),
            'pooled_fraction_by_component': {name: float(np.asarray(f)[mask].sum()) if f is not None else None
                                           for name,f in component_fractions.items()},
            'equal_time_mean_fraction': float(local.mean()),
            'window_fraction_p10_p50_p90': np.percentile(local,PERCENTILES).tolist()})
    changes = [float(np.abs(normalized[i]-normalized[i-1]).sum())
               for i in range(1,len(windows))
               if abs(windows[i]['start_s']-windows[i-1]['start_s']-1.5) < 1e-6]
    short_power = [r['mid_power']+r['side_power'] for r in result['short_windows']
                   if not r['partial'] and r['mid_power']+r['side_power'] >= threshold]
    rms_percentiles = [_db(v) for v in np.percentile(short_power,PERCENTILES)] if short_power else None
    total_power = result['full_file']['mid_power'] + result['full_file']['side_power']
    peak = max(result['sample_peak_lr'])
    bins = []
    for start in range(0, int(np.ceil(result['duration_s'])), 15):
        mask = np.asarray([start <= (r['start_s']+r['end_s'])/2 < start+15 for r in windows])
        bins.append({'start_s':start, 'end_s':min(start+15,result['duration_s']),
            'windows': int(mask.sum()),
            'mean_normalized_stereo_band_fractions': normalized[mask].mean(axis=0).tolist() if mask.any() else None})
    return {**base, 'status':'ok', 'windows':len(windows),
        'gate_dbfs_power':_db(threshold), 'band_edges_hz':edges.tolist(),
        'bands':bands, 'broad_bands':broad, 'macro_time_bins_15s':bins,
        'adjacent_normalized_shape_l1_p10_p50_p90': np.percentile(changes,PERCENTILES).tolist() if changes else None,
        'dynamics': {'rms_400ms_p10_p50_p90_dbfs':rms_percentiles,
            'rms_400ms_p90_minus_p10_db':rms_percentiles[2]-rms_percentiles[0] if rms_percentiles else None,
            'sample_crest_db_peak_channel_over_mean_stereo_power':ratio_db(peak*peak,total_power),
            'warning':'Browser level affects absolute dBFS; constant common gain cancels '
                'in normalized spectra, crest and percentile ranges. This is NOT LRA or true peak.'}}


def derive_file(input_path, output_path):
    result = json.loads(Path(input_path).read_text(encoding='utf8'))
    data = {'feature_source_sha256':sha256(__file__), 'input_result_sha256':sha256(input_path),
            'features':expand_tonal(result)}
    with Path(output_path).open('x',encoding='utf8') as stream:
        json.dump(data,stream,allow_nan=False,separators=(',',':'))
    return data


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    args = parser.parse_args()
    derive_file(args.input,args.output)
