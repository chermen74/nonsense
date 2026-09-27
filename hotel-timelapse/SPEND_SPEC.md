# Hotel Timelapse — Spend & Profit Addendum (§10–§14)

Extends BUILD_SPEC.md. Same clock, same scene. Adds the cost side so the month plays as revenue in, labor and expenses out, profit at the end — at the hotel level and zoomed into any department.

---

## 10. Department model

USALI structure by default; department IDs, labels and types are read from `layout.json → departments[]`, so any property's chart works.

| Dept ID | Name | Type | Scene anchor |
|---|---|---|---|
| ROOMS | Rooms | Operated | wings + front desk |
| GRILL | Harbor Grill | Operated (F&B outlet) | outlet zone |
| TAPROOM | Tap Room | Operated (F&B outlet) | outlet zone |
| BQT | Banquets | Operated (F&B) | function rooms |
| KITCHEN | Kitchen (shared F&B labor) | Support → allocated to F&B | back-of-house box behind outlets |
| HSKP | Housekeeping | Support → Rooms | BOH box under wing A |
| ENG | Property Ops & Maintenance | Undistributed | BOH box (loading dock side) |
| SM | Sales & Marketing | Undistributed | admin box |
| AG | Administrative & General | Undistributed | admin box |
| UTIL | Utilities | Undistributed | meter on ENG box |

Each department has: **Revenue**, **Cost of Sales**, **Labor** (wages + benefits load), **Other Expense**, **Department Profit**. Undistributed depts have no revenue. Hotel level: Σ Dept Profit − Σ Undistributed = **GOP**. Stop at GOP; fixed charges (management fee, insurance, property tax) are a toggle-able single line below it, accrued linearly across the month.

`layout.json` gains a `departments[]` array with an anchor position and a camera preset for each.

---

## 11. Schema additions — `public/data/YYYY-MM.json`

```jsonc
{
  "meta": {
    "...": "as before",
    "cos_pct": {"GRILL": {"food": 0.31, "bev": 0.23}, "TAPROOM": {"food": 0.33, "bev": 0.26}, "BQT": {"food": 0.28, "bev": 0.20}},
    "benefits_load": 0.28                     // applied on top of wages
  },

  "shifts": [                                 // hourly staff — one record per punch pair
    {"id": "P0045210", "employee": "H0217", "dept": "HSKP", "role": "Room Attendant",
     "in": "2026-08-14T08:02:00-07:00", "out": "2026-08-14T16:31:00-07:00", "rate": 24.50}
  ],

  "salaried": [                               // spread evenly across the month
    {"dept": "AG", "headcount": 6, "monthly": 61500.00},
    {"dept": "ROOMS", "headcount": 3, "monthly": 27800.00}
  ],

  "expenses": [
    {"id": "X0001832", "dept": "ROOMS", "category": "GUEST_SUPPLIES", "vendor": "American Hotel Register",
     "date": "2026-08-11T09:00:00-07:00", "amount": 4218.77, "timing": "invoice"},
    {"id": "X0002001", "dept": "UTIL", "category": "ELECTRIC", "vendor": "PG&E",
     "date": "2026-08-31T23:59:00-07:00", "amount": 96400.00, "timing": "accrual"}
  ],

  "fixed_charges": {"monthly": 310000.00}     // optional; below GOP
}
```

Categories (Other Expense): GUEST_SUPPLIES, CLEANING_SUPPLIES, LINEN, LAUNDRY, COMMISSIONS, OTA_FEES, CHINA_GLASS, MENU_PAPER, KITCHEN_SUPPLIES, R&M, CONTRACT_SERVICES, ELECTRIC, GAS, WATER, MARKETING, IT, CREDIT_CARD_FEES, MISC. Unknown categories fall into MISC — never drop a record.

Cost of sales is **not** a record; it is derived (see §12).

---

## 12. Cost accrual rules (locked)

| Cost | When it appears on the tally | Visual |
|---|---|---|
| Cost of sales (F&B) | At each check `closed` / linearly across each banquet, as `cos_pct × revenue`. Load-time scaling factor so month-end COS ties to GL actual (purchases ± inventory). | Coral pulse in the outlet turns partially grey as the plate "costs" |
| Hourly labor | Continuously while a shift is open: `rate × (1 + benefits_load)` per hour, from `in` to `out`. | Staff capsules (steel-blue) visible on the floor in their department zone; a small $/hr meter above each zone |
| Salaried labor | Linear from `period_start` to `period_end`. | Constant trickle on the admin/dept meter |
| Expense — `timing: invoice` | Full amount at `date`. | Delivery van arrives at the loading dock, box travels to the dept anchor, amount ticks |
| Expense — `timing: accrual` | Full amount at `date` (typically the last second of the month). | **Month-end wave**: at period_end the remaining accruals land as a visible cascade down the waterfall. Deliberately not hidden — this is how the close actually works. |
| Fixed charges | Linear across the month (toggle). | Below-GOP line only |

`accruedThrough(t)` extends to return, per department: `{revenue, cos, labor, other, profit}` and hotel-level `{dept_profit_total, undistributed_total, gop}`. Same keyframe precompute as §4; labor is piecewise-linear (slope changes at every punch), so punches are keyframes.

Sanity check at load: every line at `period_end` equals the file sum; GOP equals Σ operated profit − Σ undistributed. Print the reconciliation. When real data is loaded, print variance vs. GL actuals by department.

---

## 13. Staff movement

Same instanced-capsule system as guests, different colour and different paths.

- **Clock-in**: spawn at the staff entrance (new `layout.staff_entrance`, back of house), walk to the dept anchor. Housekeeping attendants walk the corridors of the wing they are assigned to; when a departure happens on a floor, the nearest attendant walks to that room, dwells 25 sim-minutes (room tint goes "dirty" amber → clean white), then continues.
- **Kitchen / outlet staff**: stand in the BOH box behind their outlet; cooks step forward on each check open, servers walk to the seated table on check open and close.
- **Banquet staff**: gather in the function room 45 min before `start`, remain through `end` + 30 min.
- **Engineering**: two-person roving path across the property; on each `R&M` invoice a capsule walks to the loading dock.
- **Clock-out**: walk back to the staff entrance, despawn.

At > 1,000× staff switch to flow mode with guests (separate colour channel). Toggle: "Show staff" / "Show guests" / both.

---

## 14. Department zoom & the P&L waterfall

**Global view** (default): tally panel becomes a compact live waterfall — one bar per department showing revenue (up, green) and stacked costs (down: COS grey, labor blue, other expense slate), with a running **GOP** figure and margin % at the bottom that updates every frame.

**Click a department** (or its scene zone): camera flies to its preset, the scene dims everything outside the zone, and the panel expands to that department's animated waterfall:

```
Harbor Grill — Aug 14, 6:42 PM (MTD)
Revenue        ████████████████████  $312,480
 Cost of sales ██████                 (94,120)
 Labor         ████████               (128,300)
 Other expense ███                    (31,760)
Dept profit    ███████                $ 58,300   18.7%
```

Bars extend as the month plays; the profit bar is the visible residual. Two secondary readouts under the waterfall:
- **Labor hours vs. covers (or occupied rooms)** as a dual-line sparkline by day — productivity, which is what actually moves the margin.
- **Daily net** bar strip along the timeline scrubber: green above zero, red below, so the user sees which days were profitable and which weren't (banquet nights vs. Monday breakfasts).

Hover any bar → the top vendors/roles behind it MTD. Hover a day on the strip → that day's mini-P&L.

**Close animation**: at `period_end`, the month-end accruals cascade in (§12), the waterfall settles, and the final GOP locks with a subtle flash. Press play from there and it loops to day 1.

---

## 15. Real data feeds (phase 3)

| Feed | Source | Fields | Notes |
|---|---|---|---|
| Hourly shifts | Paycom punch export (daily CSV) | employee ID, dept code, position, punch in/out, pay rate | Map Paycom dept codes → Dept IDs. Split shifts spanning midnight at 00:00. Overtime: rate × 1.5 after 8 h/day and 40 h/week per CA rules. |
| Salaried | Paycom payroll register | dept, semi-monthly gross | Spread evenly. |
| Benefits load | GL actuals — benefits by dept ÷ wages | | One factor per dept, not global. |
| Expenses (invoice) | Data Plus AP invoice export | vendor, GL account, dept, invoice date, amount | GL → category mapping table. Use invoice date, not post date. |
| Expenses (accrual) | GL journal entries dated last day of month; GL actuals for anything not in AP | | Exclude reclass entries. Anything in GL actual not explained by AP + payroll lands as a `timing: accrual` record so the month ties. |
| COS actual | GL actuals — COS accounts by outlet | | Scale factor per outlet applied to `cos_pct` at load so month-end ties. |

Reconciliation target: every department line ties to the property's GL actuals (any CSV export via `prep/reconcile.py`) within $1.

---

## 16. Build order (continues §9)

9. Extend `gen_synthetic_month.py` with shifts, salaried, expenses (provided).
10. Extend `accruedThrough(t)` with cost lines and per-dept results; pass reconciliation check.
11. Global waterfall panel + GOP readout.
12. Staff capsules, clock-in/out paths, housekeeping room-turn behaviour.
13. Delivery-van / loading-dock expense animation; month-end accrual cascade.
14. Department zoom: camera presets, scene dimming, dept waterfall, productivity sparkline, daily-net strip.
15. Phase 3 adapters and the GL tie-out script.

Definition of done: play August end-to-end, click into any department at any point, and the numbers on screen match `accruedThrough(t)` — and at period_end match the file totals to the cent.
