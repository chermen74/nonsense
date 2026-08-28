// The six pictures at the top of the README, and the tour strip under them.
//
//   node tools/readme-shots.mjs
//
// They were made by hand the first time, which is why they went on showing a
// mode row and a bottom strip for a day after both were replaced — the front
// page of the repository describing an app that no longer existed. A tool
// that can be re-run is the fix: every one of these is the browser build at
// phone size, shot from `web/index.html`, so re-running it after a change is
// how the README stops lying.
//
// Rebuild the page first if the renderer has moved: `node web/build.mjs`.

import { chromium } from "playwright";
import path from "path";
import { fileURLToPath } from "url";

const here = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(here, "..");
const OUT = path.join(ROOT, "brand", "shots");
const PAGE = "file://" + path.join(ROOT, "web", "index.html");

// 430x900 at 2x is the 860x1800 the existing shots were taken at, so the
// README's table keeps its proportions.
const VIEW = { width: 430, height: 900 };
const SCALE = 2;

/** A word spelled out in stroke letters, in a mix of families. */
const WORD = [
  ["T", 0, 1], ["O", 2, 2], ["Y", 1, 2],
];

const SCENES = {
  "1-door": async (pg) => {
    await pg.evaluate(() => { screen = "title"; });
  },
  "2-letters": async (pg) => {
    await pg.evaluate((word) => {
      screen = "play"; mode = "bumpers"; edit.on = false; edit.sel = -1;
      ink.family = 0; ink.tone = 2;
      table = word.map(([glyph, family, tone], i) => sane({
        nx: 0.22 + i * 0.28, ny: 0.42, size: 0.1, shape: "circle", rot: 0,
        family, tone, glyph,
      }));
      ball.x = W * 0.5; ball.y = H * 0.72; ball.vx = 0; ball.vy = 0;
    }, WORD);
  },
  // The same table in a different ink, which is the point of the pair: the
  // bumpers hold what they were given and the ball is what moves.
  "3-palette": async (pg) => {
    await pg.evaluate((word) => {
      screen = "play"; mode = "bumpers"; edit.on = false; edit.sel = -1;
      ink.family = 9; ink.tone = 2;
      table = word.map(([glyph, family, tone], i) => sane({
        nx: 0.22 + i * 0.28, ny: 0.42, size: 0.1, shape: "circle", rot: 0,
        family, tone, glyph,
      }));
      ball.x = W * 0.5; ball.y = H * 0.72; ball.vx = 0; ball.vy = 0;
    }, WORD);
  },
  "4-bolt": async (pg) => {
    await pg.evaluate(async () => {
      screen = "play"; mode = "bolt"; clearEtched();
      const throws = [[2, 0.2, 0.74, 2400, -1500], [6, 0.8, 0.7, -2200, -1600],
                      [4, 0.5, 0.24, 200, 2600], [9, 0.14, 0.36, 2500, 1000]];
      for (const [f, px, py, vx, vy] of throws) {
        ink.family = f;
        fireBolt(W * px, H * py, vx * (W / 390), vy * (H / 844));
        for (let i = 0; i < 90 && bolts.length; i++) {
          await new Promise((r) => requestAnimationFrame(r));
        }
      }
      ink.family = 2;
    });
  },
  "5-glass": async (pg) => {
    await pg.evaluate(() => {
      screen = "play"; mode = "glass"; breaks.length = 0;
      ink.family = 6; ink.tone = 2;
      breakGlass(W * 0.36, H * 0.34);
      ink.family = 2;
      breakGlass(W * 0.62, H * 0.56);
    });
  },
  "6-drawer": async (pg) => {
    await pg.evaluate(() => {
      screen = "play"; mode = "ball"; ink.family = 2; ink.tone = 2;
      drawerOpen = true;
    });
  },
};

async function main() {
  const browser = await chromium.launch({
    executablePath: process.env.CHROME || undefined,
  });
  for (const [name, set] of Object.entries(SCENES)) {
    const pg = await browser.newPage({ viewport: VIEW, deviceScaleFactor: SCALE });
    const errs = [];
    pg.on("pageerror", (e) => errs.push(String(e)));
    await pg.goto(PAGE);
    // The fonts are half of what these are for; a shot taken before they
    // arrive is a shot of a fallback.
    await pg.evaluate(() => document.fonts.ready);
    await pg.evaluate(() => { tier = "full"; });
    await pg.waitForTimeout(500);
    await set(pg);
    await pg.waitForTimeout(500);
    await pg.screenshot({ path: path.join(OUT, `${name}.png`) });
    if (errs.length) {
      console.error(`${name}: ${errs.join(" | ")}`);
      process.exitCode = 1;
    }
    console.log(`${path.relative(ROOT, path.join(OUT, name))}.png`);
    await pg.close();
  }
  await browser.close();
}

await main();
