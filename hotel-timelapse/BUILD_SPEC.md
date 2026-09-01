# Hotel Timelapse — Build Spec

A browser-based 3D replay of one month of hotel operations. Rooms light up and dim, guests visibly move through the building, restaurants and function rooms fill and empty, and department revenue tallies accumulate in sync with a scrubbable, variable-speed clock.

**This is a product, not a one-property tool.** It is owned and developed independently and must deploy to any hotel unchanged. Property specifics live only in configuration and data files, never in code.

Repo: personal GitHub (`<your-handle>/hotel-timelapse`), MIT-or-proprietary license decided before first push. Built on personal time and equipment. **No property data of any kind is committed** — the repo ships only the synthetic demo property. Real-property JSON is generated locally by the operator and stays outside the repo (`.gitignore` on `public/data/*` except the synthetic demo month).

## 0. Deployability contract

| Concern | Rule |
|---|---|
| Property identity | `public/property.json`: name, brand colours, logo path, timezone, currency, room count. Loaded at runtime. |
| Geometry | `public/layout.json` (§2). The renderer draws whatever wings/outlets/function rooms it is given — no hard-coded shapes. |
| Departments | `layout.json → departments[]` (addendum §10). USALI names are defaults; any ID/label set works. |
| Data | `public/data/YYYY-MM.json` in the §3 schema. The app never talks to a PMS/POS/payroll system directly. |
| Source systems | `prep/adapters/<system>.py` — each adapter turns one system's export into schema records. First adapters: Opera Cloud, Toast, Delphi, Paycom, Data Plus. Adding a property on Mews/Micros/ADP means adding an adapter, not touching the app. |
| Tie-out | `prep/reconcile.py` compares file totals to any GL export (CSV) — not to one FP&A vendor. |
| Hosting | Static build (`vite build`) — deploys to any static host, an intranet folder, or an `<iframe>` in an existing portal. No server required. Optional Docker image for the prep scripts. |
| Auth/data privacy | Data files are the operator's; the app runs entirely client-side and makes no outbound calls. Say so in the README. |

---

## 1. Stack

| Layer | Choice |
|---|---|
| App | Vite + React 18 |
| 3D | `three` + `@react-three/fiber` + `@react-three/drei` (OrbitControls, Instances, Html, Text) |
| State | `zustand` (sim clock, playback, tallies) |
| Data | Static JSON per month at `public/data/YYYY-MM.json` (schema in §3) |
| Prep | Python scripts in `prep/` — synthetic generator first (`gen_synthetic_month.py`), real extractors later (§7) |

Performance targets: 60 fps on a mid-range laptop at 10,000 stays + 30,000 checks. Use `InstancedMesh` for rooms and guests; never one React component per guest.

---

## 2. Coordinate system & hotel layout

`public/layout.json` defines geometry. Ship the demo layout; each deployment supplies its own.

```json
{
  "units": "meters",
  "entrance": {"x": 0, "y": 0, "z": 40},
  "front_desk": {"x": 0, "y": 0, "z": 30},
  "lobby_hub": {"x": 0, "y": 0, "z": 20},
  "elevators": [{"id": "E1", "x": -10, "y": 0, "z": 15}, {"id": "E2", "x": 10, "y": 0, "z": 15}],
  "wings": [
    {"id": "A", "origin": {"x": -40, "z": 0}, "dir": {"x": 1, "z": 0},
     "floors": 4, "rooms_per_floor": 32, "floor_height": 3.2, "room_pitch": 4.0,
     "room_numbers": {"floor_1": "1101-1132", "floor_2": "1201-1232", "floor_3": "1301-1332", "floor_4": "1401-1432"}}
  ],
  "outlets": [
    {"id": "GRILL", "name": "Harbor Grill", "x": -18, "y": 0, "z": 28, "w": 14, "d": 10, "seats": 120},
    {"id": "TAPROOM", "name": "Tap Room", "x": 22, "y": 0, "z": 34, "w": 16, "d": 12, "seats": 160}
  ],
  "function_rooms": [
    {"id": "BALLROOM", "name": "Ballroom", "x": 0, "y": 0, "z": -20, "w": 30, "d": 20, "capacity": 600},
    {"id": "MTG1", "name": "Meeting Room 1", "x": -25, "y": 0, "z": -10, "w": 10, "d": 8, "capacity": 60}
  ]
}
```

Room position = wing origin + (index × pitch) along `dir`, elevated by `floor × floor_height`. A corridor runs the length of each wing at floor height; guests walk corridor → room, never through walls. Path graph: `entrance → front_desk → lobby_hub → elevator → corridor(floor) → room`. Outlets and function rooms attach to `lobby_hub`.

Rendering fidelity: flat-shaded boxes. Rooms are 3.5 m × 3.5 m × 2.8 m boxes with a window face toward the exterior. Lit room = emissive warm colour on the window face; dark room = low-alpha grey. No textures needed.

---

## 3. Data schema — `public/data/YYYY-MM.json`

All timestamps ISO 8601 with offset (`2026-08-14T15:32:00-07:00`). Local hotel time throughout.

```jsonc
{
  "meta": {
    "month": "2026-08",
    "tz": "America/Los_Angeles",
    "period_start": "2026-08-01T00:00:00-07:00",
    "period_end":   "2026-09-01T00:00:00-07:00",
    "source": "synthetic" | "opera+toast+delphi",
    "generated_at": "..."
  },

  "stays": [
    {
      "id": "S000123",
      "room": "1214",
      "arrive": "2026-08-14T15:32:00-07:00",   // actual check-in timestamp
      "depart": "2026-08-16T10:48:00-07:00",   // actual check-out timestamp
      "guests": 2,
      "market": "TRANSIENT" | "GROUP" | "CONTRACT",
      "nights": [                              // one entry per occupied night
        {"date": "2026-08-14", "rate": 389.00},
        {"date": "2026-08-15", "rate": 429.00}
      ]
    }
  ],

  "checks": [
    {
      "id": "C0004521",
      "outlet": "GRILL",
      "opened": "2026-08-14T18:05:00-07:00",
      "closed": "2026-08-14T19:41:00-07:00",
      "covers": 3,
      "food": 142.50,
      "bev": 58.00,
      "room": "1214" | null                    // room-charge checks link to a stay; null = walk-in
    }
  ],

  "events": [
    {
      "id": "E0031",
      "function_room": "BALLROOM",
      "name": "Wedding — Alvarez",
      "start": "2026-08-15T17:00:00-07:00",
      "end":   "2026-08-15T23:00:00-07:00",
      "attendees": 180,
      "food": 21600.00,
      "bev": 7200.00,
      "room_rental": 2500.00,
      "av": 1800.00
    }
  ]
}
```

Departments for tallies: **Rooms**, **Food**, **Beverage**, **Banquet (Food/Bev/Rental/AV as sub-lines)**, **Total**. Tally panel shows MTD value and, optionally, a sparkline of daily pace.

---

## 4. Revenue accrual rules (locked)

These define *when* each dollar appears on the tally.

| Source | Rule |
|---|---|
| Rooms | Each night's `rate` accrues **linearly from 15:00 to 23:00 on that night's `date`**. Before 15:00: 0. After 23:00: full rate. Multi-night stays repeat this every occupied night. |
| Food / Bev (outlets) | Full `food` + `bev` land at `closed`. |
| Banquet | Each revenue line accrues linearly from `start` to `end`. |

Implement as a pure function `accruedThrough(t) → {rooms, food, bev, bqt_food, bqt_bev, bqt_rental, bqt_av}`. Precompute a sorted "revenue keyframe" array at load (one keyframe per accrual start/end) so the tally at time *t* is an O(log n) lookup plus linear interpolation — no per-frame scans of 40k records.

Sanity check at load: `accruedThrough(period_end)` must equal the summed totals of the file. Log both.

---

## 5. Simulation clock & playback

- Sim time `t` advances at `speed` × real time. Speed presets: **1× · 10× · 60× · 600× · 3600×** (1 sim hour per real second) plus a free slider from 1× to 10,000×.
- Transport: play/pause, scrubber across the month (drag anywhere), step ±1 hour / ±1 day, jump to date.
- Clock readout: `Fri Aug 14, 2026 · 6:42 PM` large; day-of-month progress bar under it.
- Everything derives from `t`. No stateful "on tick" mutations — room state, guest positions, occupancy and tallies are all functions of `t`, so scrubbing backwards is free.

---

## 6. Movement (the core requirement)

Guests are instanced capsule meshes (0.4 m radius, 1.7 m tall). Colour by intent: arriving = teal, departing = amber, dining = coral, banquet = violet.

Each stay/check/event produces **movement segments** — `{who, path: [points], t0, t1, color}` — computed once at load. Position at time `t` = interpolate along `path` by `(t − t0)/(t1 − t0)`. Guests outside any segment are not drawn.

### 6.1 Arrival
1. `arrive − 4 min`: spawn at `entrance`, walk to `front_desk` (real-world walk speed 1.3 m/s).
2. Dwell at desk 3 min.
3. Walk `front_desk → lobby_hub → nearest elevator`, dwell 40 s, then appear at `corridor(floor)` and walk to room.
4. On reaching the room door: room window switches to **lit**. Guest despawns.
`guests` count in the stay = number of capsules that walk together (offset ±0.5 m).

### 6.2 Departure
Reverse of arrival, starting at `depart − 6 min`. Room switches to **dark** when the guest leaves the door. Desk dwell 90 s.

### 6.3 Room lighting between arrival and departure
Lit continuously from check-in to check-out, with a dim/"sleep" state 00:00–06:30 (window at 25% emissive) so night-time still reads as occupied. Optional: brief flicker to dark for a randomised 2–5 h "out of the room" window during daytime to add life — off by default.

### 6.4 Dining
- Check with `room` set: guest leaves that room at `opened − 8 min`, walks to the outlet, sits (capsule at a random seat position inside the outlet footprint) until `closed`, walks back.
- Check with `room = null`: spawn at `entrance` at `opened − 4 min`, walk to outlet, sit, walk back out, despawn.
- Outlet floor tint intensity = current seated covers ÷ `seats`.

### 6.5 Banquets
- Attendees stream in from `entrance` (70%) and from rooms (30%, pick occupied rooms at random) during the 25 min before `start`; arrivals follow a bell curve peaking 10 min before.
- Function room floor tint intensity = attendees present ÷ `capacity`; a coloured ceiling glow appears while the event is active.
- Stream out over 15 min after `end`, same split.
- Cap rendered attendees at 250 per event; scale the tint as if full count were present.

### 6.6 Readability at high speeds
At 600×, a 4-minute walk takes 0.4 real seconds. To keep movement legible:
- Draw a fading trail (last 1.5 real seconds) behind each capsule as a line strip.
- Above 1,000×, switch to **flow mode**: replace capsules with particles along corridor edges, particle density ∝ segments active on that edge. Rooms still light/dim individually.
- At 1× and 10×, full capsules with a subtle bob animation.

---

## 7. UI layout

```
┌───────────────────────────────────────────────────────────────┐
│  3D viewport (OrbitControls; presets: Aerial · Lobby · Wing A) │
│                                                               │
│                              ┌──────────────────────────────┐ │
│                              │ MTD REVENUE       Aug 14 6:42p│ │
│                              │ Rooms      $1,842,310         │ │
│                              │ Food         412,880          │ │
│                              │ Beverage     168,205          │ │
│                              │ Banquet      296,450          │ │
│                              │ ─────────────────────────     │ │
│                              │ Total      $2,719,845         │ │
│                              │ Occ 87.6% · ADR $421 · In-hs 664│
│                              └──────────────────────────────┘ │
├───────────────────────────────────────────────────────────────┤
│ ⏮ ◀ ▶ ⏭  [====●===========================] 1× 10× 60× 600× 3600×│
└───────────────────────────────────────────────────────────────┘
```

Tallies are currency-formatted, right-aligned, and *tick* (animated number roll) when they change. Hover a room → tooltip with room number, current guest count, nightly rate. Hover an outlet/function room → covers and event name. Click a department line → filter the scene to only that department's movement.

Optional overlay toggles: heat-map of rooms by cumulative revenue this month; daily revenue bar chart along the timeline scrubber.

---

## 8. Data prep — first adapters (phase 2; property-specific mappings stay in per-adapter config, not code)

| Feed | Source | Fields needed | Notes |
|---|---|---|---|
| Stays | Opera Cloud — OHIP reservation search (`/rsv/v1/hotels/{id}/reservations`) or Back Office SFTP `RV_NA` + reservation detail export | room, actual check-in/out timestamps, nightly rate by date, adults+children, market code | Nightly rates: use `roomRates[]` by date, not folio postings. Filter to `CHECKED_OUT` and in-house at period end. |
| Checks | Toast `/orders/v2/ordersBulk` by business date | restaurant GUID → outlet, `openedDate`, `closedDate`, `numberOfGuests`, food/bev split from `selections[].salesCategory`, room-charge tender → room number | Reuse auth from the existing Toast client. Business-date loop over the month. |
| Events | Delphi/Salesforce weekly Scheduled Reports CSV (BEO export) | function room, start/end, guaranteed count, F&B / rental / AV revenue | Match function-room names to `layout.json` IDs via a mapping table. |

Output: one `YYYY-MM.json` per month conforming to §3. Reconcile totals against GL actuals via `prep/reconcile.py` and print variance — the visual should tie to the P&L within rounding.

---

## 9. Build order

1. `prep/gen_synthetic_month.py` → `public/data/2026-08.json` (provided).
2. Static scene from `layout.json`: wings, floors, rooms, outlets, function rooms, orbit camera. Verify room count = 400.
3. Sim clock + transport bar + tally panel driven by `accruedThrough(t)`. Verify end-of-month totals match file sums.
4. Room lighting from stays.
5. Movement segments + instanced capsules for arrivals/departures.
6. Dining and banquet movement, outlet/function-room tints.
7. Trails, flow mode, hover tooltips, department filter.
8. Phase 2 extractors.

Definition of done for phase 1: play August 2026 synthetic data at 600× end-to-end without frame drops; scrub to any day and see the correct rooms lit, correct tallies, and guests in motion.
