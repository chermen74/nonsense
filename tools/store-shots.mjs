// Renders the store screenshots at each store's required size, from the web
// build, with the page's own browser chrome hidden.
//
//   npm i -D playwright && npx playwright install chromium
//   node tools/store-shots.mjs
//
// Paint is drawn with real pointer drags rather than by moving the ball
// directly: the trail is laid by motion, and teleporting the ball leaves a
// blank canvas.
import { chromium } from 'playwright';
const b = await chromium.launch({ executablePath: '/opt/pw-browsers/chromium-1194/chrome-linux/chrome' });
import { fileURLToPath } from 'url';
import path from 'path';
const here = path.dirname(fileURLToPath(import.meta.url));
const URL = 'file://' + path.resolve(here, '..', 'web', 'index.html');
const OUT = path.resolve(here, '..', 'store');

// Play wants 1080x1920 minimum; Apple's 6.7" slot is 1290x2796.
const SIZES = [
  { tag: 'play',  w: 1080, h: 1920 },
  { tag: 'ios67', w: 1290, h: 2796 },
];

const SCENES = {
  title: null,
  lightning: async () => {
    openFromMenu('bolt'); ink.canvas = 5; clearEtched();
    const shots = [[2,0.18,0.78,2400,-1500],[6,0.82,0.72,-2200,-1700],[4,0.5,0.2,200,2800],
                   [7,0.12,0.35,2700,1100],[8,0.9,0.3,-2400,1400],[3,0.45,0.9,900,-2600]];
    for (const [f,px,py,vx,vy] of shots) {
      ink.family = f;
      fireBolt(W*px, H*py, vx*(W/390), vy*(H/844));
      for (let i=0;i<90 && bolts.length;i++) await new Promise(r=>requestAnimationFrame(r));
    }
  },
  bumpers: async () => {
    openFromMenu('bumpers'); ink.canvas = 5; paint.onBumpers = true;
    ink.family = 6; ball.sizeIndex = 4;
    bx = W*0.3; by = H*0.2; vx = 700*(W/390); vy = 1400*(H/844);
    for (let i=0;i<220;i++) await new Promise(r=>requestAnimationFrame(r));
  },
  dial: async () => {
    openFromMenu('dial'); ink.canvas = 5; ink.family = 0;
    dial.omega = 9;
    for (let i=0;i<40;i++) await new Promise(r=>requestAnimationFrame(r));
  },
  // Two presses rather than one: a single break reads as a smudge at
  // thumbnail size, and the crossing rings are the thing worth showing.
  glass: async () => {
    openFromMenu('glass'); ink.canvas = 6; ink.family = 2; ink.tone = 1;
    clearGlass();
    breakGlass(W * 0.40, H * 0.34);
    breakGlass(W * 0.64, H * 0.58);
    for (let i=0;i<20;i++) await new Promise(r=>requestAnimationFrame(r));
  },
  // A word, because "the bumpers can be letters" is the one feature nobody
  // guesses from a picture of circles.
  letters: async () => {
    openFromMenu('bumpers'); ink.canvas = 5; ink.family = 4; paint.onBumpers = false;
    // TOY rather than a word with an N in it: at a glance, a stretched and
    // turned N reads as an H, which makes the picture look like a bug rather
    // than a feature. T, O and Y each survive being large.
    table = 'TOY'.split('').map((ch, i) => sane({
      nx: 0.22 + i * 0.28, ny: 0.30, size: 0.095, shape: 'circle',
      rot: (i - 1) * 0.13, tone: [1, 3, 2][i], glyph: ch,
    }));
    // and a pulled bar and a hexagon below, so the shot says "any shape,
    // pulled about" as well as "letters"
    table.push(sane({ nx: 0.33, ny: 0.60, size: 0.075, shape: 'circle', rot: 0.4,
                      tone: 0, sx: 2.4, sy: 0.5 }));
    table.push(sane({ nx: 0.72, ny: 0.63, size: 0.085, shape: 'hexagon', rot: 0.3, tone: 3 }));
    ball.x = W * 0.5; ball.y = H * 0.86; ball.vx = 520 * (W / 390); ball.vy = -1500 * (H / 844);
    for (let i=0;i<150;i++) await new Promise(r=>requestAnimationFrame(r));
  },
};

for (const s of SIZES) {
  for (const [name, setup] of Object.entries(SCENES)) {
    const page = await b.newPage({ viewport: { width: s.w / 3, height: s.h / 3 }, deviceScaleFactor: 3 });
    await page.goto(URL);
    await page.waitForTimeout(450);
    // The page's own DOM chips are a browser convenience, not part of the app.
    await page.evaluate(() => {
      document.getElementById('controls').style.display = 'none';
      document.getElementById('note').style.display = 'none';
      document.querySelector('.under').style.display = 'none';
      document.querySelector('.band').style.display = 'none';
    });
    if (setup) await page.evaluate(setup);
    await page.waitForTimeout(200);
    await page.screenshot({ path: path.join(OUT, `${s.tag}-${name}.png`) });
    await page.close();
  }
}
// Paint, drawn with real drags. Three strokes in three inks on paper.
for (const s of SIZES) {
  const page = await b.newPage({ viewport: { width: s.w / 3, height: s.h / 3 }, deviceScaleFactor: 3 });
  await page.goto(URL);
  await page.waitForTimeout(400);
  await page.evaluate(() => {
    for (const sel of ['#controls', '#note', '.under', '.band']) {
      const el = document.querySelector(sel); if (el) el.style.display = 'none';
    }
    openFromMenu('paint'); ink.canvas = 1; ball.sizeIndex = 3;
  });
  const strokes = [
    { fam: 2, pts: [[0.18,0.24],[0.42,0.36],[0.3,0.52],[0.62,0.6],[0.8,0.42]] },
    { fam: 6, pts: [[0.82,0.22],[0.6,0.4],[0.72,0.62],[0.4,0.72],[0.2,0.6]] },
    { fam: 4, pts: [[0.25,0.8],[0.5,0.66],[0.75,0.8],[0.55,0.9],[0.3,0.86]] },
  ];
  const box = await page.evaluate(() => ({ w: W, h: H }));
  for (const st of strokes) {
    await page.evaluate((f) => { ink.family = f; }, st.fam);
    await page.mouse.move(box.w * st.pts[0][0], box.h * st.pts[0][1]);
    await page.mouse.down();
    for (const [px, py] of st.pts.slice(1)) {
      await page.mouse.move(box.w * px, box.h * py, { steps: 26 });
    }
    await page.mouse.up();
    await page.waitForTimeout(260);
  }
  await page.screenshot({ path: path.join(OUT, `${s.tag}-paint.png`) });
  await page.close();
}

await b.close();
console.log('shot');
