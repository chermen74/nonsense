const { contextBridge } = require("electron");

contextBridge.exposeInMainWorld("fidget", {
  quit: () => window.close(),
});
