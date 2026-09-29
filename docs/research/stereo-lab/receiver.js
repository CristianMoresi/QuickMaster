let captureStream, captureContext, captureNode;
async function startCapture() {
  captureStream = await navigator.mediaDevices.getDisplayMedia({
    video: true, audio: { channelCount: 2, echoCancellation: false, noiseSuppression: false, autoGainControl: false }
  });
  const audio = captureStream.getAudioTracks()[0];
  if (!audio) throw new Error('No audio track returned');
  // On this Windows runtime, hidden sink:none interactive contexts can advance
  // at ~0.65x wall time. A playback-sized render buffer fixes the observed drift;
  // the host still checks sample clock continuously, never assumes this setting.
  captureContext = new AudioContext({ sampleRate: 48000, sinkId: { type: 'none' }, latencyHint: 'playback' });
  if (captureContext.sinkId?.type !== 'none') throw new Error('Silent sink unavailable');
  await captureContext.audioWorklet.addModule('pcm-worklet.js');
  captureNode = new AudioWorkletNode(captureContext, 'pcm-probe', {
    channelCount: 2, channelCountMode: 'explicit', channelInterpretation: 'discrete', outputChannelCount: [2]
  });
  captureNode.port.onmessage = ({ data }) => window.lab.block(data);
  captureNode.onprocessorerror = () => window.lab.error('Audio worklet failed');
  captureContext.createMediaStreamSource(new MediaStream([audio])).connect(captureNode);
  captureNode.connect(captureContext.destination); // sink:none; worklet outputs zeros too.
  await captureContext.resume();
  return { sink: captureContext.sinkId.type, rate: captureContext.sampleRate, baseLatency: captureContext.baseLatency,
    latencyHint: 'playback', channels: audio.getSettings().channelCount,
    settings: audio.getSettings() };
}
async function stopCapture() {
  captureStream?.getTracks().forEach(track => track.stop());
  await captureContext?.close();
}
