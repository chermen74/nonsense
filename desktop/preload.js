const { contextBridge } = require("electron");

contextBridge.exposeInMainWorld("nonsense", {
  quit: () => window.close(),
});
