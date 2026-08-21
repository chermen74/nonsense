// The published page and the repo cannot be allowed to drift, so the page is
// generated from desktop/renderer.html rather than kept as a second copy of
// it. renderer.html is the reference implementation; index.src.html is the
// page around it — the DOM chips that stand in for a keyboard, and the soft
// blocks that stand in for the desktop a transparent window would show.
//
//   node web/build.mjs [extra output path]
//
// Run from the repo root.
import fs from 'fs';
import path from 'path';

const root = path.resolve(path.dirname(new URL(import.meta.url).pathname), '..');
const src = fs.readFileSync(path.join(root, 'desktop/renderer.html'), 'utf8');
const m = src.match(/<script>\n([\s\S]*)\n<\/script>/);
if (!m) { console.error('could not find the app script in renderer.html'); process.exit(1); }
const app = m[1];

const harness = fs.readFileSync(path.join(root, 'web/index.src.html'), 'utf8');
if (!harness.includes('/* __APP__ */')) { console.error('no /* __APP__ */ in index.src.html'); process.exit(1); }
const page = harness.replace('/* __APP__ */', app);

const outputs = [path.join(root, 'web/index.html'), ...process.argv.slice(2)];
for (const out of outputs) {
  fs.writeFileSync(out, page);
  console.log('wrote', out, '(' + page.length + ' bytes,', app.split('\n').length, 'app lines)');
}
