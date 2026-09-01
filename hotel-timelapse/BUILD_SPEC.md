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

## Behaviour

**Transport.** Play advances one day per tick. Default 400 ms/day — a 30-day
month runs in about twelve seconds, slow enough to read, short enough to
rewatch. Speeds: 0.5× / 1× / 2× / 4×. Space toggles play/pause, `←`/`→` step a
day, `Home`/`End` jump to the ends. Playback stops at the last day; it does not
loop by default.

**Chart.** The full period's x-axis is laid out up front and does not rescale
during playback — the empty right-hand side is the point, it's what makes it
feel like time passing. The y-axis is fixed to the period's range with headroom,
also without rescaling. Days already played are drawn solid; the current day is
marked. Primary series is selectable (occupancy / ADR / RevPAR); one series at a
time in v1.

**Readout.** Current date and day-of-week, the selected metric large, the other
metrics smaller alongside, plus running month-to-date occupancy, ADR and RevPAR.
Numbers change on the day boundary and do not animate between values — an
interpolated ADR is a number that never happened.

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

- **No build step.** Plain HTML, CSS and ES modules served from `public/`.
  Open `public/index.html` in a browser and it works.
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
public/            the deployable site; static root
```

`public/` is the deploy root. Nothing outside it is required at runtime.

## Done means

1. `python3 prep/gen_synthetic_month.py` regenerates `public/property.demo.json`
   byte-identically for the default seed.
2. Opening `public/index.html` directly from disk plays the demo month.
3. Transport, series switch, annotations and keyboard control all work.
4. A deliberately corrupted JSON produces a clear on-page error, not a blank page.
5. No network requests leave the page. No dependencies were installed.
