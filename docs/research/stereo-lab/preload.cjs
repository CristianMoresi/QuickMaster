const { contextBridge, ipcRenderer } = require('electron');
contextBridge.exposeInMainWorld('lab', Object.freeze({
  block: message => ipcRenderer.send('lab-block', message),
  error: message => ipcRenderer.send('lab-error', String(message))
}));
