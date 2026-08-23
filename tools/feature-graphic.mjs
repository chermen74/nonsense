// Play's feature graphic: 1024×500, the banner above the listing.
//
//   node tools/feature-graphic.mjs
//
// Built the same way the icon is — from brand/dog.jpg, in headless Chromium —
// so it stays in step with the icon rather than being a separate thing that
// drifts. It reuses the icon tool's cut and its aura wholesale; the only new
// work here is the layout.
//
// Two constraints shape it. Play crops this image differently across the
// surfaces it appears on, sometimes to a much wider strip, and on some of them
// it lays the app icon over the middle. So: nothing that matters within 8% of
// any edge, and nothing that matters in the centre either — he sits left of
// centre and the words sit right of it, with the middle deliberately quiet.
//
// It is written without an alpha channel for the same reason the iOS icon is:
// stores are happier with opaque art, and there is nothing here to see through.

import { chromium } from "playwright";
import fs from "fs";
import path from "path";
import { fileURLToPath } from "url";

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const PHOTO = path.join(ROOT, "brand/dog.jpg");
const OUT = path.join(ROOT, "brand/feature-graphic.png");

const W = 1024;
const H = 500;

/** Same cut as the icon: see tools/icon.mjs for why each number is what it is. */
const CUT = {
  crop: { x: 20, y: 55, w: 1044, h: 1045 },
  lumT: 110,
  warmT: 0,
  feather: 3,
  lift: 1.3,
  gain: 1.0,
};

const ART = {
  ground: "#15161a",
  glow: "#ffc86a",
  // He sits left of centre, at a size that survives the widest crop Play uses.
  faceX: 0.26,
  faceY: 0.52,
  faceR: 0.185,          // of the banner's width
  wordX: 0.47,
  glows: [
    { blur: 0.055, a: 0.8, times: 2 },
    { blur: 0.022, a: 0.7, times: 2 },
    { blur: 0.007, a: 0.5, times: 1 },
  ],
};

async function main() {
  const b64 = fs.readFileSync(PHOTO).toString("base64");
  const browser = await chromium.launch({ executablePath: process.env.CHROME || undefined });
  const page = await browser.newPage({ viewport: { width: W, height: H } });
  await page.setContent(
    `<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=IBM+Plex+Mono:wght@400;500&family=IBM+Plex+Sans:wght@300;400&display=swap">
     <style>html,body{margin:0;background:#15161a}</style>
     <canvas id="c" width="${W}" height="${H}"></canvas>`,
  );
  // The wordmark is set in the app's own faces; give them a moment to arrive
  // or the banner renders in a fallback that looks nothing like the toy.
  await page.evaluate(() => document.fonts.ready);
  await page.waitForTimeout(400);

  await page.evaluate(cutHim, { b64, cut: CUT });
  const url = await page.evaluate(paint, { art: ART, W, H });
  fs.writeFileSync(OUT, Buffer.from(url.split(",")[1], "base64"));
  await browser.close();
  console.log(`${path.relative(ROOT, OUT)}  ${W}×${H}`);
}

/** Runs in the page: cuts him out. Identical to the icon tool's step. */
async function cutHim({ b64, cut }) {
  const img = new Image();
  img.src = "data:image/jpeg;base64," + b64;
  await img.decode();
  const { crop, lumT, warmT, feather, lift, gain } = cut;
  const c = document.createElement("canvas");
  c.width = crop.w; c.height = crop.h;
  const x = c.getContext("2d");
  x.drawImage(img, crop.x, crop.y, crop.w, crop.h, 0, 0, crop.w, crop.h);
  const im = x.getImageData(0, 0, c.width, c.height);
  const d = im.data, W = c.width, H = c.height, N = W * H;

  const sandy = (i) => {
    const r = d[i * 4], g = d[i * 4 + 1], bl = d[i * 4 + 2];
    return 0.299 * r + 0.587 * g + 0.114 * bl > lumT && r - bl > warmT;
  };
  const nb = (i, f) => {
    const px = i % W, py = (i / W) | 0;
    if (px > 0) f(i - 1);
    if (px < W - 1) f(i + 1);
    if (py > 0) f(i - W);
    if (py < H - 1) f(i + W);
  };
  const flood = (ok) => {
    const m = new Uint8Array(N), st = [];
    const seed = (p) => { if (!m[p] && ok(p)) { m[p] = 1; st.push(p); } };
    for (let i = 0; i < W; i++) { seed(i); seed((H - 1) * W + i); }
    for (let j = 0; j < H; j++) { seed(j * W); seed(j * W + W - 1); }
    while (st.length) nb(st.pop(), (n) => { if (!m[n] && ok(n)) { m[n] = 1; st.push(n); } });
    return m;
  };

  const bg = flood(sandy);
  const lab = new Int32Array(N).fill(-1);
  let best = -1, bestN = 0;
  for (let i = 0; i < N; i++) {
    if (bg[i] || lab[i] >= 0) continue;
    const st = [i]; lab[i] = i; let n = 0;
    while (st.length) {
      const p = st.pop(); n++;
      nb(p, (q) => { if (!bg[q] && lab[q] < 0) { lab[q] = i; st.push(q); } });
    }
    if (n > bestN) { bestN = n; best = i; }
  }
  const keep = new Uint8Array(N);
  for (let i = 0; i < N; i++) if (lab[i] === best) keep[i] = 1;
  const outside = flood((p) => !keep[p]);
  for (let i = 0; i < N; i++) if (!keep[i] && !outside[i]) keep[i] = 1;

  for (let i = 0; i < N; i++) {
    if (!keep[i]) continue;
    for (let k = 0; k < 3; k++) {
      const v = d[i * 4 + k] / 255;
      d[i * 4 + k] = Math.min(255, Math.round(255 * Math.pow(v, 1 / lift) * gain));
    }
  }
  const alpha = new Float32Array(N);
  for (let i = 0; i < N; i++) alpha[i] = keep[i];
  for (let pass = 0; pass < feather; pass++) {
    const src = alpha.slice();
    for (let y = 1; y < H - 1; y++) {
      for (let xx = 1; xx < W - 1; xx++) {
        const i = y * W + xx;
        alpha[i] = (src[i] * 4 + src[i - 1] + src[i + 1] + src[i - W] + src[i + W]) / 8;
      }
    }
  }
  for (let i = 0; i < N; i++) d[i * 4 + 3] = Math.round(alpha[i] * 255);
  x.putImageData(im, 0, 0);
  window.him = c;
}

/** Runs in the page: lays out the banner. */
function paint({ art, W, H }) {
  const him = window.him;
  const c = document.getElementById("c");
  const x = c.getContext("2d");

  x.fillStyle = art.ground;
  x.fillRect(0, 0, W, H);

  const cx = W * art.faceX;
  const cy = H * art.faceY;
  const r = W * art.faceR;

  // A wide warm wash behind him, falling to the ground before it reaches the
  // words — the banner has to read at a glance, and a lit half against a dark
  // half does that better than an even field.
  const wash = x.createRadialGradient(cx, cy, r * 0.2, cx, cy, W * 0.55);
  wash.addColorStop(0, "rgba(226,168,74,0.30)");
  wash.addColorStop(0.45, "rgba(170,102,44,0.16)");
  wash.addColorStop(1, "rgba(21,22,26,0)");
  x.fillStyle = wash;
  x.fillRect(0, 0, W, H);

  const layer = () => {
    const p = document.createElement("canvas");
    p.width = W; p.height = H;
    return [p, p.getContext("2d")];
  };

  // his face, soft at the rim
  const [fc, fx] = layer();
  const scale = (r * 2) / 1010;
  fx.drawImage(him, cx - 520 * scale, cy - 505 * scale, him.width * scale, him.height * scale);
  const m = fx.createRadialGradient(cx, cy, r * 0.86, cx, cy, r * 1.0);
  m.addColorStop(0, "rgba(0,0,0,1)");
  m.addColorStop(1, "rgba(0,0,0,0)");
  fx.globalCompositeOperation = "destination-in";
  fx.fillStyle = m;
  fx.fillRect(0, 0, W, H);

  // the aura is his own outline, as on the icon
  const [gc, gx] = layer();
  gx.drawImage(fc, 0, 0);
  gx.globalCompositeOperation = "source-in";
  gx.fillStyle = art.glow;
  gx.fillRect(0, 0, W, H);
  for (const p of art.glows) {
    x.save();
    x.globalAlpha = p.a;
    x.globalCompositeOperation = "lighter";
    x.filter = `blur(${W * p.blur}px)`;
    for (let i = 0; i < (p.times || 1); i++) x.drawImage(gc, 0, 0);
    x.restore();
  }
  x.drawImage(fc, 0, 0);

  // The words, right of the quiet middle. Each line is measured and shrunk
  // until it fits inside the safe box rather than trusted to: a line that runs
  // off the edge is the one thing Play's crop is guaranteed to make worse, and
  // the first version of this banner lost the end of two of the three.
  const wx = W * art.wordX;
  const right = W * 0.955;
  x.textAlign = "left";
  x.textBaseline = "alphabetic";

  const line = (text, size, weight, family, spacing, colour, y) => {
    let px = size;
    for (let i = 0; i < 40; i++) {
      x.font = `${weight} ${px}px ${family}`;
      x.letterSpacing = `${spacing}px`;
      if (wx + x.measureText(text).width <= right) break;
      px -= 1;
    }
    x.fillStyle = colour;
    x.fillText(text, wx, y);
    return px;
  };

  line("NONSENSE", 78, 500, "'IBM Plex Mono', ui-monospace, monospace", 10,
       "#eeeae2", H * 0.45);
  line("Something to do with your hands.", 30, 300,
       "'IBM Plex Sans', system-ui, sans-serif", 0,
       "rgba(238,234,226,0.62)", H * 0.585);
  line("six toys · no scores · no account", 22, 400,
       "'IBM Plex Mono', ui-monospace, monospace", 2,
       "rgba(255,200,106,0.85)", H * 0.71);
  x.letterSpacing = "0px";

  return c.toDataURL("image/png");
}

await main();
