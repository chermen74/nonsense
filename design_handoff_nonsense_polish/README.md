# Handoff: Nonsense visual polish pass

> Audio is documented separately in **AUDIO.md** — synth tuning in `NonsenseCore`, no audio files involved.

## Overview
A visual polish pass on the existing **Nonsense** fidget toy app (`chermen74/nonsense`, branch `claude/fidget-github-repo-ue2fno`). Three screens are covered: the front door menu, the toy screen's control dock, and the ink/canvas drawer.

**Scope is visual only.** No screens were added, no flows changed, no labels rewritten. Every control that exists today still exists, in the same place in the information hierarchy. The physics, audio, haptics, and toy behavior are entirely untouched.

## About the design files
`Nonsense Polish.dc.html` and `nonsense-polish-standalone.html` in this bundle are **design references built in HTML** — a prototype of the intended look, not production code to copy. The task is to reproduce these visuals in the app's existing environments:

- **iOS / SwiftUI** — `ios/App/ToyView.swift` (the primary target; the screenshots in `brand/shots/` came from here)
- **Web** — `web/index.src.html` (`web/index.html` is generated; edit the `.src` file)

The web build already defines the correct palette in CSS custom properties. Reuse those tokens rather than introducing new ones.

## Fidelity
**High-fidelity.** Colors, type, spacing, and radii below are final values, taken from the repo's own `web/index.src.html` token block. Match them exactly.

---

## The four problems this pass fixes

1. **Screen tint was hiding the palette.** The app ships defaulting to a slate screen tint that turns the warm paper ground (`#e8e4dc`) into flat grey. The palette is good; it was being covered up. Default the tint lower, or drop the grey cast so the paper reads warm.
2. **Every control had the same weight.** Tool selection, tool options, and the menu were all the same-size chip in the same grey. Nothing told the eye what mattered.
3. **The chip grid crowded the canvas.** Three rows of chips plus a swatch strip consumed the bottom third of the play area, and each row read as an unrelated group.
4. **Targets were too small.** Chips were roughly 26–28pt tall and swatches ~16pt wide — below the 44pt minimum, and the swatch strip was effectively un-hittable one-handed.

---

## Design tokens

All values below already exist in `web/index.src.html`. Do not invent new colors.

| Token | Hex | Use |
| --- | --- | --- |
| `--ground` | `#e8e4dc` | Paper ground (top of canvas gradient), chip fill |
| `--ground-2` | `#ded9cf` | Bottom of canvas gradient, chip hover |
| `--ground-3` | `#d1cbbe` | Under-shapes |
| `--ground-4` | `#c6bfb1` | Under-shapes, blade shape fill |
| `--graphite` | `#3a3a3c` | Selected chip fill, headings, menu button |
| `--oxblood` | `#702929` | Accent: numerals, rules, focus ring. Sparing. |
| `--ink` | `#4a4742` | Body text on paper |
| `--ink-soft` | `#6d685f` | Secondary text, section labels |
| Panel ground | `#f0eee9` | Dock and drawer surface (one step lighter than `--ground`) |
| Hairline | `rgba(58,58,60,.13)` | List dividers |
| Panel border | `rgba(58,58,60,.14)` | Dock / drawer / chip borders |

**Typography** — both faces are already in use in the repo.

- **IBM Plex Mono** — all UI chrome: wordmark, tool names, chip labels, section labels, numerals.
- **IBM Plex Sans, italic** — descriptive taglines only ("throw it and let it ring"). Italic sans against mono is what makes the descriptions read as annotation rather than as another control.

| Role | Size / weight / tracking |
| --- | --- |
| Wordmark (front door) | Mono 30px / 400 / letter-spacing .34em (add left padding of 1em×.34 to re-center) |
| Wordmark (in-toy watermark) | Mono 9px / 400 / .3em / `rgba(58,58,60,.22)` |
| Tagline | Sans italic 13px / 400 |
| Door item name | Mono 15px / 500 / .09em / `--graphite` |
| Door item description | Mono→Sans italic 12.5px / 400 / `--ink-soft` |
| Door numeral | Mono 12px / 400 / `--oxblood` |
| Tool tile numeral | Mono 9px / 400 / 62% opacity |
| Tool tile name | Mono 10px / 500 / .05em |
| Chip label | Mono 10.5px / 400 / .06em |
| Drawer section label | Mono 9.5px / 500 / .18em / `--ink-soft` |

**Radii:** 3px chips and tool tiles · 6px dock panel · 10px drawer top corners · 2px swatch cells. (The repo currently uses 2px on chips; 3px reads slightly less brittle at the larger size, but keep 2px if you prefer the existing feel — be consistent either way.)

**Shadows:** dock `0 10px 28px rgba(58,58,60,.13)` · drawer `0 -14px 40px rgba(58,58,60,.16)`. No other shadows anywhere.

**Spacing scale:** 4 / 5 / 8 / 11 / 14 / 18 / 22 / 30 / 44.

---

## Screen 1 — Front door

**Purpose:** pick a toy. Seven entries, unchanged.

**Layout:** full-bleed vertical gradient `#e8e4dc` → `#ded9cf`, top to bottom. Padding 78 top / 30 sides / 40 bottom (top padding clears the status bar and dynamic island).

**Masthead**, centered, 44px of space below it:
- Wordmark `NONSENSE` — mono 30px, tracking .34em, `--graphite`
- A 34×2px `--oxblood` rule, 20px below the wordmark, 16px above the tagline
- Tagline `something to do with your hands` — Sans italic 13px, `--ink-soft`

**Menu list.** This is the biggest change on this screen: the seven grey rounded cards become a **hairline-ruled list**. Cards gave seven items equal, heavy visual weight and ate 8px of gutter on each side; rules let the type carry the hierarchy.

Each row: a 3-column grid, `26px | 1fr | 26px`, gap 14, min-height **60px**, `border-top: 1px solid rgba(58,58,60,.13)`, transparent background. A closing rule sits under the last row.

- Column 1: item numeral, mono 12px, `--oxblood`
- Column 2: name (mono 15px/500/.09em, `--graphite`) with description 3px below (Sans italic 12.5px, `--ink-soft`)
- Column 3: 18×18 line glyph, 1.4px stroke, `#8a8378`, round joins/caps

Row press/hover state: background `rgba(255,255,255,.34)`. No border change, no scale.

**Content, verbatim — do not rewrite:**

| # | Name | Description |
| --- | --- | --- |
| 1 | BALL | throw it and let it ring |
| 2 | DIAL | a knurled wheel that clicks |
| 3 | BUMPERS | a table to bounce through |
| 4 | LIGHTNING | a strike that stays etched |
| 5 | GLASS | press it and it breaks |
| 6 | PAINT | a ball that leaves ink |
| 7 | INK & CANVAS | colour, sheerness, ground |

Glyphs are simple geometry matching each toy: circle, spoked wheel, hexagon, bolt, cracked square, two bars, square outline. Placeholders in the prototype — substitute the app's real glyphs if better ones exist.

---

## Screen 2 — The toy

**Purpose:** play. The canvas must dominate; chrome must recede.

**Canvas:** same paper gradient, full bleed. Wordmark watermark centered at y≈60, mono 9px, tracking .3em, `rgba(58,58,60,.22)`.

### The dock — the main change

The three-row chip grid becomes **one floating panel** holding three tiers, so the eye reads a single object with internal structure instead of three loose rows.

Panel: inset 14px left and right, 44px from the bottom (clears the home indicator). Background `rgba(240,238,233,.94)` with `backdrop-filter: blur(14px)` — on SwiftUI use `.ultraThinMaterial` tinted toward `#f0eee9`. Border `1px solid rgba(58,58,60,.14)`, radius 6, shadow `0 10px 28px rgba(58,58,60,.13)`. Padding 12 / 12 / 10. Internal gap 11.

**Tier 1 — tool selector (primary).** Six equal columns, gap 5, each tile min-height **52px**, radius 3, centered. Numeral above name with 5px between them.
- Unselected: `#e8e4dc` fill, `1px solid rgba(58,58,60,.14)`, text `--ink`
- Selected: `#3a3a3c` fill and border, text `#e8e4dc`
- Hover: border `rgba(58,58,60,.34)`

Tools, in order: `1 BALL · 2 DIAL · 3 BUMPERS · 4 BOLT · 5 GLASS · 6 PAINT`.

**Tier 2 — contextual options (secondary).** A row, min-height 44px, gap 6. Each chip flex:1, min-height 38px, `#e8e4dc` fill, `1px solid rgba(58,58,60,.16)`, radius 3, mono 10.5px, `--ink`, `white-space: nowrap`. Hover: fill `#ded9cf`, text `--graphite`.

The set swaps with the selected tool — this is what removes the third row. Options for tools you aren't holding are no longer on screen.

| Tool | Options |
| --- | --- |
| BALL | SHAPE · CIRCLE, SIZE −, SIZE +, PALETTE |
| DIAL | SIZE −, SIZE +, PALETTE |
| BUMPERS | EDIT, CLEAR, PALETTE |
| BOLT | CLEAR, PALETTE |
| GLASS | CLEAR, PALETTE |
| PAINT | PAINT HERE, SIZE −, SIZE +, PALETTE |

**Menu (tertiary).** Pinned to the right end of tier 2, fixed 44×38, `#3a3a3c` fill, radius 3, glyph `≡` in `#e8e4dc` at 15px. Hover `#4a4742`. Demoted out of the chip flow — it is not a tool and shouldn't compete with them.

**Tier 3 — ink strip.** Separated by `border-top: 1px solid rgba(58,58,60,.1)` with 10px above the swatches. Twelve flush swatches, each flex:1, height **34px** (was ~16), no border, no gap — a continuous ribbon. Selected swatch gets a double inset ring: `inset 0 0 0 2px #e8e4dc, inset 0 0 0 3.5px #3a3a3c`, which reads on light and dark inks alike.

Strip inks: `#8a8378` graphite · `#e8e4dc` chalk · `#702929` oxblood · `#b4794a` ochre · `#c9a227` brass · `#7d8c5c` sage · `#4f6b7a` slate · `#3f4d7a` indigo · `#7a4f6b` plum · `#b06a6a` rose · `#5c6b5c` moss · `#3a3a3c` ink.

Selecting an ink retints live toy elements over **350ms ease** (color/background only — never animate position or size of anything the physics owns).

---

## Screen 3 — The drawer

**Purpose:** ink, translucency, canvas, tint, sound. Same five groups as today, rebuilt as a proper sheet.

**Sheet:** bottom-anchored, full width, background `#f0eee9`, `border-top: 1px solid rgba(58,58,60,.16)`, radius 10 on top corners only, shadow `0 -14px 40px rgba(58,58,60,.16)`, padding 14 / 20 / 40. Presents with a 24px rise and fade over 300ms ease. A 38×4px grab handle, `rgba(58,58,60,.2)`, radius 2, centered, 16px below the top edge.

**Ink header:** a baseline-aligned row, 12px below the handle — `INK` label left (mono 10px/500/.18em, `--ink-soft`), current ink name and a 13×13 radius-2 chip of that color right (mono 12px, `--graphite`), 8px apart.

**Swatch grid:** 10 columns × 4 rows, gap 4, each cell 1:1 aspect, radius 2, `1px solid rgba(58,58,60,.12)`. Selected cell: `box-shadow: 0 0 0 2px #3a3a3c`. 22px of space below the grid.

Grid, row by row (10 per row) — neutrals, then oxblood, ochre, sage/slate, indigo, plum families:

```
#9b958a #e8e4dc #cfc7b8 #b4a998 #8a8378 #6d685f #4a4742 #3a3a3c #2a2a2c #1a1a1c
#f0d8d8 #d9a6a6 #b06a6a #702929 #4d1c1c #f2e0c8 #e0bd8e #b4794a #7d5230 #4d3320
#e6e8d4 #c6cca6 #9aa878 #7d8c5c #4f5c3a #d8e2e6 #a8bcc6 #7295a4 #4f6b7a #334a56
#dcdcea #b0b2cf #7a7da8 #3f4d7a #28304d #ecdae6 #cfa8c2 #a3739a #7a4f6b #4d3143
```

If the app already ships a specific palette, keep it — the grid geometry and selection treatment are the point, not these exact 40 values.

**Setting sections.** Four groups, 18px apart, each with `border-top: 1px solid rgba(58,58,60,.11)` and 14px padding above its label. Label (mono 9.5px/500/.18em, `--ink-soft`), then a row of chips 8px below, gap 5.

Chips: flex:1, min-height **38px**, radius 3, mono 10.5px/.05em. Unselected `#e8e4dc` + `1px solid rgba(58,58,60,.16)` + `--ink`. Selected `#3a3a3c` fill and border, `#e8e4dc` text. Hover: border `rgba(58,58,60,.34)`.

| Section | Values | Prototype default |
| --- | --- | --- |
| TRANSLUCENCY | 15% · 30% · 50% · 75% · 100% | 100% |
| CANVAS | sheer · paper · linen · sage · slate · ink | slate |
| SCREEN TINT | 0% · 6% · 12% · 18% · 25% · 34% | 12% |
| SOUND | off · organ · keys · drum · bell | bell |

(The repo also lists `black` under canvas and `pluck` under sound. Keep whatever the app ships — the row wraps or scrolls; don't shrink chips below 38pt to fit.)

**On screen tint:** the prototype keeps 12% as the current selection to match the screenshots, but consider shipping a lower default. The tint is what made the app read grey and generic; the palette only shows at low values.

---

## Interactions & behavior

- **Front door row tap** → opens that toy, unchanged. `INK & CANVAS` opens the drawer.
- **Tool tile tap** → sets the active tool; tier 2 swaps its option set. Instant, no transition on the swap.
- **Ink tap (strip or grid)** → sets ink; live elements retint over 350ms ease.
- **Menu tap** → presents the drawer sheet (300ms rise + fade).
- **Setting chip tap** → sets that value immediately; no confirm, no apply button.
- Keep existing haptics and audio on every one of these. `ios/App/Haptics.swift` and `Speaker.swift` need no changes.
- **Focus ring** (web, keyboard): `2px solid #702929`, offset 2px — already the repo's pattern.
- **Reduced motion:** drop the sheet rise and the ink transition; keep the state changes.

## State
No new state. Existing values are enough: `tool`, `ink`, `translucency`, `canvas`, `tint`, `sound`, `drawerOpen`. The one addition is derived, not stored: the tier-2 option set is a pure function of `tool`.

## Safe-area notes
The dock sits 44px above the bottom edge to clear the home indicator; the drawer carries 40px of bottom padding for the same reason. Front-door top padding of 78px clears the status bar and dynamic island. Use real safe-area insets rather than these constants where the platform provides them.

## Assets
No new assets. Front-door glyphs are line-drawn placeholders (18×18, 1.4px stroke) — replace with the app's real glyphs if they exist. Brand assets already in the repo (`brand/icon-*.png`, `brand/shots/*`) are unchanged.

## Files in this bundle
- `Nonsense Polish.dc.html` — the design source
- `nonsense-polish-standalone.html` — same design, self-contained, opens offline in any browser
- `ios-frame.jsx` — iPhone bezel used for presentation only. Not part of the design; do not port.
- `shots/before-*.png` — the app as it looks today, for comparison
- `shots/after-all-three.png` — the polished design: front door, toy dock, drawer, at 2x

## Files to change in the repo
| Target | What |
| --- | --- |
| `ios/App/ToyView.swift` | Front door list, dock, drawer — all three screens live here |
| `web/index.src.html` | Same three treatments; palette tokens already correct at lines 21–28 |
| `web/index.html` | Generated — rebuild, don't hand-edit |

Leave `ios/App/Store.swift`, `Haptics.swift`, `Speaker.swift`, and `ios/Sources/NonsenseCore/` alone. This pass touches presentation only.
