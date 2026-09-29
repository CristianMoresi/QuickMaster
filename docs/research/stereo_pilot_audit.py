"""Independent FFmpeg astats arithmetic cross-check and bounded pilot inventory.

Shares the decoder, NOT the M/S power implementation, with stereo_probe.
This does not independently validate MP3 decoder correctness or listening quality.
"""
import argparse
from collections import Counter
import json
from pathlib import Path
import re
import subprocess

import stereo_probe as probe


def audit(manifest_path, result_dir):
    manifest = json.loads(Path(manifest_path).read_text(encoding='utf-8'))
    rows = []
    for entry in manifest['tracks']:
        result_path = Path(result_dir) / (entry['id'] + '.json')
        result = json.loads(result_path.read_text(encoding='utf-8'))
        if result['analyzer_sha256'] != probe.sha256(probe.__file__):
            raise ValueError('Stale analyzer result')
        if probe.sha256(entry['local_path']) != result['source_sha256']:
            raise ValueError('Source no longer matches recorded hash')
        command = ['ffmpeg', '-nostdin', '-hide_banner', '-nostats', '-threads', '1',
                   '-protocol_whitelist', 'file,pipe', '-i', entry['local_path'],
                   '-af', 'aformat=sample_fmts=dbl,pan=stereo|c0=0.5*c0+0.5*c1|c1=0.5*c0-0.5*c1,astats=metadata=0:reset=0',
                   '-c:a', 'pcm_f64le', '-f', 'null', '-']
        checked = subprocess.run(command, capture_output=True, check=True, creationflags=probe.CREATE_NO_WINDOW, timeout=120)
        text = checked.stderr.decode(errors='replace')
        levels = re.findall(r'RMS level dB: ([-+\w.]+)', text)
        if len(levels) != 3:
            raise ValueError('Unexpected astats channel/overall layout')
        ratio = float(levels[1]) - float(levels[0])
        error = abs(ratio - result['full_file']['side_mid_db'])
        if error > 1e-5:
            raise AssertionError(f"M/S implementation mismatch {entry['id']}: {error}")
        gates = result['long_window_gate_sensitivity']
        rows.append({'id': entry['id'], 'artist': entry['artist'], 'source_sha256': result['source_sha256'],
                     'source_page': entry['source_page'], 'permission': entry['permission'],
                     'duration_s': result['duration_s'], 'sample_rate': result['sample_rate'],
                     'codec': result['stream']['codec_name'], 'decode_quality': result['decode_quality'],
                     'decoder_issues': result['decoder_issues'],
                     'side_mid_db': result['full_file']['side_mid_db'], 'astats_side_mid_db': ratio,
                     'astats_absolute_error_db': error, 'elapsed_s': result['elapsed_seconds'],
                     'all_active_windows': gates['absolute_minus_80_dbfs']['selected_windows'],
                     'p95_minus_10_windows': gates['p95_minus_10_db']['selected_windows'],
                     'gate_sensitivity': gates,
                     'bands': result['coarse_band_summary'],
                     'calibration_eligible': False})
    hashes = [r['source_sha256'] for r in rows]
    if len(set(hashes)) != len(rows):
        raise ValueError('Duplicate audio hashes')
    return {'status': 'technical pilot only; not genre calibration',
            'method_version': probe.VERSION, 'track_count': len(rows),
            'artist_project_counts': dict(Counter(r['artist'] for r in rows)),
            'decode_quality_counts': dict(Counter(r['decode_quality'] for r in rows)),
            'audio_seconds': sum(r['duration_s'] for r in rows),
            'analysis_seconds_excluding_probe_and_initial_hash': sum(r['elapsed_s'] for r in rows),
            'max_astats_absolute_error_db': max(r['astats_absolute_error_db'] for r in rows),
            'calibration_eligible_count': 0, 'tracks': rows}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', required=True, type=Path)
    parser.add_argument('--result-dir', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    result = audit(args.manifest, args.result_dir)
    with args.output.open('x', encoding='utf-8') as stream:
        json.dump(result, stream, indent=2, allow_nan=False)
    print(json.dumps({k: v for k, v in result.items() if k != 'tracks'}, indent=2))
