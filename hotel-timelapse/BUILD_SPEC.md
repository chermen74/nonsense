# BUILD_SPEC — hotel-timelapse

## What this is

A single-page, static web viewer that plays a hotel property's daily operating
data as a **timelapse**: press play, watch a month advance one day at a time,
and see occupancy, ADR and RevPAR move together.

The point is not another dashboard. A dashboard shows you a month as a shape.
A timelapse shows you a month as a *sequence* — the Tuesday hole, the Friday
compression, the week a festival ate the whole house — in the order it actually
happened to the people who worked it.

## Scope of v1

In:

- Load one JSON document (`public/property.demo.json` by default) and render it.
- A transport: play, pause, scrub, step one day, speed control.
- A primary chart that fills in day by day as the timelapse advances.
- A readout of the current day's numbers, updating in step.
- Event annotations surfaced when the playhead reaches them.
- A hover layer over the chart, and a data table for everything the chart implies.
- Works offline, from `file://` or any static host, on desktop and phone.

Out (v1 explicitly does not do):

- Any live PMS / RMS / STR integration. Data arrives as a JSON file, full stop.
- Any server, database, login, or build step.
- Multi-property comparison, forecasting, budget variance.
- Photo or camera timelapse (a separate idea, considered later).

## Data contract

The viewer consumes one document matching `schema: "hotel-timelapse/property@1"`.
`public/property.demo.json` is the reference instance; `prep/gen_synthetic_month.py`
generates it. Treat the generator's output as the contract — if the two disagree,
the generator is right.

```jsonc
{
  "schema": "hotel-timelapse/property@1",
  "synthetic": true,                    // true = fabricated demo data
  "property": { "id", "name", "locale", "timezone", "rooms", "currency" },
  "period":   { "start": "YYYY-MM-DD", "end": "YYYY-MM-DD", "grain": "day" },
  "metrics":  [ { "key", "label", "unit", "precision" } ],   // display hints
  "summary":  { "rooms_sold", "rooms_available", "occupancy", "adr", "revpar", "room_revenue" },
  "annotations": [ { "date", "label", "kind" } ],
  "days": [
    {
      "date": "2026-06-01",
      "dow": "Mon",
      "rooms_available": 240,
      "rooms_sold": 168,
      "occupancy": 0.7,                 // fraction, not percent
      "adr": 261.12,
      "revpar": 182.78,
      "room_revenue": 43868.16,
      "segments": { "transient": 0, "group": 0, "contract": 0 }
    }
  ]
}
```

Invariants the viewer may rely on (the generator asserts all four):

- `occupancy == rooms_sold / rooms_available`
- `room_revenue == rooms_sold * adr`
- `revpar == room_revenue / rooms_available`
- `sum(segments.values()) == rooms_sold`

`days` is ordered ascending by date with no gaps. Units: occupancy is a
fraction in `[0, 1]`; money is in `property.currency` major units.

The generator also writes a sidecar next to the JSON — `property.demo.js`, one
assignment to `window.HOTEL_TIMELAPSE_DATA` wrapping the same document. A page
opened from `file://` is not allowed to `fetch` a sibling file, and
double-clicking `public/index.html` has to work. Both files come out of the same
run, so they cannot drift; the JSON remains the contract.

## Behaviour

**Transport.** Play advances one day per tick. Default 400 ms/day — a 30-day
month runs in about twelve seconds, slow enough to read, short enough to
rewatch. Speeds: 0.5× / 1× / 2× / 4×. Space toggles play/pause, `←`/`→` step a
day, `Home`/`End` jump to the ends. Playback stops at the last day; it does not
loop by default.

**Chart.** The full period's x-axis is laid out up front and does not rescale
during playback — the empty right-hand side is the point, it's what makes it
feel like time passing. The y-axis is **zero-baselined**, topped at the
period's own maximum rounded up to a clean tick, and likewise never rescales:
the area wash under the line reads as magnitude, and a wash rising from a
truncated baseline overstates the swing. Friday and Saturday columns carry a
faint band — the shape every hotel month is built around. Days already played
are drawn solid; the current day gets an end-dot and a playhead line. Primary
series is selectable (occupancy / ADR / RevPAR); one series at a time in v1, so
no legend box — the chart title names the series, and each switch button
carries its name as text beside its swatch.

**Readout.** Current date and day-of-week, the selected metric large, the other
metrics smaller alongside, plus running month-to-date occupancy, ADR and RevPAR.
Numbers change on the day boundary and do not animate between values — an
interpolated ADR is a number that never happened.

**Hover.** Hovering the chart raises a tooltip for the nearest day already
played, carrying all three metrics plus rooms sold, and rings that day's point.
Days not yet reached do not respond — there is nothing there yet.

**Table view.** A toggle below the transport reveals a table of every day played
so far, current row in bold. It is the non-visual path to the same numbers, and
the relief for the one palette slot (aqua/RevPAR) that sits below 3:1 on the
light surface.

**Annotations.** When the playhead reaches an annotated date, its label appears
against that day and stays visible for the rest of the run. Annotations are
marked on the axis from the start, unlabelled, so a viewer can see something is
coming.

**Empty and error states.** Fetch failure, malformed JSON, wrong `schema`,
violated invariant, or zero days: say plainly what's wrong and which file, on
the page. Never render a half-broken chart.

**Synthetic badge.** When `synthetic` is `true`, the page shows a persistent
"Demo data" marker. This is not decoration. Anything that looks like a real
property's results and isn't must say so on its face.

## Constraints

- **No build step.** Plain HTML, CSS and one classic script served from
  `public/`. Open `public/index.html` in a browser and it works. Not ES modules:
  module scripts are blocked under `file://`, which would break exactly the case
  this constraint exists to protect.
- **No runtime dependencies, no CDN.** No charting library — the chart is a few
  dozen lines of SVG or canvas. Nothing to audit, nothing to break offline.
- **`prep/` is Python 3.11+, standard library only.** No `pip install`.
- Accessible: keyboard-operable transport, real focus states, text contrast at
  4.5:1 or better, respects `prefers-reduced-motion` (skip the playhead easing,
  keep the stepping), readable in light and dark.
- Handles a period of 1 to 400 days without changes.

## Repository layout

```
CLAUDE.md          conventions for agents working in this repo
BUILD_SPEC.md      this file — what to build
SPEND_SPEC.md      what it may cost to build and run
KICKOFF_PROMPT.md  the prompt that starts the build
prep/              data preparation, Python, stdlib only
public/
  index.html           structure
  styles.css           tokens and layout; light and dark
  app.js               the whole viewer, one classic script
  property.demo.json   generated, the data contract
  property.demo.js     generated, offline copy for file://
```

`public/` is the deploy root. Nothing outside it is required at runtime.

## Done means

1. `python3 prep/gen_synthetic_month.py` regenerates `public/property.demo.json`
   and its sidecar byte-identically for the default seed.
2. Opening `public/index.html` directly from disk plays the demo month.
3. Transport, series switch, annotations and keyboard control all work.
4. A deliberately corrupted JSON, and a missing one, each produce a clear
   on-page error naming the failure and the file, not a blank page.
5. No network requests leave the page. No dependencies were installed.
