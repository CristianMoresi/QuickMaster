"""Reproducible corpus assembly: only independently accepted captures count.

Produces a portable, audio-free measurement archive plus normalized stereo and
tonal features. Partial coverage is explicit and never enables a genre profile.
"""
import argparse
from datetime import datetime,timezone
import gzip
import json
from pathlib import Path
import platform
from urllib.parse import urlparse,parse_qs
import numpy as np
from capture_quality import audit_capture
from stereo_features import expand
from tonal_features import expand_tonal
from stereo_probe import sha256


def read(path):
    return json.loads(Path(path).read_text(encoding='utf8'))


def quantiles(values):
    finite=[v for v in values if isinstance(v,(int,float)) and np.isfinite(v)]
    return {'n':len(finite),'p10_p50_p90':np.percentile(finite,[10,50,90]).tolist() if finite else None,
            'mean':float(np.mean(finite)) if finite else None,
            'min':min(finite) if finite else None,'max':max(finite) if finite else None}


def catalogue_evidence(job, campaign_root):
    """Keep the observed selected item, not a whole search page or other songs."""
    video=parse_qs(urlparse(job['url']).query)['v'][0]
    evidence=[]
    for folder in ('public-catalog-music-v1','public-catalog-v1','public-catalog-v2'):
        file=Path(campaign_root)/folder/(job['id']+'.json')
        if not file.exists():continue
        page=read(file)
        links=[link for link in page.get('links',[])
               if parse_qs(urlparse(link.get('href','')).query).get('v')==[video]]
        if not links:continue
        contexts=[]
        for link in links:
            local=next((a.get('text','') for a in link.get('ancestors',[])
                        if a.get('tag') in ('YTD-VIDEO-RENDERER','YTMUSIC-RESPONSIVE-LIST-ITEM-RENDERER',
                                            'YTMUSIC-TWO-ROW-ITEM-RENDERER') or a.get('id')=='dismissible'),None)
            text=' '.join((local or link.get('title') or link.get('text') or '').split())
            contexts.append(text)
        evidence.append({'source_page':page.get('url'),'queried_utc':page.get('queriedUtc'),
            'selected_url':job['url'],'selected_item_context':max(contexts,key=len)[:800],
            'snapshot_sha256':sha256(file)})
    return evidence


def group_summary(rows):
    accepted=[r for r in rows if r['accepted']]
    summary={'expected':len(rows),'accepted':len(accepted),
        'coverage_complete':len(accepted)==len(rows) and len(rows)>=5,
        'observation_unit':'one recording; songs equally weighted, not windows',
        'perceptual_optimum_established':False,'population_estimate':False}
    if not accepted:return summary
    summary['side_mid_db']=quantiles([r['stereo']['pooled_spectral_side_mid_db'] for r in accepted])
    summary['side_time_median_db_by_gate']={gate:quantiles([
        (r.get('side_time_gate_sensitivity',{}).get(gate,{}).get('finite_ratio_p10_p50_p90_db') or [None,None,None])[1]
        for r in accepted]) for gate in ('absolute_minus_80_dbfs','p95_minus_10_db','p95_minus_20_db','p95_minus_40_db')}
    summary['side_low_frequency_fractions']=[{'cutoff_hz':cutoff,**quantiles([
        next(c['fraction_side_below'] for c in r['stereo']['cutoffs'] if c['cutoff_hz']==cutoff)
        for r in accepted])} for cutoff in (120,200,250,300)]
    edges=accepted[0]['tonal']['band_edges_hz']
    if any(r['tonal']['band_edges_hz']!=edges for r in accepted):raise ValueError('Different frequency grids')
    summary['tonal_equal_time_normalized_fractions']=[{'lo_hz':lo,'hi_hz':hi,
        **quantiles([r['tonal']['bands'][i]['equal_time_mean_fraction'] for r in accepted])}
        for i,(lo,hi) in enumerate(zip(edges[:-1],edges[1:]))]
    summary['tonal_curve_note']='The mean curve sums to one. Independent band quantiles need not; '
    summary['tonal_curve_note']+='they describe between-recording spread, not an EQ prescription.'
    summary['dynamic_range_400ms_db']=quantiles([r['tonal']['dynamics']['rms_400ms_p90_minus_p10_db'] for r in accepted])
    summary['sample_crest_db']=quantiles([r['tonal']['dynamics']['sample_crest_db_peak_channel_over_mean_stereo_power'] for r in accepted])
    return summary


def assemble(jobs_dir, roots, extra_attempts, output):
    output=Path(output)
    if output.exists():raise FileExistsError('New output directory required; preserve previous evidence snapshots')
    jobs=[read(p) for p in sorted(Path(jobs_dir).glob('*.json'))]
    if len({j['id'] for j in jobs})!=len(jobs) or len({j['url'] for j in jobs})!=len(jobs):
        raise ValueError('Duplicate selected recording or job ID')
    attempts=[p.parent for root in roots for p in sorted(Path(root).glob('*/provenance.json'))]
    attempts.extend(Path(p) for p in extra_attempts)
    attempts=list(dict.fromkeys(p.resolve() for p in attempts))
    by_id={j['id']:[] for j in jobs}
    for p in attempts:
        provenance=read(p/'provenance.json')
        if provenance.get('job',{}).get('id') in by_id:by_id[provenance['job']['id']].append((p,provenance))
    rows=[]
    output.mkdir(parents=True)
    (output/'records').mkdir()
    for job in jobs:
        audited=[]
        for p,provenance in by_id[job['id']]:
            measurement=read(p/'measurement.json');settings=read(p/'player-settings.json')
            events=read(p/'events.json') if (p/'events.json').exists() else []
            qc=audit_capture(job,provenance,measurement,settings,events)
            audited.append((p,provenance,measurement,settings,qc,events))
        good=[a for a in audited if a[4]['accepted']]
        evidence=catalogue_evidence(job,Path(jobs_dir).parent)
        row={'job':job,'accepted':bool(good),'catalogue_evidence':evidence,
             'attempts':[{'path':str(a[0]),'qc':a[4]} for a in audited]}
        if good:
            # Prefer an accepted run with continuous clock logs. Still preserve
            # each attempt and never combine two captures into a fictitious one.
            p,provenance,measurement,settings,qc,events=max(good,key=lambda a:a[4]['continuous_clock_logged'])
            row.update(qc=qc,source_attempt=str(p),measurement_sha256=sha256(p/'measurement.json'),
                       captured_duration_s=measurement['duration_s'],full_file=measurement['full_file'],
                       side_time_gate_sensitivity=measurement['long_window_gate_sensitivity'],
                       stereo=expand(measurement),tonal=expand_tonal(measurement))
            if row['stereo']['status']!='ok' or row['tonal']['status']!='ok':
                raise ValueError('Accepted capture has no usable feature windows: '+job['id'])
            page=read(p/'source-state.json')
            identity={'title':page.get('title'),'url':page.get('url'),'duration':page.get('duration'),
                'identity_excerpt':' '.join(page.get('text','').split())[:600],
                'source_snapshot_sha256':sha256(p/'source-state.json')}
            row['source_identity']=identity
            archive={'job':job,'qc':qc,'provenance':provenance,'player_settings':settings,'events':events,
                     'source_identity':identity,'catalogue_evidence':evidence,'measurement':measurement}
            payload=json.dumps(archive,allow_nan=False,separators=(',',':')).encode('utf8')
            archive_path=output/'records'/(job['id']+'.json.gz')
            archive_path.write_bytes(gzip.compress(payload,compresslevel=9,mtime=0))
            row['archive']={'path':'records/'+archive_path.name,'sha256':sha256(archive_path),'audio':False}
        rows.append(row)
    groups={group:group_summary([r for r in rows if r['job']['group']==group])
            for group in sorted({j['group'] for j in jobs})}
    complete=len(jobs)==50 and len(groups)==10 and all(g['coverage_complete'] for g in groups.values())
    result={'created_utc':datetime.now(timezone.utc).isoformat(),'expected':len(jobs),
        'accepted':sum(r['accepted'] for r in rows),'complete':complete,
        'scope':'50 preselected public delivery references, ten general families, not original studio masters',
        'warning':'Five recordings per family are exploratory references, not an ideal or population norm. '
            'No app DSP profile is automatically installed from these measurements.',
        'method_sha256':{name:sha256(Path(__file__).parent/name) for name in
                         ('assemble_stereo_study.py','capture_quality.py','tonal_features.py','stereo_features.py','stereo_probe.py')},
        'assembly_runtime':{'python':platform.python_version(),'numpy':np.__version__},
        'groups':groups,'rows':rows}
    (output/'study.json').write_text(json.dumps(result,allow_nan=False,indent=2),encoding='utf8')
    lines=['# Stereo / tonal measurement coverage','',f"Accepted: {result['accepted']}/{len(jobs)}. Complete: {complete}.",'',
           'These are public streaming delivery measurements. No audio is included. A recording is one observation; '
           'overlapping time windows are not independent masters. Absolute browser dBFS is not compared between songs.','',
           '| Family | Accepted / selected | Coverage complete |','|---|---:|---|']
    for name,g in groups.items():lines.append(f"| {name} | {g['accepted']} / {g['expected']} | {g['coverage_complete']} |")
    lines+=['','See `study.json` for source URLs, exact editions, capture checks, metric definitions and normalized features. '
            'Compressed `records/*.json.gz` preserve the powers/timelines and provenance, not PCM.','',
            'No universal or perceptually ideal Mid/Side ratio or EQ curve is claimed.','']
    (output/'README.md').write_text('\n'.join(lines),encoding='utf8')
    print(json.dumps({'accepted':result['accepted'],'expected':len(jobs),'complete':complete,'output':str(output)}))
    return result


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jobs',type=Path,required=True)
    parser.add_argument('--root',type=Path,action='append',default=[])
    parser.add_argument('--attempt',type=Path,action='append',default=[])
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    assemble(args.jobs,args.root,args.attempt,args.output)
