// Normal public watch-page playback into an isolated, permanently muted window.
// Captures the OWN webContents, never Windows loopback, other tabs or microphone.
const {app,BrowserWindow,session,ipcMain}=require('electron');
const {spawn}=require('node:child_process');
const fs=require('node:fs'),path=require('node:path');
const {pathToFileURL}=require('node:url');
const {createHash}=require('node:crypto');
const tailGuardSeconds=.250;
const jobPath=process.argv[2];
if(!jobPath)throw Error('watch-capture.cjs JOB.json OUTPUT_DIR');
const job=JSON.parse(fs.readFileSync(jobPath,'utf8'));
const out=path.resolve(process.argv[3]);
if(!/^[a-z0-9-]+$/.test(job.id)||!/^https:\/\/www\.youtube\.com\/watch\?v=[\w-]{11}$/.test(job.url))throw Error('Invalid public watch job');
fs.mkdirSync(out,{recursive:true});
if(fs.existsSync(path.join(out,'measurement.json')))throw Error('Measurement exists; refusing a duplicate play');
app.setPath('userData',path.join(out,'browser-profile'));
let source,receiver,worker,watchdog,timeout,collecting=false,sequence=0,frames=0,shuttingDown=false,receivedFrames=0,warmupNonzero=false;
const events=[],receiverUrl=pathToFileURL(path.join(__dirname,'receiver.html')).href;
const inputGaps=[];
const delay=ms=>new Promise(r=>setTimeout(r,ms));
const log=(kind,data={})=>{events.push({utc:new Date().toISOString(),kind,...data});console.log(kind,JSON.stringify(data));};
function save(name,value){fs.writeFileSync(path.join(out,name),JSON.stringify(value,null,2));}
function shutdown(code){shuttingDown=true;collecting=false;ipcMain.removeAllListeners('lab-block');ipcMain.removeAllListeners('lab-error');clearInterval(watchdog);clearTimeout(timeout);
  if(source&&!source.isDestroyed())source.destroy();if(receiver&&!receiver.isDestroyed())receiver.destroy();
  if(worker&&worker.exitCode===null)worker.kill();app.exit(code);}
function fail(e){if(shuttingDown)return;log('FAILED',{error:e?.stack||e?.message||String(e)});save('events.json',events);shutdown(1);}
process.on('unhandledRejection',fail);process.on('uncaughtException',fail);
async function readState(){return source.webContents.executeJavaScript(`(()=>{
  const v=document.querySelector('video');const p=v?.closest('.html5-video-player');
  return {title:document.title,url:location.href,text:document.body.innerText.slice(0,14000),
    ad:!!p?.classList.contains('ad-showing'),paused:v?.paused,ended:v?.ended,
    time:v?.currentTime,duration:Number.isFinite(v?.duration)?v.duration:null,
    ready:v?.readyState,volume:v?.volume,muted:v?.muted,rate:v?.playbackRate,
    researchEnded:window.__researchSongEnded||null,
    error:v?.error?{code:v.error.code,message:v.error.message}:null};})()`);}
function accessBlocked(s){return /confirm you.re not a bot|confirma que no eres un bot|sign in to confirm|inicia sesi[oó]n para confirmar|video unavailable|v[ií]deo no disponible/i.test(s.text);}
app.whenReady().then(async()=>{
  const isolated=session.fromPartition('research-watch');
  isolated.on('will-download',event=>event.preventDefault());
  isolated.setPermissionRequestHandler((wc,permission,cb)=>cb(wc===receiver?.webContents
    &&wc.getURL()===receiverUrl&&['media','display-capture'].includes(permission)));
  isolated.setDisplayMediaRequestHandler((request,cb)=>{
    if(request.frame?.url!==receiverUrl||!source?.webContents.isAudioMuted())return cb({});
    cb({video:source.webContents.mainFrame,audio:source.webContents.mainFrame,enableLocalEcho:false});
  });
  const prefs={session:isolated,sandbox:true,contextIsolation:true,nodeIntegration:false,backgroundThrottling:false};
  source=new BrowserWindow({show:false,width:1200,height:800,webPreferences:prefs});
  receiver=new BrowserWindow({show:false,webPreferences:{...prefs,preload:path.join(__dirname,'preload.cjs')}});
  for(const w of [source,receiver]){w.webContents.setAudioMuted(true);w.webContents.setWindowOpenHandler(()=>({action:'deny'}));
    w.webContents.on('render-process-gone',(_e,d)=>fail(Error('Renderer exited: '+JSON.stringify(d))));}
  watchdog=setInterval(()=>{if(!source.webContents.isAudioMuted()||!receiver.webContents.isAudioMuted())fail(Error('Mute invariant'));},100);
  timeout=setTimeout(()=>fail(Error('Job timeout')),30*60*1000);
  log('LOADING',{url:job.url});
  await Promise.race([source.loadURL(job.url),delay(25000)]);await delay(6000);
  log('PAGE_LOADED',{url:source.webContents.getURL()});
  await source.webContents.executeJavaScript(`(()=>{const b=Array.from(document.querySelectorAll('button')).find(x=>x.innerText.trim()==='Rechazar todo');if(b&&b.getBoundingClientRect().height)b.click();})()`);
  const deadline=Date.now()+600000;
  let state;
  while(Date.now()<deadline){
    state=await readState();
    save('latest-state.json',state);
    if(state.text.includes('Antes de ir a YouTube')) {
      const clicked=await source.webContents.executeJavaScript(`(()=>{const b=Array.from(document.querySelectorAll('button')).find(x=>x.innerText.trim()==='Rechazar todo'&&x.getBoundingClientRect().height>0);if(b){b.click();return true;}return false;})()`);
      if(clicked){log('COOKIE_CHOICE',{choice:'reject optional cookies'});await delay(2000);continue;}
    }
    if(accessBlocked(state)||state.error)throw Error('Public player unavailable; no bypass: '+JSON.stringify(state));
    if(!state.ad&&state.ready>=2&&state.duration>90)break;
    // Only the ordinary visible skip control. Never intercept/block ad requests.
    if(state.ad){
      const skipped=await source.webContents.executeJavaScript(`(()=>{const b=Array.from(document.querySelectorAll('button,[role="button"]')).find(x=>/^(Saltar|Saltar anuncios|Skip|Skip ads|Skip ad)$/i.test(x.innerText.trim())&&x.getBoundingClientRect().height>0&&!x.disabled);if(b){b.click();return true;}return false;})()`);
      if(skipped)log('ORDINARY_SKIP_CONTROL',{clicked:true});
    }
    if(state.paused)await source.webContents.executeJavaScript(`(()=>{document.querySelector('video')?.play().catch(()=>{});return true;})()`,true);
    if(Math.round((deadline-Date.now())/1000)%20===0)log('WAITING_FOR_CONTENT',{ad:state.ad,time:state.time,duration:state.duration,paused:state.paused,ready:state.ready});
    await delay(1000);
  }
  if(state.ad||!state.duration||state.duration<90)throw Error('No identifiable full song');
  const fold=s=>s.normalize('NFKD').replace(/\p{M}/gu,'').toLowerCase().replace(/[^\p{L}\p{N}]/gu,'');
  if(!fold(state.title).includes(fold(job.titleMatch)))throw Error('Title mismatch');
  if(job.expectedDurationSeconds&&Math.abs(state.duration-job.expectedDurationSeconds)>job.durationToleranceSeconds)throw Error('Unexpected edition/duration');
  await source.webContents.executeJavaScript(`(()=>{const v=document.querySelector('video');v.pause();v.currentTime=0;
    const auto=document.querySelector('[aria-label="Reproducción automática activada"]');if(auto?.getBoundingClientRect().height)auto.click();})()`);
  // Read the normal player menu; disable Stable volume only if offered/enabled.
  await source.webContents.executeJavaScript(`document.querySelector('.ytp-settings-button')?.click()`);
  await delay(250);
  const menu=await source.webContents.executeJavaScript(`Array.from(document.querySelectorAll('[role^="menuitem"]'),b=>({text:b.innerText,checked:b.getAttribute('aria-checked'),disabled:b.getAttribute('aria-disabled')}))`);
  const stable=menu.find(x=>/volumen estable|stable volume/i.test(x.text));
  if(stable?.checked==='true'&&stable.disabled!=='true') {
    await source.webContents.executeJavaScript(`(()=>{const e=Array.from(document.querySelectorAll('[role="menuitemcheckbox"]')).find(x=>/volumen estable|stable volume/i.test(x.innerText)&&x.getAttribute('aria-checked')==='true');e?.click();})()`);
  }
  await delay(200);
  const menuAfter=await source.webContents.executeJavaScript(`Array.from(document.querySelectorAll('[role^="menuitem"]'),b=>({text:b.innerText,checked:b.getAttribute('aria-checked'),disabled:b.getAttribute('aria-disabled')}))`);
  const stableAfter=menuAfter.find(x=>/volumen estable|stable volume/i.test(x.text));
  if(stable?.checked==='true'&&stableAfter?.checked!=='false')throw Error('Cannot verify Stable volume is disabled');
  save('player-settings.json',{menu,menuAfter,stableVolume:stable?stable.disabled==='true'?'unavailable':stableAfter?.checked==='false'?'off':'unknown':'not_offered_in_content_menu'});
  await source.webContents.executeJavaScript(`document.querySelector('.ytp-settings-button')?.click()`);
  save('source-state.json',state);log('SOURCE_READY',{title:state.title,duration:state.duration});
  ipcMain.on('lab-block',(event,message)=>{
    if(shuttingDown)return;
    if(event.sender!==receiver.webContents)return fail(Error('Unexpected sender'));
    if(message.sequence!==sequence++||message.channels!==2)return fail(Error('Missing capture block'));
    receivedFrames+=message.pcm.byteLength/8;
    if(!warmupNonzero){const values=new Float32Array(message.pcm);warmupNonzero=values.some(x=>Math.abs(x)>1e-8);}
    if(!collecting)return;
    const pcm=Buffer.from(message.pcm);
    if(pcm.length%8||worker.stdin.writableLength>1024*1024)return fail(Error('Invalid PCM/backpressure'));
    if(message.missingInputFrames)inputGaps.push({startFrame:frames,frames:pcm.length/8,missingFrames:message.missingInputFrames});
    frames+=pcm.length/8;worker.stdin.write(pcm);
  });
  ipcMain.on('lab-error',(_event,error)=>fail(Error(error)));
  await receiver.loadURL(receiverUrl);
  const capture=await receiver.webContents.executeJavaScript('startCapture()',true);
  if(capture.sink!=='none'||capture.channels!==2||capture.rate!==48000)throw Error('Invalid capture route');
  save('capture-route.json',capture);
  // Browser tab capture negotiates its audio route on actual playback, not on
  // a paused element. Warm it up, discard those samples, seek back, THEN measure.
  const warmupStart=receivedFrames;
  await source.webContents.executeJavaScript(`(()=>{const v=document.querySelector('video');v.muted=false;v.play().catch(()=>{});return true;})()`,true);
  const warmupDeadline=Date.now()+20000;
  while(receivedFrames-warmupStart<48000*2||!warmupNonzero){
    if(Date.now()>warmupDeadline)throw Error('Capture did not receive valid stereo during warm-up');
    const s=await readState();if(s.ad||accessBlocked(s))throw Error('Warm-up interrupted');
    await delay(100);
  }
  await source.webContents.executeJavaScript(`(()=>{const v=document.querySelector('video');v.pause();v.currentTime=0;return true;})()`);
  await delay(600);
  log('ROUTE_WARM',{frames:receivedFrames-warmupStart,nonzero:warmupNonzero});
  const python=process.env.STEREO_PYTHON||'python';
  worker=spawn(python,['-B',path.join(__dirname,'stream-worker.py'),path.join(out,'measurement.json')],{windowsHide:true,stdio:['pipe','pipe','pipe']});
  const completion=new Promise((resolve,reject)=>{let errors='';worker.stderr.on('data',x=>errors=(errors+x).slice(-16384));
    worker.stdout.on('data',x=>console.log('ANALYZER',x.toString().trim()));worker.on('error',reject);
    worker.on('exit',c=>c===0?resolve():reject(Error('Analyzer failed: '+errors)));});
  worker.stdin.on('error',fail);
  worker.stdin.write(JSON.stringify({sampleRate:capture.rate,channels:2})+'\n');
  await delay(300);collecting=true;
  const duration=state.duration;
  const testTail=job.routeTailTest===true;
  await source.webContents.executeJavaScript(`(()=>{const v=document.querySelector('video');v.currentTime=${testTail?'Math.max(0,v.duration-8)':'0'};v.muted=false;v.playbackRate=1;
    const expected=v.duration;
    const end=e=>{if(e.target===v){window.__researchSongEnded={time:v.currentTime,duration:v.duration};v.pause();window.removeEventListener('ended',end,true);}};
    window.addEventListener('ended',end,true);
    const timer=setInterval(()=>{if(v.duration===expected&&v.currentTime>=expected-${tailGuardSeconds}&&!v.closest('.html5-video-player')?.classList.contains('ad-showing')){
      v.pause();window.__researchSongEnded={time:v.currentTime,duration:expected,kind:'bounded_tail_guard'};clearInterval(timer);}},10);
    v.play().catch(()=>{});return true;})()`,true);
  log('MEASURING',{id:job.id,duration});
  const timeline=[],started=Date.now();let previous=-1,stalled=0;
  while(true){
    await delay(1000);state=await readState();
    save('latest-state.json',state);
    const sample={elapsed:(Date.now()-started)/1000,time:state.time,duration:state.duration,ad:state.ad,paused:state.paused,ended:state.ended,volume:state.volume,rate:state.rate,capturedFrames:frames};
    timeline.push(sample);
    if(sample.elapsed>10&&Math.abs(frames/48000-sample.elapsed)>.75){
      save('timeline.json',timeline);throw Error('Sample clock drift; capture invalid');
    }
    if(state.researchEnded&&state.researchEnded.time>=duration-tailGuardSeconds-.02) {log('SONG_ENDED',state.researchEnded);break;}
    if(state.ad||new URL(state.url).searchParams.get('v')!==new URL(job.url).searchParams.get('v')||accessBlocked(state)||state.error){save('quarantine-state.json',state);save('timeline.json',timeline);throw Error('Capture contaminated/interrupted; quarantined');}
    if(state.rate!==1||state.muted)throw Error('Invalid playback rate/mute');
    if(state.time<previous-.1)throw Error('Unexpected seek');
    stalled=state.time===previous?stalled+1:0;if(stalled>15)throw Error('Playback stalled');previous=state.time;
    if(timeline.length%30===0)log('PROGRESS',{seconds:state.time,duration,frames});
    if(state.ended||state.time>=duration-.06)break;
    if(Date.now()-started>(duration+90)*1000)throw Error('Incomplete song');
  }
  await source.webContents.executeJavaScript(`document.querySelector('video')?.pause()`);
  await delay(500);collecting=false;worker.stdin.end();await completion;
  await receiver.webContents.executeJavaScript('stopCapture()');
  const measured=JSON.parse(fs.readFileSync(path.join(out,'measurement.json'),'utf8'));
  const elapsed=measured.duration_s;
  const internalGaps=inputGaps.filter(g=>g.startFrame/48000>.5&&(g.startFrame+g.frames)/48000<duration-5);
  const quality=!testTail&&!internalGaps.length&&elapsed>=duration-.5&&elapsed<=duration+3&&measured.sample_peak_lr.some(x=>x>.0001)?'candidate':'quarantined';
  save('provenance.json',{job,capture,quality,expectedDuration:duration,capturedDuration:elapsed,
    title:state.title,sourceMuted:true,receiverMuted:true,sourceMutedAfterCaptureStop:source.webContents.isAudioMuted(),
    pcmPersisted:false,route:'owned webContents -> silent sink -> PCM pipe -> measurements',
    normalization:'player gain and visible Stable volume menu recorded separately',
    endBoundary:state.researchEnded||null,tailGuardSeconds,routeTailTest:testTail,inputGaps,
    methodFiles:Object.fromEntries(['watch-capture.cjs','receiver.js','pcm-worklet.js','stream-worker.py','../stereo_probe.py','../stereo_features.py'].map(f=>[f,createHash('sha256').update(fs.readFileSync(path.join(__dirname,f))).digest('hex')])),
    runtime:{electron:process.versions.electron,chrome:process.versions.chrome,node:process.versions.node},
    completeness:'Content from time zero, except final approximately 250 ms deliberately paused before post-roll. Capture includes short silent route padding. Not sample-exact PCM of a production master.',timeline});
  log('CAPTURE_COMPLETE',{quality,elapsed,sideMidDb:measured.full_file.side_mid_db});
  save('events.json',events);shutdown(0);
}).catch(fail);
