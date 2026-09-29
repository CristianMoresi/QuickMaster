"""Verify portable corpus integrity, coverage, capture QC and derived features."""
import argparse
from datetime import datetime,timezone
import gzip
import hashlib
import json
from pathlib import Path
from assemble_stereo_study import group_summary,read
from capture_quality import audit_capture
from stereo_features import expand
from tonal_features import expand_tonal
from stereo_probe import sha256


def verify(directory):
    directory=Path(directory).resolve()
    study=read(directory/'study.json')
    if not study['complete'] or study['accepted']!=50 or study['expected']!=50:
        raise ValueError('Research is not complete: require 50 independently accepted references')
    rows=study['rows']
    if len(rows)!=50 or len({r['job']['id'] for r in rows})!=50 or len({r['job']['url'] for r in rows})!=50:
        raise ValueError('Missing or duplicated recording')
    if len(study['groups'])!=10 or any(g['accepted']!=5 or g['expected']!=5 for g in study['groups'].values()):
        raise ValueError('Require ten families with five recordings each')
    seconds=0.;clock_modes={};source_evidence=0
    for row in rows:
        file=(directory/row['archive']['path']).resolve()
        if directory not in file.parents or sha256(file)!=row['archive']['sha256']:
            raise ValueError('Invalid archive path/hash: '+row['job']['id'])
        archive=json.loads(gzip.decompress(file.read_bytes()))
        measurement=archive['measurement']
        serialized=json.dumps(measurement,allow_nan=False,separators=(',',':')).encode('utf8')
        if hashlib.sha256(serialized).hexdigest()!=row['measurement_sha256']:
            raise ValueError('Measurement archive is not byte-equivalent to original JSON: '+row['job']['id'])
        qc=audit_capture(row['job'],archive['provenance'],measurement,archive['player_settings'],archive['events'])
        if not row['accepted'] or not qc['accepted'] or qc!=row['qc']:
            raise ValueError('Independent capture audit failed: '+row['job']['id'])
        if expand(measurement)!=row['stereo'] or expand_tonal(measurement)!=row['tonal']:
            raise ValueError('Derived features differ: '+row['job']['id'])
        if measurement['full_file']!=row['full_file']:
            raise ValueError('Full-file data mismatch')
        if abs(sum(b['equal_time_mean_fraction'] for b in row['tonal']['bands'])-1.)>1e-12:
            raise ValueError('Tonal distribution is not normalized')
        seconds+=archive['provenance']['expectedDuration']
        clock_modes[qc['clock_evidence_mode']]=clock_modes.get(qc['clock_evidence_mode'],0)+1
        source_evidence+=bool(row['catalogue_evidence'])
    rebuilt={g:group_summary([r for r in rows if r['job']['group']==g]) for g in study['groups']}
    if rebuilt!=study['groups']:raise ValueError('Group aggregation mismatch')
    unexpected=[str(p) for p in directory.rglob('*') if p.is_file() and p.suffix.lower() not in ('.json','.gz','.md')]
    if unexpected:raise ValueError('Unexpected non-metric file in delivery: '+str(unexpected))
    result={'verified_utc':datetime.now(timezone.utc).isoformat(),'status':'passed',
        'references':len(rows),'families':len(rebuilt),'references_per_family':5,
        'source_duration_seconds':seconds,'clock_evidence_modes':clock_modes,
        'references_with_preserved_selected_catalogue_item':source_evidence,
        'all_have_capture_source_identity':all(bool(r['source_identity']) for r in rows),
        'study_sha256':sha256(directory/'study.json'),'verifier_sha256':sha256(__file__),
        'checks':['unique songs and URLs','complete 10x5 coverage','archive and measurement hashes',
                  'capture-quality gates','all stereo and tonal features recomputed',
                  'equal-song group aggregation recomputed','normalized spectral sums','no audio files in delivery'],
        'limitations':'This validates the measured public deliveries and computations, '
                      'not lossless original masters, perceptual preferences or population norms.'}
    (directory/'verification.json').write_text(json.dumps(result,indent=2),encoding='utf8')
    print(json.dumps(result))
    return result


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory',type=Path)
    verify(parser.parse_args().directory)
