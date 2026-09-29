"""Post-process v4 probe timelines, without reopening or altering source audio.

Frequency distributions use pooled, equal-weight, full 3 s Hann windows. They
are NOT the exact full-file rectangular RMS and NOT perceptual stereo quality.
"""
import argparse
import hashlib
import json
import math
from pathlib import Path
import numpy as np
from stereo_probe import descriptors, ratio_db, sha256


def expand(result):
    edges = np.asarray(result['band_edges_hz'])
    windows = [r for r in result['long_windows'] if not r['partial']
               and r['mid_power'] + r['side_power'] >= 1e-8
               and 'spectral_lr_cross_powers' in r]
    if not windows:
        return {'status': 'no_full_active_windows', 'spectral_windows': 0, 'bands': [], 'cutoffs': []}
    matrices = np.asarray([r['spectral_lr_cross_powers'] for r in windows])
    pooled = matrices.mean(axis=0)
    if pooled.shape != (len(edges)-1, 3) or not np.isfinite(pooled).all():
        raise ValueError('Malformed spectral powers')
    pm = np.maximum(0., (pooled[:,0]+pooled[:,1]+2*pooled[:,2])/4)
    ps = np.maximum(0., (pooled[:,0]+pooled[:,1]-2*pooled[:,2])/4)
    # Exact time-domain sums distinguish true nulls from FFT roundoff. Never
    # turn tiny cancellation residue into a fictitious normalized spectrum.
    if result['full_file']['side_power']==0: ps[:]=0
    if result['full_file']['mid_power']==0: pm[:]=0
    total_mid, total_side = float(pm.sum()), float(ps.sum())
    bands=[]
    for i,(lo,hi) in enumerate(zip(edges[:-1],edges[1:])):
        desc=descriptors(pooled[i])
        if ps[i]+pm[i]==0: desc['state']='silence'
        elif pm[i]==0: desc['state']='antiphase'
        elif ps[i]==0: desc['state']='dual_mono'
        bands.append({'lo_hz':float(lo),'hi_hz':float(hi),**desc,
            'mid_power':float(pm[i]),'side_power':float(ps[i]),'side_mid_db':ratio_db(float(ps[i]),float(pm[i])),
            'fraction_of_total_side':float(ps[i]/total_side) if total_side else None,
            'fraction_of_total_mid':float(pm[i]/total_mid) if total_mid else None,
            'side_fraction_of_band_energy':float(ps[i]/(ps[i]+pm[i])) if ps[i]+pm[i] else None})
    cutoffs=[]
    for cutoff in (120,200,250,300):
        below = edges[1:] <= cutoff
        low_m,low_s = float(pm[below].sum()),float(ps[below].sum())
        cutoffs.append({'cutoff_hz':cutoff,
            'fraction_side_below':low_s/total_side if total_side else None,
            'fraction_mid_below':low_m/total_mid if total_mid else None,
            'side_mid_below_db':ratio_db(low_s,low_m),
            'side_mid_above_db':ratio_db(total_side-low_s,total_mid-low_m)})
    # Macro timeline bins are time bins, NOT semantic verse/chorus labels.
    bins=[]
    for start in range(0, math.ceil(result['duration_s']), 15):
        selected=[r for r in windows if start <= (r['start_s']+r['end_s'])/2 < start+15]
        finite=[r['side_mid_db'] for r in selected if isinstance(r['side_mid_db'],(float,int))]
        bins.append({'start_s':start,'end_s':min(start+15,result['duration_s']),
            'windows':len(selected),'finite_windows':len(finite),
            'side_mid_p10_p50_p90_db':np.percentile(finite,[10,50,90]).tolist() if finite else None})
    correlations=[r['correlation_uncentered'] for r in windows if r['correlation_uncentered'] is not None]
    return {'status':'ok','spectral_windows':len(windows),
        'weighting':'equal full 3 s Hann windows, hop 1.5 s, total power >= -80 dBFS',
        'pooled_spectral_side_mid_db':ratio_db(total_side,total_mid),
        'bands':bands,'cutoffs':cutoffs,'macro_time_bins_15s':bins,
        'negative_correlation_fraction':sum(c<0 for c in correlations)/len(correlations) if correlations else None,
        'not_computed':['magnitude-squared coherence','semantic musical section labels','perceptual optimum']}


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    result=json.loads(args.input.read_text(encoding='utf8'))
    data={'feature_version':1,'feature_source_sha256':sha256(__file__),
        'input_result_sha256':sha256(args.input),'source_sha256':result['source_sha256'],
        'decode_quality':result['decode_quality'],'features':expand(result)}
    with args.output.open('x',encoding='utf8') as stream:
        json.dump(data,stream,allow_nan=False,separators=(',',':'))
