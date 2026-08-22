# Store screenshots

Rendered from the web build at each store's required size, with the page's own
browser chrome hidden — so they are the app, not a screenshot of a web page.

| | |
|---|---|
| `play-*.png` | 1080×1920, Google Play's phone slot |
| `ios67-*.png` | 1290×2796, Apple's 6.7" slot (everything else derives from it) |

Regenerate them with `node tools/store-shots.mjs` after any visual change.

Play takes at least two; Apple takes up to ten. The order that tells the story
best is **lightning, bumpers, paint, dial, title** — lead with lightning,
because it is the one that reads at thumbnail size.
