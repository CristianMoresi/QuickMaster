// Metadata only. Never requests previews, media streams, authentication or cookies.
import fs from 'node:fs/promises';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { createHash } from 'node:crypto';
import { corpus } from './repertoire.mjs';

export const fingerprint = value => createHash('sha256').update(JSON.stringify(value)).digest('hex');
export const normalize = value => value.normalize('NFKD').replace(/\p{M}/gu,'')
  .toLowerCase().replace(/[^\p{L}\p{N}]/gu,'');
export function validateSelection(selection) {
  const ids = new Set(), works = new Set();
  for (const group of selection.groups) {
    if (!/^[a-z][a-z0-9-]*$/.test(group.id) || group.tracks.length < 5) throw Error('Invalid or undersized stratum');
    const artists = new Set();
    for (const track of group.tracks) {
      if (!/^[a-z][a-z0-9-]*$/.test(track.id) || ids.has(track.id)) throw Error('Invalid or duplicate ID');
      const key = normalize(track.artist)+'|'+normalize(track.title)+'|'+normalize(track.intendedAlbum);
      if (works.has(key)) throw Error('Duplicate performance candidate');
      if (!track.title || !track.artist || !track.intendedAlbum) throw Error('Missing provenance');
      ids.add(track.id); works.add(key); artists.add(normalize(track.artist));
    }
    if (artists.size < 5) throw Error('Need five distinct artist/performance units per stratum');
  }
  return { groups: selection.groups.length, tracks: ids.size };
}
export function rankCandidates(track, results) {
  return results.filter(r => r.kind === 'song').map(r => {
    const artistMatch = normalize(r.artistName) === normalize(track.artist);
    const titleMatch = normalize(r.trackName) === normalize(track.title);
    const albumMatch = normalize(r.collectionName || '') === normalize(track.intendedAlbum);
    return { trackId:r.trackId, artist:r.artistName, title:r.trackName, album:r.collectionName,
      releaseDate:r.releaseDate, durationMs:r.trackTimeMillis, explicitness:r.trackExplicitness,
      catalogGenre:r.primaryGenreName, url:r.trackViewUrl, country:r.country,
      artistMatch, titleMatch, albumMatch, score:4*artistMatch+4*titleMatch+2*albumMatch };
  }).sort((a,b)=>b.score-a.score).slice(0,8);
}
export async function verifyCatalog(directory, { fetchImpl=fetch, intervalMs=3200, limit=Infinity }={}) {
  validateSelection(corpus);
  await fs.mkdir(directory,{recursive:true});
  let requested=0, previousRequest=0;
  for (const group of corpus.groups) for (const track of group.tracks) {
    const output=path.join(directory,track.id+'.json'), selectionHash=fingerprint(track);
    try {
      const cache=JSON.parse(await fs.readFile(output,'utf8'));
      if(cache.selectionHash!==selectionHash) throw Error('Stale metadata; choose a new directory');
      if(cache.status==='ok') continue;
      // An error is evidence, not permission to rewrite it. Use another run directory.
      throw Error(`Prior metadata failure for ${track.id}; inspect before retry`);
    } catch(error) { if(error.code!=='ENOENT') throw error; }
    if(requested++>=limit) return;
    await new Promise(resolve=>setTimeout(resolve,Math.max(0,intervalMs-(Date.now()-previousRequest))));
    const query=new URL('https://itunes.apple.com/search');
    query.search=new URLSearchParams({term:`${track.artist} ${track.title}`,entity:'song',media:'music',country:'US',limit:'25'});
    let record={id:track.id,selectionHash,queriedUtc:new Date().toISOString(),query:query.href,
      status:'error',candidates:[],audioAccessGranted:false,masteringEditionVerified:false};
    previousRequest=Date.now();
    try {
      const response=await fetchImpl(query,{signal:AbortSignal.timeout(25000)});
      if(!response.ok) throw Error(`Metadata HTTP ${response.status}`);
      const data=await response.json();
      record={...record,status:'ok',resultCount:data.resultCount,candidates:rankCandidates(track,data.results)};
    } catch(error) { record.error=error.message; }
    await fs.writeFile(output,JSON.stringify(record,null,2),{flag:'wx'});
    console.log(`CATALOG ${track.id}: ${record.status}, ${record.candidates.length} candidates`);
    if(record.status==='error') throw Error(record.error+'; stopped, no retry storm');
  }
}
export async function exportSelection(directory, metadataDir) {
  const counts=validateSelection(corpus);
  await fs.mkdir(directory,{recursive:true});
  const selection={...corpus,counts,selectionSha256:fingerprint(corpus)};
  const lines=['# Stereo Image — repertorio preseleccionado', '',
    `${counts.tracks} referencias, cinco por cada una de ${counts.groups} familias generales. Selección editorial anterior a medir; no demuestra una relación M/S ideal.`, '',
    'Los álbumes identifican la interpretación buscada, no certifican la edición del master. Las reediciones, remixes, versiones de radio, directos y masters Atmos/binaurales deben identificarse por separado. Las referencias históricas no se agregan como si todas fueran masters actuales.', '',
    'Estado: selección preparada; los enlaces verifican candidatos de catálogo, NO acceso al audio ni mediciones. Ninguna preview se usa como canción completa.',''];
  let matched=0,lookedUp=0;
  for(const group of selection.groups) {
    lines.push(`## ${group.label}`,'','| ID | Artista | Tema / álbum previsto | Candidato de catálogo |','| --- | --- | --- | --- |');
    for(const track of group.tracks) {
      let candidate;
      if(metadataDir) {
        try {
          const data=JSON.parse(await fs.readFile(path.join(metadataDir,track.id+'.json'),'utf8'));
          if(data.selectionHash!==fingerprint(track)) throw Error('Metadata selection mismatch');
          if(data.status==='ok') {lookedUp++; candidate=data.candidates[0];}
        } catch(error) {if(error.code!=='ENOENT') throw error;}
      }
      const exact=candidate?.artistMatch&&candidate?.titleMatch&&candidate?.albumMatch;
      if(exact) matched++;
      track.catalogCandidate=candidate||null;
      const esc=s=>String(s).replaceAll('|','\\|').replaceAll('\n',' ');
      lines.push(`| ${track.id} | ${esc(track.artist)} | ${esc(track.title)} — ${esc(track.intendedAlbum)} | ${candidate?.url?`[${exact?'Coincidencia textual':'Revisar versión/intérprete'}](${candidate.url})`:'Pendiente'} |`);
    }
    lines.push('');
  }
  selection.metadataCoverage={lookedUp,exactTextCandidates:matched,verifiedMasters:0,measuredFamousSongs:0};
  lines.push('## Límites de cobertura','',
    'Cinco temas por familia son un mínimo exploratorio, no evidencia de todos sus subgéneros. Las diez familias acordadas agrupan variaciones de producción: conservar sus etiquetas sin convertirlas en treinta perfiles. Flamenco, músicas tradicionales, gospel y otros estilos no tienen cobertura específica en esta primera selección.','',
    'La unidad de observación es el master de una interpretación. Las miles de ventanas del mismo tema NO son miles de canciones independientes. No habrá un perfil habilitado con menos de cinco masters válidos de cinco unidades artísticas independientes.');
  await fs.writeFile(path.join(directory,'selection.json'),JSON.stringify(selection,null,2));
  await fs.writeFile(path.join(directory,'selection.md'),lines.join('\n')+'\n');
  console.log(JSON.stringify({counts,...selection.metadataCoverage}));
}
if(process.argv[1] && import.meta.url===pathToFileURL(path.resolve(process.argv[1])).href) {
  const [command,directory,extra]=process.argv.slice(2);
  if(!directory) throw Error('Usage: catalog.mjs verify OUTPUT_DIR [LIMIT] | export OUTPUT_DIR [METADATA_DIR]');
  if(command==='verify') await verifyCatalog(directory,{limit:extra?Number(extra):Infinity});
  else if(command==='export') await exportSelection(directory,extra);
  else throw Error('Unknown command');
}
