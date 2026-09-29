"""One authorized local-file job. Invoked by the Node queue; no audio output."""
import json
from pathlib import Path
import sys
sys.path.insert(0,str(Path(__file__).resolve().parent.parent))
from stereo_probe import analyze_file, sha256
from stereo_features import expand
import stereo_features

job=json.loads(Path(sys.argv[1]).read_text(encoding='utf8'))
result=analyze_file(job['localPath'])
result['extended_features']=expand(result)
result['feature_source_sha256']=sha256(stereo_features.__file__)
with Path(sys.argv[2]).open('x',encoding='utf8') as output:
    json.dump(result,output,allow_nan=False,separators=(',',':'))
print(json.dumps({'quality':result['decode_quality'],'duration':result['duration_s']}),flush=True)
