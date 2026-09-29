// Research-only capture of this application's OWN source. No desktop/mic capture.
const { app, BrowserWindow, ipcMain, session } = require('electron');
const fs = require('node:fs');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const {spawn}=require('node:child_process');

const root = path.resolve(__dirname, '../../..');
const output = process.argv[2] ? path.resolve(process.argv[2]) : path.join(root, 'dist/stereo-research-20260928/electron-fixture');
fs.mkdirSync(output, { recursive: true });
app.setPath('userData', path.join(output, 'profile'));
let source, receiver, distractor, watchdog, exitTimer, analyzer;
let sequence = 0, frames = 0, sumL = 0, sumR = 0, sumLR = 0, pcmPeak = 0;
let firstBlock = null;
const receiverUrl = pathToFileURL(path.join(__dirname, 'receiver.html')).href;
const fixtureUrl = pathToFileURL(path.join(__dirname, 'fixture.html')).href;

function shutdown(code = 0) {
  clearInterval(watchdog); clearTimeout(exitTimer);
  // The source remains muted until destroyed, including when capture stops.
  if (source && !source.isDestroyed()) source.destroy();
  if (receiver && !receiver.isDestroyed()) receiver.destroy();
  if(distractor&&!distractor.isDestroyed())distractor.destroy();
  if(analyzer&&analyzer.exitCode===null)analyzer.kill();
  app.exit(code);
}
function fail(error) { console.error('CAPTURE_FAILED', error?.stack || error?.message || JSON.stringify(error) || String(error)); shutdown(1); }
process.on('uncaughtException', fail);
process.on('unhandledRejection', fail);

app.whenReady().then(async () => {
  let analysisComplete;
  if(process.argv.includes('--analyze')){
    analyzer=spawn(process.env.STEREO_PYTHON||'python',['-B',path.join(__dirname,'stream-worker.py'),path.join(output,'measurement.json')],
      {windowsHide:true,stdio:['pipe','pipe','pipe']});
    analysisComplete=new Promise((resolve,reject)=>{let errors='';
      analyzer.stderr.on('data',x=>errors+=x);analyzer.stdout.on('data',x=>console.log(x.toString().trim()));
      analyzer.on('error',reject);analyzer.on('exit',c=>c===0?resolve():reject(Error(errors)));});
    analyzer.stdin.write(JSON.stringify({sampleRate:48000,channels:2})+'\n');
  }
  const isolated = session.fromPartition('stereo-research-fixture');
  isolated.setPermissionRequestHandler((wc, permission, callback, details) => {
    console.log('PERMISSION', permission, JSON.stringify(details));
    callback(wc === receiver?.webContents && wc.getURL() === receiverUrl
      && (permission === 'display-capture' || permission === 'media'));
  });
  isolated.setDisplayMediaRequestHandler((request, callback) => {
    console.log('DISPLAY_REQUEST', request.frame?.url);
    if (request.frame?.url !== receiverUrl || !source || source.isDestroyed()
        || !source.webContents.isAudioMuted()) return callback({});
    callback({ video: source.webContents.mainFrame, audio: source.webContents.mainFrame, enableLocalEcho: false });
  });
  const preferences = { session: isolated, sandbox: true, contextIsolation: true,
    nodeIntegration: false, backgroundThrottling: false };
  source = new BrowserWindow({ show: false, webPreferences: preferences });
  source.webContents.setAudioMuted(true); // Set BEFORE any page can execute.
  source.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
  receiver = new BrowserWindow({ show: false, webPreferences: {
    ...preferences, preload: path.join(__dirname, 'preload.cjs') } });
  receiver.webContents.setAudioMuted(true); // Additional barrier; context also uses sink:none.
  receiver.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
  for (const window of [source, receiver]) {
    window.webContents.on('render-process-gone', (_event, details) => fail(JSON.stringify(details)));
    window.webContents.on('console-message', event => console.log('RENDERER', event.message));
  }
  watchdog = setInterval(() => {
    if (!source.webContents.isAudioMuted() || !receiver.webContents.isAudioMuted()) fail('Mute invariant lost');
  }, 100);
  exitTimer = setTimeout(() => fail('Fixture timeout'), 25000);
  ipcMain.on('lab-block', (event, message) => {
    if (event.sender !== receiver.webContents) return fail('Unknown PCM sender');
    if (message.sequence !== sequence++ || message.channels !== 2) return fail('Missing block or wrong channel count');
    const x = new Float32Array(message.pcm);
    if(analyzer)analyzer.stdin.write(Buffer.from(message.pcm));
    if (x.length % 2) return fail('Partial sample');
    for (let i = 0; i < x.length; i += 2) {
      if (!Number.isFinite(x[i]) || !Number.isFinite(x[i + 1])) return fail('Nonfinite PCM');
      sumL += x[i] ** 2; sumR += x[i + 1] ** 2; sumLR += x[i] * x[i + 1];
      pcmPeak = Math.max(pcmPeak, Math.abs(x[i]), Math.abs(x[i + 1]));
    }
    frames += x.length / 2;
    if (pcmPeak > .01 && firstBlock === null) firstBlock = Date.now();
  });
  ipcMain.on('lab-error', (_event, error) => fail(error));
  await source.loadURL(fixtureUrl);
  if(process.argv.includes('--distractor')){
    distractor=new BrowserWindow({show:false,webPreferences:preferences});
    distractor.webContents.setAudioMuted(true);
    distractor.webContents.setWindowOpenHandler(()=>({action:'deny'}));
    await distractor.loadURL(fixtureUrl);
    await distractor.webContents.executeJavaScript('startFixture({midHz:3500,sideHz:7500})',true);
  }
  await receiver.loadURL(receiverUrl);
  const capture = await receiver.webContents.executeJavaScript('startCapture()', true);
  console.log('CAPTURE_READY', JSON.stringify(capture));
  if (capture.sink !== 'none' || capture.channels !== 2) throw new Error('Invalid silent stereo route');
  await source.webContents.executeJavaScript('startFixture()', true);
  const started=Date.now(),firstFrames=frames;
  await new Promise(resolve => setTimeout(resolve, 10000));
  const wallSeconds=(Date.now()-started)/1000, clockSeconds=(frames-firstFrames)/48000;
  await source.webContents.executeJavaScript('stopFixture()');
  const pm = (sumL + sumR + 2 * sumLR) / 4;
  const ps = (sumL + sumR - 2 * sumLR) / 4;
  const ratio = 10 * Math.log10(ps / pm);
  const result = { capture, frames, sequence, wallSeconds, clockSeconds, peak: pcmPeak, sideMidDb: ratio,
    unrelatedOwnedWindowActive:!!distractor,
    unrelatedOwnedWindowMuted:distractor?.webContents.isAudioMuted()??null,
    expectedSideMidDb: -6.020599913279624,
    errorDb: Math.abs(ratio + 6.020599913279624),
    sourceMuted: source.webContents.isAudioMuted(), receiverMuted: receiver.webContents.isAudioMuted(),
    sourceVisible: source.isVisible(), receiverVisible: receiver.isVisible() };
  if (!Number.isFinite(ratio) || result.errorDb > .1 || Math.abs(clockSeconds-wallSeconds)>.2 || !firstBlock) throw new Error(JSON.stringify(result));
  // Stop capture and verify the independent source mute survives.
  await receiver.webContents.executeJavaScript('stopCapture()');
  if(analyzer){analyzer.stdin.end();await analysisComplete;
    const measured=JSON.parse(fs.readFileSync(path.join(output,'measurement.json'),'utf8'));
    const spectral=measured.long_windows.filter(w=>!w.partial&&w.start_s>=1&&w.end_s<=9);
    const bandIndex=frequency=>measured.band_edges_hz.findIndex((lo,i)=>lo<=frequency&&measured.band_edges_hz[i+1]>frequency);
    const im=bandIndex(1000),is=bandIndex(1300);
    const diagnostics=spectral.map(w=>{
      const p=w.spectral_lr_cross_powers;
      const pm=p.map(x=>(x[0]+x[1]+2*x[2])/4),ps=p.map(x=>(x[0]+x[1]-2*x[2])/4);
      const sum=x=>x.reduce((a,b)=>a+b,0);
      return {midPower:sum(pm),sidePower:sum(ps),mid1000Fraction:pm[im]/sum(pm),side1300Fraction:ps[is]/sum(ps)};
    });
    result.spectralValidation=diagnostics;
    if(!diagnostics.length||diagnostics.some(d=>Math.abs(10*Math.log10(d.midPower/.02))>.05
        ||Math.abs(10*Math.log10(d.sidePower/.005))>.05||d.mid1000Fraction<.999||d.side1300Fraction<.999))
      throw Error('Captured spectra fail known-tone oracle: '+JSON.stringify(diagnostics));
  }
  result.sourceMutedAfterCaptureStop = source.webContents.isAudioMuted();
  if (!result.sourceMutedAfterCaptureStop) throw new Error('Capture stop leaked mute state');
  fs.writeFileSync(path.join(output, 'fixture-result.json'), JSON.stringify(result, null, 2));
  console.log('FIXTURE_PASS', JSON.stringify(result));
  shutdown();
}).catch(fail);
