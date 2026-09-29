import { spawn } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '../../..');
const exe = path.join(root, 'dist/stereo-research-20260928/electron-runtime/node_modules/electron/dist/electron.exe');
const env = { ...process.env };
delete env.ELECTRON_RUN_AS_NODE;
const arguments_ = process.argv.slice(2);
const entry = arguments_[0] === '--public-probe' ? (arguments_.shift(), 'remote-probe.cjs')
  : arguments_[0] === '--clock-probe' ? (arguments_.shift(), 'clock-probe.cjs')
  : arguments_[0] === '--watch' ? (arguments_.shift(), 'watch-capture.cjs')
  : arguments_[0] === '--search' ? (arguments_.shift(), 'search-catalog.cjs') : 'main.cjs';
const child = spawn(exe, [path.join(here, entry), ...arguments_], {
  cwd: root, env, windowsHide: true, stdio: 'inherit'
});
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => child.kill());
child.on('error', error => { console.error(error.message); process.exitCode = 1; });
child.on('exit', code => { process.exitCode = code ?? 1; });
