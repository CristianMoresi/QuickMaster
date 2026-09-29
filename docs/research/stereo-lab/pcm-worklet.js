class PcmProbe extends AudioWorkletProcessor {
  constructor() { super(); this.sequence = 0; this.used = 0; this.missing = 0; this.samples = new Float32Array(4096 * 2); }
  process(inputs, outputs) {
    const input = inputs[0];
    if (input.length !== 0 && input.length !== 2) {
      this.port.postMessage({sequence:this.sequence++,channels:input.length,error:'Unexpected channel layout'});
      return false;
    }
    const n=outputs[0][0].length;
    const available=input.length===2?input[0].length:0;
    if(input.length===2&&(input[1].length!==available||available>n)){
      this.port.postMessage({sequence:this.sequence++,channels:0,error:'Mismatched quantum size'});return false;
    }
    // A disconnected/empty input still occupies time. Record zeros AND flag it,
    // rather than silently shortening the timeline. QC decides if it's expected.
    this.missing+=n-available;
    for (let i = 0; i < n; ++i) {
      this.samples[this.used++] = i<available ? input[0][i] : 0; this.samples[this.used++] = i<available ? input[1][i] : 0;
      if (this.used === this.samples.length) {
        this.port.postMessage({ sequence: this.sequence++, channels: 2, missingInputFrames:this.missing,
          endContextFrame:currentFrame+n, pcm: this.samples.buffer }, [this.samples.buffer]);
        this.samples = new Float32Array(4096 * 2); this.used = 0; this.missing=0;
      }
    }
    return true; // zero-filled outputs; never route captured PCM to speakers
  }
}
registerProcessor('pcm-probe', PcmProbe);
