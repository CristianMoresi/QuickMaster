// Public search-page metadata, no audio extraction or playback manipulation.
const {app,BrowserWindow,session}=require('electron');
const fs=require('node:fs'),path=require('node:path');
const root=path.resolve(__dirname,'../../..');
const albumMode=process.argv.includes('--album');
const musicMode=process.argv.includes('--music');
const wanted=process.argv.find(x=>x.startsWith('--ids='))?.slice(6).split(',');
const out=path.join(root,'dist/stereo-research-20260928/public-catalog-'+(musicMode?'music-v1':albumMode?'v2':'v1'));
fs.mkdirSync(out,{recursive:true});app.setPath('userData',path.join(out,'profile'));
let win;const delay=ms=>new Promise(r=>setTimeout(r,ms));
const stop=code=>{if(win&&!win.isDestroyed())win.destroy();app.exit(code);};
app.whenReady().then(async()=>{
  const {corpus}=await import('./repertoire.mjs');
  const isolated=session.fromPartition('research-public-search');
  isolated.setPermissionRequestHandler((_wc,_permission,cb)=>cb(false));
  isolated.on('will-download',event=>event.preventDefault());
  win=new BrowserWindow({show:false,width:1200,height:900,webPreferences:{session:isolated,sandbox:true,contextIsolation:true,nodeIntegration:false,backgroundThrottling:false}});
  win.webContents.setAudioMuted(true);win.webContents.setWindowOpenHandler(()=>({action:'deny'}));
  for(const group of corpus.groups)for(const track of group.tracks){
    if(wanted&&!wanted.includes(track.id))continue;
    const file=path.join(out,track.id+'.json');if(fs.existsSync(file))continue;
    if(!win.webContents.isAudioMuted())throw Error('Mute invariant');
    const url=musicMode?'https://music.youtube.com/search?q='+encodeURIComponent(track.artist+' '+track.title)
      :'https://www.youtube.com/results?search_query='+encodeURIComponent(track.artist+' '+track.title+(albumMode?' provided to youtube':' official audio'));
    try {await win.loadURL(url);} catch(e) {
      // YouTube sometimes performs a same-site themeRefresh navigation while
      // loadURL is pending. This is not a retry of an access denial.
      if(e.code!=='ERR_ABORTED'||!/^https:\/\/(www|music)\.youtube\.com\//.test(win.webContents.getURL()))throw e;
    }
    await delay(musicMode?6500:3500);
    await win.webContents.executeJavaScript(`(()=>{const b=Array.from(document.querySelectorAll('button')).find(x=>x.innerText.trim()==='Rechazar todo');if(b?.getBoundingClientRect().height)b.click();})()`);
    await delay(1000);
    const state=await win.webContents.executeJavaScript(`(()=>{const seen=new Set();return {title:document.title,url:location.href,text:document.body.innerText.slice(0,24000),
      renderers:Array.from(document.querySelectorAll('ytd-video-renderer'),r=>({text:r.innerText,links:Array.from(r.querySelectorAll('a'),a=>({url:a.href,text:a.innerText,title:a.getAttribute('title'),id:a.id}))})).slice(0,20),
      links:Array.from(document.querySelectorAll('a')).filter(a=>a.href.includes('/watch?v=')&&(a.innerText.trim()||a.getAttribute('title'))).map(a=>{
      let p=a;const ancestors=[];for(let i=0;i<5&&p;i++,p=p.parentElement)ancestors.push({tag:p.tagName,id:p.id,text:p.innerText?.slice(0,700)});
      return {href:a.href,text:a.innerText,title:a.getAttribute('title'),ancestors};}).filter(a=>{const v=new URL(a.href).searchParams.get('v');if(seen.has(v))return false;seen.add(v);return true;}).slice(0,12)};})()`);
    if(/confirm you.re not a bot|confirma que no eres un bot|unusual traffic|tr[aá]fico inusual/i.test(state.text))throw Error('Access challenge; stopping without bypass');
    fs.writeFileSync(file,JSON.stringify({track,queriedUtc:new Date().toISOString(),...state},null,2),{flag:'wx'});
    console.log('PUBLIC_CATALOG',track.id,JSON.stringify(state.links.slice(0,3).map(a=>({url:a.href,title:a.title||a.text}))));
  }
  stop(0);
}).catch(e=>{console.error(e.stack);stop(1);});
