// Sequential Node research runner. Local-file decoding has NO physical output.
// No downloader, browser account access, preview substitution or DRM processing.
import fs from 'node:fs/promises';
import { createReadStream } from 'node:fs';
import { createHash } from 'node:crypto';
import { spawn } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath,pathToFileURL } from 'node:url';
import { corpus } from './repertoire.mjs';
import { validateSelection,fingerprint } from './catalog.mjs';
const here=path.dirname(fileURLToPath(import.meta.url));
export async function hashFile(file) {
  const hash=createHash('sha256');
  for await(const chunk of createReadStream(file)) hash.update(chunk);
  return hash.digest('hex');
}
export function validateSources(sources) {
  const ids=new Set(corpus.groups.flatMap(g=>g.tracks.map(t=>t.id))), seen=new Set(),paths=new Set();
  for(const source of sources.tracks) {
    if(!ids.has(source.id) || seen.has(source.id)) throw Error('Unknown or duplicate source ID');
    seen.add(source.id);
    if(!['user_owned_authorized','rightsholder_authorized'].includes(source.permission)
      || !source.permissionEvidence) throw Error('Missing reviewed source authority');
    if(!path.isAbsolute(source.localPath)||/^https?:/i.test(source.localPath)) throw Error('Local absolute path required');
    const key=path.resolve(source.localPath).toLowerCase();
    if(paths.has(key)) throw Error('Duplicate audio path');
    paths.add(key);
    if(!source.edition || !source.sourceReference) throw Error('Missing edition/provenance');
    if(!Number.isFinite(source.expectedDurationSeconds) || source.expectedDurationSeconds<=0) throw Error('Expected full duration required');
    if(source.fullTrack!==true || source.masteringEditionVerified!==true) throw Error('Review full track and edition before analysis');
  }
}
function worker(python,job,out) {
  return new Promise((resolve,reject)=>{
    const child=spawn(python,['-B',path.join(here,'file-worker.py'),job,out],{windowsHide:true,stdio:['ignore','pipe','pipe']});
    let diagnostics='';
    const timer=setTimeout(()=>{child.kill();reject(Error('Worker timeout; result is incomplete'));},30*60*1000);
    for(const stream of [child.stdout,child.stderr]) stream.on('data',x=>{diagnostics=(diagnostics+x).slice(-16384);});
    child.on('error',error=>{clearTimeout(timer);reject(error);});
    child.on('exit',code=>{clearTimeout(timer);code===0?resolve():reject(Error(diagnostics||`Worker exit ${code}`));});
  });
}
export async function runStudy(sources,output,python=process.env.STEREO_PYTHON||'python') {
  validateSelection(corpus); validateSources(sources);
  await fs.mkdir(output,{recursive:true});
  const methodHash=fingerprint(await Promise.all(['file-worker.py','../stereo_probe.py','../stereo_features.py','study.mjs','repertoire.mjs'].map(p=>hashFile(path.join(here,p)))));
  const rows=[],seenHashes=new Set();
  const mapping=new Map(sources.tracks.map(t=>[t.id,t]));
  for(const group of corpus.groups) for(const track of group.tracks) {
    const source=mapping.get(track.id);
    const row={id:track.id,group:group.id,artist:track.artist,title:track.title,state:'missing_authorized_audio',profileEligible:false};
    rows.push(row);
    if(!source) continue;
    try {
      const digest=await hashFile(source.localPath);
      if(seenHashes.has(digest)) throw Error('Duplicate binary audio; not independent evidence');
      seenHashes.add(digest);
      const key=fingerprint({source,track,digest,methodHash});
      const out=path.join(output,track.id+'.json');
      const record=await fs.readFile(out,'utf8').then(JSON.parse).catch(e=>{if(e.code==='ENOENT')return null;throw e;});
      let measured;
      if(record) {
        if(record.cacheKey!==key) throw Error('Stale result: use a new output directory');
        measured=record.measurement;
      } else {
        const job=path.join(output,track.id+'.job.json'),part=path.join(output,track.id+'.measurement.part');
        await fs.writeFile(job,JSON.stringify(source),{flag:'wx'});
        await worker(python,job,part);
        measured=JSON.parse(await fs.readFile(part,'utf8'));
        if(measured.source_sha256!==digest || await hashFile(source.localPath)!==digest) throw Error('Input changed');
        await fs.writeFile(out,JSON.stringify({cacheKey:key,source,track,methodHash,measurement:measured}),{flag:'wx'});
        // Keep job/part as diagnostic evidence; resuming uses the complete JSON.
      }
      const durationError=Math.abs(measured.duration_s-source.expectedDurationSeconds);
      row.state=measured.decode_quality==='clean' && durationError<=Math.max(.25,source.expectedDurationSeconds*.001)
        ?'measured_candidate':'quarantined';
      row.durationErrorSeconds=durationError; row.durationSeconds=measured.duration_s;
      row.sideMidDb=measured.full_file.side_mid_db; row.sourceSha256=digest;
      // Even clean masters are observations, not validated DSP targets.
      row.profileEligible=false;
    } catch(error) {row.state='failed';row.error=error.message;}
    console.log(`STUDY ${track.id}: ${row.state}`);
  }
  const coverage=corpus.groups.map(g=>({id:g.id,label:g.label,required:5,
    measured:rows.filter(r=>r.group===g.id&&r.state==='measured_candidate').length}));
  const result={generatedUtc:new Date().toISOString(),methodHash,selectionHash:fingerprint(corpus),coverage,rows,
    complete:coverage.every(g=>g.measured>=g.required),profilesEnabled:0};
  const statusFile=path.join(output,`coverage-${Date.now()}.json`);
  await fs.writeFile(statusFile,JSON.stringify(result,null,2),{flag:'wx'});
  console.log(JSON.stringify({measured:rows.filter(r=>r.state==='measured_candidate').length,total:rows.length,complete:result.complete,statusFile}));
  return result;
}
if(process.argv[1]&&import.meta.url===pathToFileURL(path.resolve(process.argv[1])).href) {
  const [manifest,output]=process.argv.slice(2);
  if(!manifest||!output)throw Error('Usage: study.mjs SOURCES.json OUTPUT_DIR');
  await runStudy(JSON.parse(await fs.readFile(manifest,'utf8')),output);
}
