// Research-only conversion of the verified 50-song corpus. No application defaults changed.
import fs from 'node:fs';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

export function energyShare(midPower, sidePower) {
  if (!Number.isFinite(midPower) || !Number.isFinite(sidePower) || midPower < 0 || sidePower < 0)
    throw new RangeError('Powers must be finite and non-negative');
  const scale = Math.max(midPower, sidePower);
  if (scale === 0) return null; // Silence is undefined, not 0% Side.
  const m = midPower / scale, s = sidePower / scale;
  return {mid:m/(m+s), side:s/(m+s)};
}

export function sideGainForShare(midPower, sidePower, targetSide) {
  if (!Number.isFinite(targetSide) || targetSide <= 0 || targetSide >= 1)
    throw new RangeError('Target must be strictly between 0 and 1');
  energyShare(midPower, sidePower); // Validate even when a component is zero.
  if (midPower === 0 || sidePower === 0) return null;
  // Log domain avoids overflow in the intermediate power ratio.
  const gain = Math.exp(.5*(Math.log(midPower)-Math.log(sidePower)+Math.log(targetSide)-Math.log1p(-targetSide)));
  // An unrepresentable gain cannot satisfy the target; never return Infinity or zero.
  return Number.isFinite(gain) && gain > 0 ? gain : null;
}

const labels = {
  electronic:'Electronic', pop:'Pop', rock:'Rock', metal:'Metal', 'hip-hop':'Hip Hop',
  'rnb-soul-funk':'R&B / Soul / Funk', 'acoustic-folk-country':'Acoustic / Folk / Country',
  'jazz-blues':'Jazz / Blues', 'orchestral-cinematic':'Orchestral / Cinematic', latin:'Latin'
};

export function deriveProfiles(study, sourceSha256) {
  if (!study.complete || study.accepted !== 50 || study.rows.length !== 50)
    throw new Error('Expected the completed 50-reference study');
  if (new Set(study.rows.map(r=>r.job.id)).size!==50 || new Set(study.rows.map(r=>r.job.url)).size!==50)
    throw new Error('Repeated reference');
  const profiles=Object.entries(labels).map(([id,label])=>{
    const rows=study.rows.filter(r=>r.job.group===id);
    if(rows.length!==5) throw new Error(`Wrong coverage: ${id}`);
    const references=rows.map(r=>{
      if(!r.accepted || !r.qc.accepted || r.stereo.status!=='ok') throw new Error(`Rejected: ${r.job.id}`);
      // Rebuild from the disjoint bands, independently of the saved ratio in dB.
      const pm=r.stereo.bands.reduce((sum,b)=>sum+b.mid_power,0);
      const ps=r.stereo.bands.reduce((sum,b)=>sum+b.side_power,0);
      const share=energyShare(pm,ps);
      if(!share || Math.abs(10*Math.log10(ps/pm)-r.stereo.pooled_spectral_side_mid_db)>1e-9)
        throw new Error(`Inconsistent spectral powers: ${r.job.id}`);
      return {id:r.job.id,artist:r.job.artist,title:r.job.titleMatch,url:r.job.url,mid:share.mid,side:share.side};
    });
    const side=references.reduce((sum,r)=>sum+r.side,0)/references.length;
    return {id,label,referenceCount:5,targetMid:1-side,targetSide:side,
      minimumSide:Math.min(...references.map(r=>r.side)),maximumSide:Math.max(...references.map(r=>r.side)),references};
  });
  return {schemaVersion:1,studySha256:sourceSha256,source:'YouTube public streaming deliveries; measured 2026-09-28',
    status:'empirical_reference_not_perceptually_validated',
    convention:'M=(L+R)/2; S=(L-R)/2; Side fraction=P(S)/(P(M)+P(S))',
    aggregation:'Arithmetic mean of five per-recording Side energy fractions. Each recording has equal weight; pooled spectral powers within each recording.',
    warning:'Not perceived volume, an average of dB, or an average chorus. No semantic sections were annotated. Not a universal quality or safety limit.',profiles};
}

if(process.argv[1] && path.resolve(process.argv[1])===fileURLToPath(import.meta.url)) {
  const folder=path.join(path.dirname(fileURLToPath(import.meta.url)),'stereo-study-20260928');
  const bytes=fs.readFileSync(path.join(folder,'study.json'));
  const hash=crypto.createHash('sha256').update(bytes).digest('hex');
  const verified=JSON.parse(fs.readFileSync(path.join(folder,'verification.json')));
  if(verified.status!=='passed' || hash!==verified.study_sha256) throw new Error('Unverified source');
  const result=deriveProfiles(JSON.parse(bytes),hash);
  fs.writeFileSync(path.join(folder,'energy-profiles.json'),JSON.stringify(result,null,2)+'\n');
  for(const p of result.profiles) console.log(`${p.label}: Mid ${(100*p.targetMid).toFixed(2)}% / Side ${(100*p.targetSide).toFixed(2)}%`);
}
