// Selected from visible public catalogue records, before measuring these tracks.
// Public delivery references, not lossless source-master certification.
import fs from 'node:fs/promises';
import path from 'node:path';
import {corpus} from './repertoire.mjs';
import {fingerprint} from './catalog.mjs';
const sources={
  'house-disco-1':['4D7u5KF7SP8','Random Access Memories, album version'],
  'house-disco-3':['VNg3MxYKSi0','Latch feat. Sam Smith; verify album in public player metadata'],
  'house-disco-4':['9P5gQXslrR0','For Lack of a Better Name; NOT Club Edit'],
  'trance-5':['PyD4QQgJ6O4','Just Be, album version'],
  'dnb-bass-1':['q9I01iNFcL4','Watercolour Full Version, single edition'],
  'pop-1':['fHI8X4OXluQ','Official audio, 2019 XO/Republic; not the music video'],
  'pop-2':['OsfAnsMY21M','Future Nostalgia; solo version, NOT DaBaby remix'],
  'pop-3':['3YgtjHZyCIQ','Anti-Hero; verify Midnights edition in public player metadata'],
  'pop-4':['Faq-ODnfIPw','Dangerous Woman'],
  'art-pop-1':['ZD6rXLXZOEI','WHEN WE ALL FALL ASLEEP, WHERE DO WE GO?; solo, not Bieber remix'],
  'classic-rock-1':['b72gdhV_rXM','The Game; 2011 phonogram, Bob Ludwig credit, catalogue publication 2026'],
  'classic-rock-2':['9vWNauaZAgg','Back In Black; studio song, not River Plate/Donington'],
  'alternative-rock-2':['pqrUQrAcfo4','Do I Wanna Know?; public catalogue song, not music video'],
  'alternative-rock-3':['m2zUrruKjDQ','Hot Fuss; not Thin White Duke remix'],
  'alternative-rock-5':['BMMGwtklEeE','Echoes, Silence, Patience & Grace'],
  'metal-1':['CHIWNDAwTqQ','Metallica; studio song, mastering edition to record from player metadata'],
  'metal-2':['vGtaDvhSxaI','Rammstein; studio album song, not nine-minute narrative video'],
  'metal-3':['B2lmOei7qfk','Vol. 3: The Subliminal Verses (Special Edition)'],
  'metal-4':['zgychWIo6UA','Magma'],
  'metal-5':['H8Wx8GV1Oiw','Sempiternal (Expanded Edition)'],
  'hip-hop-1':['Qeem6ZVr8Ic','Still D.R.E. feat. Snoop Dogg; NOT instrumental'],
  'hip-hop-2':['izhKFB0_2w0','N.Y. State of Mind; NOT Pt. II'],
  'hip-hop-5':['67vr-3kpX3Q','Alright album song; not shortened official video/single'],
  'trap-drill-1':['NQbkGDoD7B0','SICKO MODE studio song'],
  'trap-drill-2':['AMCwYdTJ_PE','FUTURE; NOT Marshmello remix'],
  'rnb-1':['0BdlKkvjEgA','Good Days official audio, SOS edition 2022'],
  'rnb-2':['9cHbvRUALrc','Pink + White, album song'],
  'rnb-5':['CRKXG5YleHU','Voodoo, full album track; not music-video edit'],
  'soul-funk-1':['IYFqc9gk4qI','An Evening With Silk Sonic'],
  'soul-funk-3':['ftdZ363R9kQ','Talking Book'],
  'folk-acoustic-1':['IJ8i49EqgYI','Tracy Chapman, self-titled album'],
  'folk-acoustic-2':['pvC5YD-IjL0','Bon Iver, Bon Iver'],
  'folk-acoustic-4':['B-c3PAENnLU','Heartbeats, public catalogue song; verify Veneer edition'],
  'country-1':['l6_w3887Rwo','Tennessee Whiskey, Chris Stapleton, NOT Joshua Patterson cover'],
  'country-2':['vYZFN4INkhs','Golden Hour; official UMG song with mastering credits'],
  'jazz-1':['GtOcxj3NDBI','Don’t Know Why; NOT First Sessions Demo'],
  'jazz-3':['KJEzFvXx3Xw','So What feat. Coltrane/Adderley/Evans, NOT 1964 live Four & More'],
  'jazz-4':['ryA6eHZNnXY','Time Out, Columbia/Legacy catalogue'],
  'blues-1':['kpC69qIe02E','Completely Well'],
  'blues-2':['LNX4Bl4T1q0','Tin Pan Alley, 9:12 version; studio, not 1982 alternate or live'],
  'orchestral-1':['4Xmg_UVAL2E','Karajan/Berliner, recorded 1977; deliberate premeasurement revision of 1963 draft'],
  'orchestral-5':['LnHdDKi6gYE','Dudamel/Simón Bolívar, Caracas 2008 live; deliberate premeasurement revision of Fiesta draft'],
  'cinematic-1':['jgyShFzdB_Q','Inception original motion picture album, not live Prague/remix'],
  'cinematic-2':['wtHra9tFISY','Harry Potter and the Sorcerer’s Stone original soundtrack'],
  'cinematic-3':['tUMc_-Bcunk','The Blue Notebooks (15 Years); chamber original, not orchestra version'],
  'latin-pop-1':['nOj6d-HOw2w','Oral Fixation Vol. 2 Expanded; original Wyclef version, not anniversary'],
  'latin-pop-3':['mE4Mik_vfJA','Más; not MTV Unplugged'],
  'reggaeton-1':['juRFjpB5Ppg','Un Verano Sin Ti'],
  'reggaeton-3':['i9gxO_5vA-Q','MAÑANA SERÁ BONITO; not Sistek remix'],
  'latin-organic-1':['79OxD7KNlYw','3.0; original salsa, not Versión Pop'],
};
export const jobs=corpus.groups.flatMap(g=>g.tracks.map(t=>{
  if(!sources[t.id])throw Error('Missing selected source: '+t.id);
  return {id:t.id,group:g.id,artist:t.artist,titleMatch:t.title,
    url:'https://www.youtube.com/watch?v='+sources[t.id][0],edition:sources[t.id][1],
    sourceEvidence:'Public visible YouTube/YouTube Music catalogue snapshots in dist/stereo-research-20260928/public-catalog*; selected before measurement',
    expectedDurationSeconds:null,durationToleranceSeconds:3,selectionHash:fingerprint(t),
    sourceMasterVerified:false,purpose:'Stereo descriptors of public streaming delivery, no stored audio'};
}));
if(process.argv[2]){
  const out=path.resolve(process.argv[2]);await fs.mkdir(out,{recursive:true});
  for(const job of jobs)await fs.writeFile(path.join(out,job.id+'.json'),JSON.stringify(job,null,2),{flag:'wx'});
  console.log(`Prepared ${jobs.length} jobs in ${out}`);
}
