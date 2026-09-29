"""Analyze transient float32 PCM from owned-tab capture; never store the audio."""
import json
from pathlib import Path
import sys
import numpy as np
sys.path.insert(0,str(Path(__file__).resolve().parent.parent))
from stereo_probe import Analyzer, sha256
from stereo_features import expand

header=json.loads(sys.stdin.buffer.readline())
rate=header['sampleRate']
if header.get('channels')!=2 or rate!=48000:
    raise ValueError('This capture route is validated at 48 kHz stereo only')
analyzer=Analyzer(rate)
while True:
    raw=sys.stdin.buffer.read(rate*8)
    if not raw: break
    if len(raw)%8: raise ValueError('Incomplete stereo frame')
    analyzer.add(np.frombuffer(raw,dtype='<f4').reshape(-1,2).astype(np.float64))
result=analyzer.finish()
result.update(extended_features=expand(result),stream_worker_sha256=sha256(__file__),
    source_kind='decoded browser delivery; not the lossless production master',pcm_persisted=False)
with Path(sys.argv[1]).open('x',encoding='utf8') as out:
    json.dump(result,out,allow_nan=False,separators=(',',':'))
print(json.dumps({'frames':result['frames'],'seconds':result['duration_s'],'sideMidDb':result['full_file']['side_mid_db']}),flush=True)
