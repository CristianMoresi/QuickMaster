// Local silent clock diagnostic. No public playback, no microphone or loopback.
const {app,BrowserWindow}=require('electron');
const fs=require('node:fs'),path=require('node:path');
const {pathToFileURL}=require('node:url');
app.whenReady().then(async()=>{
  const w=new BrowserWindow({show:false,webPreferences:{sandbox:true,contextIsolation:true,
    nodeIntegration:false,backgroundThrottling:false}});
  w.webContents.setAudioMuted(true);
  await w.loadURL(pathToFileURL(path.join(__dirname,'fixture.html')).href);
  const result=await w.webContents.executeJavaScript(`(async()=>{
    const specs=[{name:'none-interactive',sinkId:{type:'none'},latencyHint:'interactive'},
      {name:'none-playback',sinkId:{type:'none'},latencyHint:'playback'},
      {name:'none-100ms',sinkId:{type:'none'},latencyHint:.1},
      {name:'muted-default-zero-output',latencyHint:'playback'}];
    const contexts=specs.map(spec=>{const c=new AudioContext({sampleRate:48000,...spec});
      const gain=c.createGain();gain.gain.value=0;const osc=c.createOscillator();
      osc.connect(gain).connect(c.destination);osc.start();return c;});
    await Promise.all(contexts.map(c=>c.resume()));
    const before=performance.now(),times=contexts.map(c=>c.currentTime);
    await new Promise(r=>setTimeout(r,20000));
    const elapsed=(performance.now()-before)/1000;
    const result=contexts.map((c,i)=>({spec:specs[i],elapsed,contextElapsed:c.currentTime-times[i],
      rate:c.sampleRate,baseLatency:c.baseLatency,clockRatio:(c.currentTime-times[i])/elapsed}));
    await Promise.all(contexts.map(c=>c.close()));return result;
  })()`,true);
  const out=path.resolve(__dirname,'../../../dist/stereo-research-20260928/clock-probe.json');
  fs.writeFileSync(out,JSON.stringify({utc:new Date().toISOString(),muted:w.webContents.isAudioMuted(),result},null,2));
  console.log('CLOCK_PROBE',JSON.stringify(result));w.destroy();app.exit(0);
}).catch(e=>{console.error(e);app.exit(1);});
