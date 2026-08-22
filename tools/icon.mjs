// The app icon: his face, with an aura around it.
//
//   node tools/icon.mjs
//
// One photograph in (brand/dog.jpg), every icon the three stores want out. It
// runs in headless Chromium because a canvas is the only image editor this
// repo has, and because doing it in code means the icon can be regenerated
// from the photo rather than being a binary somebody once exported and can
// never adjust again.
//
// Three steps: cut him out of the sand, light him, and lay him on the ground.
//
// The cut is the interesting part. He is a black dog on bright sand, so the
// ground is found rather than the dog: flood inwards from the border over
// anything bright and warm, keep the largest thing left, then put back any
// pocket of "ground" that turns out to have dog all the way round it — which
// is what an eye is. Grit that survives all that is beyond the crop.
//
// The aura is his own outline blurred and lit, not a disc behind him. That is
// the difference between a halo and a spotlight, and it is also what makes a
// black dog legible at 48 pixels: his edge is bright even where he is not.

import { chromium } from "playwright";
import fs from "fs";
import path from "path";
import zlib from "zlib";
import { fileURLToPath } from "url";

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const PHOTO = path.join(ROOT, "brand/dog.jpg");

/** Where he is in the photograph, and how he is cut out of it. */
const CUT = {
  crop: { x: 120, y: 30, w: 480, h: 470 },
  lumT: 100,          // brighter than this...
  warmT: 10,          // ...and warmer than this, and it is sand
  feather: 2,         // passes of a soft edge, so he is not cut with scissors
  lift: 1.35,         // he was photographed in full sun: a silhouette as shot
  gain: 1.0,
};

/**
 * The composition, in fractions of the icon's width, so it holds at 48 and at
 * 1024 alike. The face is centred on the near eye rather than on his bounding
 * box: it puts the ear inside the circle and leaves the grit outside it.
 */
const ART = {
  ground: "#15161a",
  auraR: 0.58,
  aura: [
    [0, "rgba(226,168,74,0.42)"],
    [0.5, "rgba(170,102,44,0.24)"],
    [0.8, "rgba(90,50,26,0.10)"],
    [1, "rgba(21,22,26,0)"],
  ],
  glow: "#ffc86a",
  glows: [
    { blur: 0.075, a: 0.85, times: 2 },
    { blur: 0.030, a: 0.75, times: 2 },
    { blur: 0.009, a: 0.55, times: 1 },
  ],
  rings: [{ r: 0.452, w: 0.008, c: "rgba(247,219,158,0.35)" }],
  fx: 190, fy: 205, span: 410,      // in cut-out pixels
  fill: 0.70, dy: 0,
  hard: 0.335, soft: 0.385,
};

/**
 * Android densities. The adaptive icon is a 108dp canvas of which only the
 * middle 72dp is guaranteed to survive the launcher's mask, so the ground and
 * its warm wash go on the background layer where being cropped costs nothing,
 * and he goes on the foreground layer scaled into the safe circle.
 */
const DPI = { mdpi: 1, hdpi: 1.5, xhdpi: 2, xxhdpi: 3, xxxhdpi: 4 };
const SAFE = 66 / 108;

async function main() {
  const b64 = fs.readFileSync(PHOTO).toString("base64");
  const browser = await chromium.launch({ executablePath: process.env.CHROME || undefined });
  const page = await browser.newPage();
  await page.setContent("<canvas id=c></canvas>");
  await page.evaluate(cutHim, { b64, cut: CUT });
  // compose runs in the page twice — once to a PNG the canvas writes, once to
  // raw bytes this file writes — so it is installed there rather than passed.
  await page.evaluate(`window.compose = ${compose.toString()}`);

  const write = async (rel, size, mode) => {
    const url = await page.evaluate((a) => window.compose(a), { size, mode, art: ART });
    const out = path.join(ROOT, rel);
    fs.mkdirSync(path.dirname(out), { recursive: true });
    fs.writeFileSync(out, Buffer.from(url.split(",")[1], "base64"));
    return rel;
  };

  const wrote = [];
  for (const [dpi, k] of Object.entries(DPI)) {
    const res = `android/app/src/main/res/mipmap-${dpi}`;
    wrote.push(await write(`${res}/ic_launcher_foreground.png`, Math.round(108 * k), "fg"));
    wrote.push(await write(`${res}/ic_launcher_background.png`, Math.round(108 * k), "bg"));
    wrote.push(await write(`${res}/ic_launcher_monochrome.png`, Math.round(108 * k), "mono"));
    wrote.push(await write(`${res}/ic_launcher.png`, Math.round(48 * k), "full"));
    wrote.push(await write(`${res}/ic_launcher_round.png`, Math.round(48 * k), "round"));
  }
  // iOS wants one 1024 with no alpha in it at all — App Store Connect
  // rejects an icon that has an alpha channel, even a fully opaque one, so
  // this one is re-encoded without the channel rather than merely painted
  // over.
  wrote.push(await writeOpaque("ios/App/Assets.xcassets/AppIcon.appiconset/icon-1024.png", 1024));
  // Play's store listing, and the page.
  wrote.push(await write("brand/icon-512.png", 512, "full"));
  wrote.push(await write("brand/icon-180.png", 180, "round"));
  wrote.push(await write("brand/icon-64.png", 64, "round"));

  await browser.close();
  console.log(wrote.join("\n"));

  async function writeOpaque(rel, size) {
    const b = await page.evaluate(rgbOf, { size, mode: "full", art: ART });
    const rgb = Buffer.from(b, "base64");
    const out = path.join(ROOT, rel);
    fs.mkdirSync(path.dirname(out), { recursive: true });
    fs.writeFileSync(out, pngRGB(size, size, rgb));
    return rel;
  }
}

/** Runs in the page: the same composition, as raw RGB with no alpha. */
function rgbOf(arg) {
  const url = window.compose(arg);
  const c = document.createElement("canvas");
  c.width = arg.size; c.height = arg.size;
  const x = c.getContext("2d");
  const im = new Image();
  im.src = url;
  return im.decode().then(() => {
    x.drawImage(im, 0, 0);
    const d = x.getImageData(0, 0, c.width, c.height).data;
    const out = new Uint8Array(c.width * c.height * 3);
    for (let i = 0, j = 0; i < d.length; i += 4, j += 3) {
      out[j] = d[i]; out[j + 1] = d[i + 1]; out[j + 2] = d[i + 2];
    }
    let s = "";
    for (let i = 0; i < out.length; i += 0x8000) {
      s += String.fromCharCode.apply(null, out.subarray(i, i + 0x8000));
    }
    return btoa(s);
  });
}

/**
 * A PNG with three channels and no fourth. Hand-rolled because the only
 * encoder in reach is a canvas, and a canvas always writes RGBA.
 */
function pngRGB(w, h, rgb) {
  const crcTable = [];
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    crcTable[n] = c >>> 0;
  }
  const crc = (buf) => {
    let c = 0xffffffff;
    for (const b of buf) c = crcTable[(c ^ b) & 0xff] ^ (c >>> 8);
    return (c ^ 0xffffffff) >>> 0;
  };
  const chunk = (type, data) => {
    const len = Buffer.alloc(4);
    len.writeUInt32BE(data.length);
    const body = Buffer.concat([Buffer.from(type, "ascii"), data]);
    const c = Buffer.alloc(4);
    c.writeUInt32BE(crc(body));
    return Buffer.concat([len, body, c]);
  };
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0);
  ihdr.writeUInt32BE(h, 4);
  ihdr[8] = 8;          // bit depth
  ihdr[9] = 2;          // truecolour, no alpha
  // filter byte 0 in front of every row: no prediction, just the bytes
  const rows = Buffer.alloc(h * (w * 3 + 1));
  for (let y = 0; y < h; y++) {
    rows[y * (w * 3 + 1)] = 0;
    rgb.copy(rows, y * (w * 3 + 1) + 1, y * w * 3, (y + 1) * w * 3);
  }
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk("IHDR", ihdr),
    chunk("IDAT", zlib.deflateSync(rows, { level: 9 })),
    chunk("IEND", Buffer.alloc(0)),
  ]);
}

/** Runs in the page: cuts him out and leaves him on a canvas called `him`. */
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
  // the biggest thing that is not ground is the dog; the rest is grit
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
  // a pocket of sand with dog all round it is an eye, not ground
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

/**
 * Runs in the page: lays him out at one size.
 *
 *   full  — ground, wash, aura, face: the whole icon
 *   round — the same, masked to a circle
 *   fg    — aura and face only, inside the adaptive icon's safe circle
 *   bg    — ground and wash only, full bleed
 *   mono  — his silhouette in white, for a themed launcher
 */
function compose({ size: S, mode, art }) {
  const him = window.him;
  const c = document.createElement("canvas");
  c.width = S; c.height = S;
  const x = c.getContext("2d");
  const inset = mode === "fg" || mode === "mono" ? 66 / 108 : 1;
  const cx = S / 2, cy = S / 2;
  const pad = () => {
    const p = document.createElement("canvas");
    p.width = S; p.height = S;
    return [p, p.getContext("2d")];
  };

  if (mode === "full" || mode === "round" || mode === "bg") {
    x.fillStyle = art.ground;
    x.fillRect(0, 0, S, S);
    const wash = x.createRadialGradient(cx, cy, S * 0.06, cx, cy, S * art.auraR);
    for (const [at, col] of art.aura) wash.addColorStop(at, col);
    x.fillStyle = wash;
    x.fillRect(0, 0, S, S);
  }
  if (mode === "bg") return c.toDataURL("image/png");

  // his face, softened at the rim so he sits in the light rather than being
  // cut out and pasted onto it
  const [fc, fx] = pad();
  const scale = (S * art.fill * inset) / art.span;
  fx.drawImage(him, cx - art.fx * scale, cy + S * art.dy - art.fy * scale,
               him.width * scale, him.height * scale);
  const m = fx.createRadialGradient(cx, cy + S * art.dy, S * art.hard * inset,
                                    cx, cy + S * art.dy, S * art.soft * inset);
  m.addColorStop(0, "rgba(0,0,0,1)");
  m.addColorStop(1, "rgba(0,0,0,0)");
  fx.globalCompositeOperation = "destination-in";
  fx.fillStyle = m;
  fx.fillRect(0, 0, S, S);

  if (mode === "mono") {
    // A themed icon is one colour: he becomes his own outline.
    x.drawImage(fc, 0, 0);
    x.globalCompositeOperation = "source-in";
    x.fillStyle = "#ffffff";
    x.fillRect(0, 0, S, S);
    return c.toDataURL("image/png");
  }

  const [gc, gx] = pad();
  gx.drawImage(fc, 0, 0);
  gx.globalCompositeOperation = "source-in";
  gx.fillStyle = art.glow;
  gx.fillRect(0, 0, S, S);
  for (const p of art.glows) {
    x.save();
    x.globalAlpha = p.a;
    x.globalCompositeOperation = "lighter";
    x.filter = `blur(${Math.max(S * p.blur * inset, 0.5)}px)`;
    for (let i = 0; i < (p.times || 1); i++) x.drawImage(gc, 0, 0);
    x.restore();
  }
  for (const ring of art.rings) {
    x.beginPath();
    x.arc(cx, cy, S * ring.r * inset, 0, Math.PI * 2);
    x.strokeStyle = ring.c;
    x.lineWidth = Math.max(S * ring.w * inset, 0.75);
    x.stroke();
  }
  x.drawImage(fc, 0, 0);

  if (mode === "round") {
    const [rc, rx] = pad();
    rx.drawImage(c, 0, 0);
    rx.globalCompositeOperation = "destination-in";
    rx.beginPath();
    rx.arc(cx, cy, S / 2, 0, Math.PI * 2);
    rx.fillStyle = "#000";
    rx.fill();
    return rc.toDataURL("image/png");
  }
  return c.toDataURL("image/png");
}

await main();
