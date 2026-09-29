let fixtureContext, fixtureSource;
async function startFixture({midHz=1000,sideHz=1300}={}) {
  fixtureContext = new AudioContext({ sampleRate: 48000 });
  const n = 48000 * 12;
  const buffer = fixtureContext.createBuffer(2, n, 48000);
  for (let i = 0; i < n; ++i) {
    const m = .2 * Math.sin(2 * Math.PI * midHz * i / 48000);
    const s = .1 * Math.sin(2 * Math.PI * sideHz * i / 48000);
    buffer.getChannelData(0)[i] = m + s;
    buffer.getChannelData(1)[i] = m - s;
  }
  fixtureSource = fixtureContext.createBufferSource(); fixtureSource.buffer = buffer;
  fixtureSource.connect(fixtureContext.destination);
  await fixtureContext.resume(); fixtureSource.start();
}
async function stopFixture() { fixtureSource?.stop(); await fixtureContext?.close(); }
