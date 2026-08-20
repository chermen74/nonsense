// Fidget desktop — a sheer pane of glass over your whole screen.
// The window is transparent and always-on-top; your Zoom call, email,
// everything stays visible (and live) underneath the 12% tint.
// Esc closes it. Tab toggles ball/dial.

const { app, BrowserWindow, screen } = require("electron");
const path = require("path");

function createWindow() {
  const { bounds } = screen.getPrimaryDisplay();

  // NOTE: on Windows, `fullscreen: true` breaks transparency, so we make a
  // frameless window sized to the display bounds instead.
  const win = new BrowserWindow({
    x: bounds.x,
    y: bounds.y,
    width: bounds.width,
    height: bounds.height,
    frame: false,
    transparent: true,
    resizable: false,
    alwaysOnTop: true,
    hasShadow: false,
    skipTaskbar: false,
    webPreferences: {
      contextIsolation: true,
      preload: path.join(__dirname, "preload.js"),
    },
  });

  win.setAlwaysOnTop(true, "screen-saver");
  win.loadFile("renderer.html");
}

app.whenReady().then(createWindow);
app.on("window-all-closed", () => app.quit());
