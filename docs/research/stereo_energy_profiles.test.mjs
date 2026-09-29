import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import crypto from 'node:crypto';
import {energyShare,sideGainForShare,deriveProfiles} from './stereo_energy_profiles.mjs';

test('Known energy splits are not amplitude/volume shares',()=>{
  assert.deepEqual(energyShare(.6,.4),{mid:.6,side:.4});
  assert.ok(Math.abs(energyShare(.6**2,.4**2).side-4/13)<1e-15);
  assert.deepEqual(energyShare(1,1),{mid:.5,side:.5});
});
test('Silent and degenerate components are explicit',()=>{
  assert.equal(energyShare(0,0),null);
  assert.deepEqual(energyShare(1,0),{mid:1,side:0});
  assert.deepEqual(energyShare(0,1),{mid:0,side:1});
  assert.equal(sideGainForShare(1,0,.4),null);
  assert.equal(sideGainForShare(0,1,.4),null);
});
test('Invalid input is rejected; large finite energies remain defined',()=>{
  for(const x of [NaN,Infinity,-1]) assert.throws(()=>energyShare(1,x));
  for(const x of [NaN,0,1,-1,Infinity]) assert.throws(()=>sideGainForShare(1,1,x));
  assert.deepEqual(energyShare(1e308,1e308),{mid:.5,side:.5});
});
test('Unrepresentable correction is explicit instead of infinite gain or underflow',()=>{
  assert.equal(sideGainForShare(Number.MAX_VALUE,Number.MIN_VALUE,.5),null);
  assert.equal(sideGainForShare(Number.MIN_VALUE,Number.MAX_VALUE,Number.MIN_VALUE),null);
  assert.ok(Number.isFinite(sideGainForShare(1e300,1e-300,.5)));
});
test('Ratio is invariant to common gain and side-only gain reaches the target',()=>{
  for(const pm of [1e-8,.1,1,100]) for(const ps of [1e-9,.01,1,100]) for(const target of [.01,.1,.4,.8]) {
    const p=energyShare(pm,ps);
    assert.ok(Math.abs(energyShare(pm*49,ps*49).side-p.side)<1e-14);
    const g=sideGainForShare(pm,ps,target);
    assert.ok(Math.abs(energyShare(pm,ps*g*g).side-target)<1e-14);
  }
});
test('The actual corpus yields ten equally weighted five-song profiles',()=>{
  const bytes=fs.readFileSync(new URL('./stereo-study-20260928/study.json',import.meta.url));
  const check=JSON.parse(fs.readFileSync(new URL('./stereo-study-20260928/verification.json',import.meta.url)));
  const hash=crypto.createHash('sha256').update(bytes).digest('hex');
  assert.equal(hash,check.study_sha256);
  const profiles=deriveProfiles(JSON.parse(bytes),hash).profiles;
  assert.equal(profiles.length,10);
  for(const p of profiles) {
    assert.equal(p.references.length,5);
    assert.ok(Math.abs(p.targetMid+p.targetSide-1)<1e-15);
    assert.ok(Math.abs(p.targetSide-p.references.reduce((s,r)=>s+r.side,0)/5)<1e-15);
  }
  assert.ok(Math.abs(profiles.find(p=>p.id==='electronic').targetSide-.11743)<.00001);
  assert.ok(Math.abs(profiles.find(p=>p.id==='orchestral-cinematic').targetSide-.35096)<.00001);
});
