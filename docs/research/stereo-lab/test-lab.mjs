import test from 'node:test';
import assert from 'node:assert/strict';
import {corpus} from './repertoire.mjs';
import {validateSelection,rankCandidates,fingerprint} from './catalog.mjs';
import {validateSources,runStudy} from './study.mjs';
import os from 'node:os';
import fs from 'node:fs/promises';
import path from 'node:path';
import vm from 'node:vm';
import {readFileSync} from 'node:fs';

function worklet(){
  const messages=[];let Constructor;
  const context={Float32Array,currentFrame:0,AudioWorkletProcessor:class{constructor(){this.port={postMessage:x=>messages.push(x)};}},
    registerProcessor:(_name,c)=>{Constructor=c;}};
  vm.runInNewContext(readFileSync(new URL('./pcm-worklet.js',import.meta.url),'utf8'),context);
  const processor=new Constructor(),outputs=[[new Float32Array(128),new Float32Array(128)]];
  return {messages,outputs,tick(input){const result=processor.process([input],outputs);context.currentFrame+=128;return result;}};
}
test('empty input preserves sample clock and is flagged, never silently omitted',()=>{
  const w=worklet();for(let i=0;i<32;i++)w.tick([]);
  assert.equal(w.messages.length,1);assert.equal(w.messages[0].missingInputFrames,4096);
  assert.equal(w.messages[0].endContextFrame,4096);assert.ok(new Float32Array(w.messages[0].pcm).every(x=>x===0));
});
test('stereo PCM retained; output stays silent',()=>{
  const w=worklet();for(let i=0;i<32;i++)w.tick([new Float32Array(128).fill(.25),new Float32Array(128).fill(-.5)]);
  const x=new Float32Array(w.messages[0].pcm);assert.equal(x[0],.25);assert.equal(x[1],-.5);
  assert.equal(w.messages[0].missingInputFrames,0);assert.ok(w.outputs[0].every(x=>x.every(v=>v===0)));
});
test('empty channel buffers are explicit gaps, not NaN or time compression',()=>{
  const w=worklet();for(let i=0;i<32;i++)w.tick([new Float32Array(0),new Float32Array(0)]);
  assert.equal(w.messages[0].missingInputFrames,4096);assert.ok(new Float32Array(w.messages[0].pcm).every(x=>x===0));
});
test('unexpected mono route rejects rather than pretending stereo',()=>{
  const w=worklet();assert.equal(w.tick([new Float32Array(128)]),false);assert.equal(w.messages[0].channels,1);
});
test('nonfinite input is not silently converted to silence',()=>{
  const w=worklet();for(let i=0;i<32;i++)w.tick([new Float32Array(128).fill(NaN),new Float32Array(128)]);
  assert.ok(Number.isNaN(new Float32Array(w.messages[0].pcm)[0]));
});
test('sequence and clock continue across present and absent input',()=>{
  const w=worklet();for(let i=0;i<64;i++)w.tick(i<32?[]:[new Float32Array(128),new Float32Array(128)]);
  assert.deepEqual(w.messages.map(x=>x.sequence),[0,1]);assert.deepEqual(w.messages.map(x=>x.endContextFrame),[4096,8192]);
});

test('exact agreed scope: ten families, five distinct artists each',()=>{
  assert.deepEqual(validateSelection(corpus),{groups:10,tracks:50});
  assert.ok(corpus.groups.every(g=>g.tracks.length===5));
});
test('reject undersized groups',()=>{
  const c=structuredClone(corpus);c.groups[0].tracks.pop();assert.throws(()=>validateSelection(c));
});
test('reject duplicated observations',()=>{
  const c=structuredClone(corpus);c.groups[0].tracks[1]=c.groups[0].tracks[0];assert.throws(()=>validateSelection(c));
});
test('metadata match does not authorize audio or validate master',()=>{
  const track=corpus.groups[0].tracks[0];
  const r=rankCandidates(track,[{kind:'song',trackName:track.title,artistName:track.artist,
    collectionName:track.intendedAlbum,previewUrl:'https://example.invalid/audio',trackViewUrl:'https://example.invalid/catalog'}]);
  assert.equal(r[0].score,10);assert.equal(r[0].previewUrl,undefined);assert.equal(r[0].masteringEditionVerified,undefined);
});
test('remix title does not equal original',()=>{
  const t=corpus.groups[0].tracks[0];const r=rankCandidates(t,[{kind:'song',trackName:t.title+' (Remix)',artistName:t.artist}]);
  assert.equal(r[0].titleMatch,false);
});
test('empty source registry is allowed, not complete',()=>validateSources({tracks:[]}));
test('unreviewed/local unknown inputs rejected',()=>{
  assert.throws(()=>validateSources({tracks:[{id:'not-in-corpus'}]}));
  assert.throws(()=>validateSources({tracks:[{id:corpus.groups[0].tracks[0].id,permission:'unknown'}]}));
});
test('cache key changes when provenance changes',()=>{
  assert.notEqual(fingerprint({edition:'original'}),fingerprint({edition:'remaster'}));
});
test('missing audio never produces measurements or genre profiles',async()=>{
  const dir=await fs.mkdtemp(path.join(os.tmpdir(),'stereo-study-test-'));
  try {const r=await runStudy({tracks:[]},dir);
    assert.equal(r.complete,false);assert.equal(r.profilesEnabled,0);assert.equal(r.rows.length,50);
    assert.ok(r.rows.every(x=>x.state==='missing_authorized_audio'));assert.ok(r.coverage.every(x=>x.measured===0));
  } finally {await fs.rm(dir,{recursive:true});}
});
