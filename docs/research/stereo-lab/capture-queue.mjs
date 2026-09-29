// Bounded parallel public playback research. Stop/resume per completed track.
import fs from 'node:fs/promises';
import {openSync,closeSync} from 'node:fs';
import {spawn} from 'node:child_process';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
const here=path.dirname(fileURLToPath(import.meta.url));
const [jobDirArg,outArg,concurrencyArg='3',idsArg,statusName='queue-status.json']=process.argv.slice(2);
if(!jobDirArg||!outArg)throw Error('capture-queue.mjs JOB_DIR OUTPUT_DIR [1..4] [comma-separated IDs]');
if(!/^queue-status[\w-]*\.json$/.test(statusName))throw Error('Invalid queue status filename');
const jobsDir=path.resolve(jobDirArg),out=path.resolve(outArg),concurrency=Number(concurrencyArg);
if(!Number.isInteger(concurrency)||concurrency<1||concurrency>4)throw Error('Concurrency must be 1..4');
await fs.mkdir(out,{recursive:true});
const wanted=idsArg?new Set(idsArg.split(',')):null;
const files=(await fs.readdir(jobsDir)).filter(x=>x.endsWith('.json')&&(!wanted||wanted.has(x.slice(0,-5))));
if(wanted&&files.length!==wanted.size)throw Error('Requested IDs absent from job directory');
let next=0;const results=[],children=new Set();
let statusWrite=Promise.resolve();
const controlFile=path.join(out,'STOP');
async function runJob(file){
  const job=JSON.parse(await fs.readFile(path.join(jobsDir,file),'utf8'));
  const dir=path.join(out,job.id);
  try {const p=JSON.parse(await fs.readFile(path.join(dir,'provenance.json'),'utf8'));
    if(p.quality==='candidate'&&p.job.url===job.url){console.log('REUSED',job.id);return {id:job.id,state:'candidate',reused:true};}
    return {id:job.id,state:'previous_quarantine_requires_review'};
  }catch(e){if(e.code!=='ENOENT')throw e;}
  await fs.mkdir(dir,{recursive:true});
  const log=openSync(path.join(dir,'run.log'),'a');
  console.log('START',job.id,job.artist,job.titleMatch);
  const code=await new Promise((resolve,reject)=>{
    const child=spawn(process.execPath,[path.join(here,'launch.mjs'),'--watch',path.join(jobsDir,file),dir],
      {windowsHide:true,stdio:['ignore',log,log]});children.add(child);
    child.on('error',reject);child.on('exit',code=>{children.delete(child);resolve(code);});
  }).finally(()=>closeSync(log));
  let quality='failed';
  try{quality=JSON.parse(await fs.readFile(path.join(dir,'provenance.json'),'utf8')).quality;}catch{}
  const result={id:job.id,exitCode:code,state:quality};
  console.log('FINISH',JSON.stringify(result));return result;
}
async function lane(){
  while(next<files.length){
    try{await fs.access(controlFile);console.log('STOP requested; no new playback');break;}catch{}
    // Every lane awaited filesystem I/O; another lane may have taken the last job.
    if(next>=files.length)break;
    const file=files[next++];
    let result;try{result=await runJob(file);}catch(e){result={id:file.slice(0,-5),state:'runner_error',error:e.message};}
    results.push(result);
    const snapshot=JSON.stringify({total:files.length,finished:results.length,active:children.size,results},null,2);
    statusWrite=statusWrite.then(async()=>{
      const temp=path.join(out,statusName+'.tmp');await fs.writeFile(temp,snapshot);
      await fs.rename(temp,path.join(out,statusName));
    });
    await statusWrite;
  }
}
await Promise.all(Array.from({length:concurrency},lane));
console.log('QUEUE_COMPLETE',JSON.stringify({total:files.length,results}));
