// Reachability test of one public watch page, not a downloader. No login, no
// spoofed browser identity, no media URL extraction, no adblocking, no DRM bypass.
const {app,BrowserWindow,session}=require('electron');
const fs=require('node:fs');
const path=require('node:path');
const out=path.resolve(__dirname,'../../../dist/stereo-research-20260928/public-player-probe');
fs.mkdirSync(out,{recursive:true});
app.setPath('userData',path.join(out,'profile'));
let win;
function finish(code){if(win&&!win.isDestroyed())win.destroy();app.exit(code);}
app.whenReady().then(async()=>{
  const isolated=session.fromPartition('public-player-probe');
  isolated.setPermissionRequestHandler((_wc,_permission,cb)=>cb(false));
  win=new BrowserWindow({show:false,width:1200,height:800,webPreferences:{session:isolated,
    sandbox:true,contextIsolation:true,nodeIntegration:false,backgroundThrottling:false}});
  win.webContents.setAudioMuted(true);
  win.webContents.setWindowOpenHandler(()=>({action:'deny'}));
  isolated.on('will-download',event=>event.preventDefault());
  const timer=setInterval(()=>{if(!win.webContents.isAudioMuted())finish(2);},100);
  await win.loadURL('https://www.youtube.com/watch?v=fHI8X4OXluQ');
  await new Promise(r=>setTimeout(r,12000));
  // A normal cookie choice in the isolated research session; never logs in.
  const consent=await win.webContents.executeJavaScript(`(()=>{const b=Array.from(document.querySelectorAll('button')).find(x=>x.innerText.trim()==='Rechazar todo');if(b){b.click();return true;}return false;})()`);
  console.log('COOKIE_REJECTED',consent);
  await new Promise(r=>setTimeout(r,3000));
  await win.webContents.executeJavaScript(`document.querySelector('.ytp-settings-button')?.click()`);
  const state=await win.webContents.executeJavaScript(`({title:document.title,url:location.href,
    text:document.body.innerText.slice(0,14000),
    menuItems:Array.from(document.querySelectorAll('[role^="menuitem"]'),b=>({text:b.innerText,checked:b.getAttribute('aria-checked'),disabled:b.getAttribute('aria-disabled'),role:b.getAttribute('role')})),
    videos:Array.from(document.querySelectorAll('video'),v=>({parentClass:v.parentElement.className,grandparentClass:v.parentElement.parentElement.className,paused:v.paused,ended:v.ended,
      duration:Number.isFinite(v.duration)?v.duration:null,time:v.currentTime,readyState:v.readyState,
      muted:v.muted,volume:v.volume,error:v.error?{code:v.error.code,message:v.error.message}:null}))})`);
  state.sourceMuted=win.webContents.isAudioMuted();
  fs.writeFileSync(path.join(out,'state.json'),JSON.stringify(state,null,2));
  console.log(JSON.stringify(state,null,2));
  clearInterval(timer);finish(0);
}).catch(e=>{console.error(e.stack);finish(1);});
setTimeout(()=>finish(3),60000);
