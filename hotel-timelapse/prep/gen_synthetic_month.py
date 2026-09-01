#!/usr/bin/env python3
"""Generate a synthetic month of hotel operating data for hotel-timelapse.

No real property data ever enters this repo. This script fabricates a
plausible month -- weekday/weekend rhythm, a couple of compression events,
a group block or two -- so the viewer has something honest to animate.

Standard library only. Deterministic for a given --seed.

    python3 prep/gen_synthetic_month.py --month 2026-06 --out public/property.demo.json
"""

from __future__ import annotations

import argparse
import calendar
import json
import math
import random
from datetime import date, timedelta
from pathlib import Path

SCHEMA = "hotel-timelapse/property@1"

# Occupancy multipliers by weekday (Mon=0). A leisure-leaning coastal
# property: soft mid-week, full Friday and Saturday.
DOW_LIFT = [0.82, 0.86, 0.92, 0.97, 1.18, 1.22, 0.95]

# ADR multipliers by weekday. Rate follows demand but with less amplitude.
DOW_RATE = [0.90, 0.91, 0.94, 0.99, 1.14, 1.20, 1.00]

SEGMENTS = ("transient", "group", "contract")


def month_days(year: int, month: int) -> list[date]:
    n = calendar.monthrange(year, month)[1]
    return [date(year, month, d) for d in range(1, n + 1)]


def pick_events(days: list[date], rng: random.Random) -> dict[date, dict]:
    """Two or three compression events scattered through the month."""
    catalog = [
        ("Regional conference", 0.16, 1.22),
        ("Festival weekend", 0.13, 1.30),
        ("Wedding block", 0.09, 1.08),
        ("Holiday travel", 0.11, 1.15),
    ]
    rng.shuffle(catalog)
    events: dict[date, dict] = {}
    count = rng.randint(2, 3)
    # Keep events apart so the timelapse shows distinct peaks, not one plateau.
    candidates = [d for d in days if 3 <= d.day <= len(days) - 3]
    chosen: list[date] = []
    for _ in range(count):
        pool = [d for d in candidates if all(abs((d - c).days) > 5 for c in chosen)]
        if not pool:
            break
        chosen.append(rng.choice(pool))
    for start, (label, occ_lift, rate_lift) in zip(sorted(chosen), catalog):
        span = rng.randint(2, 3)
        for i in range(span):
            day = start + timedelta(days=i)
            if day not in days:
                continue
            # Taper the lift across the tail of the event.
            decay = 1.0 - (i * 0.25)
            events[day] = {
                "label": label,
                "occ_lift": occ_lift * decay,
                "rate_lift": 1.0 + (rate_lift - 1.0) * decay,
                "first_day": i == 0,
            }
    return events


def build_days(
    days: list[date],
    rooms: int,
    base_occ: float,
    base_adr: float,
    events: dict[date, dict],
    rng: random.Random,
) -> list[dict]:
    rows = []
    n = len(days)
    for idx, day in enumerate(days):
        # Gentle ramp across the month plus a slow seasonal wave.
        trend = 1.0 + 0.05 * (idx / max(n - 1, 1))
        wave = 1.0 + 0.03 * math.sin(2 * math.pi * idx / n)
        ev = events.get(day)

        occ = base_occ * DOW_LIFT[day.weekday()] * trend * wave
        adr = base_adr * DOW_RATE[day.weekday()] * trend
        if ev:
            occ += ev["occ_lift"]
            adr *= ev["rate_lift"]

        occ *= rng.uniform(0.96, 1.04)
        adr *= rng.uniform(0.97, 1.03)
        # A house never sells the last few rooms; 98% is a sellout in practice.
        occ = min(max(occ, 0.28), 0.98)

        rooms_sold = int(round(occ * rooms))
        occ = rooms_sold / rooms
        adr = round(adr, 2)
        room_revenue = round(rooms_sold * adr, 2)
        revpar = round(room_revenue / rooms, 2)

        # Split the house. Groups swell on event days, contract stays flat.
        group_share = rng.uniform(0.10, 0.18) + (0.16 if ev else 0.0)
        contract_share = rng.uniform(0.04, 0.08)
        group = int(round(rooms_sold * min(group_share, 0.45)))
        contract = int(round(rooms_sold * contract_share))
        transient = rooms_sold - group - contract

        rows.append(
            {
                "date": day.isoformat(),
                "dow": day.strftime("%a"),
                "rooms_available": rooms,
                "rooms_sold": rooms_sold,
                "occupancy": round(occ, 4),
                "adr": adr,
                "revpar": revpar,
                "room_revenue": room_revenue,
                "segments": {
                    "transient": transient,
                    "group": group,
                    "contract": contract,
                },
            }
        )
    return rows


def build_annotations(events: dict[date, dict]) -> list[dict]:
    return [
        {"date": day.isoformat(), "label": ev["label"], "kind": "event"}
        for day, ev in sorted(events.items())
        if ev["first_day"]
    ]


def summarize(rows: list[dict], rooms: int) -> dict:
    sold = sum(r["rooms_sold"] for r in rows)
    revenue = round(sum(r["room_revenue"] for r in rows), 2)
    available = rooms * len(rows)
    return {
        "rooms_sold": sold,
        "rooms_available": available,
        "occupancy": round(sold / available, 4),
        "adr": round(revenue / sold, 2) if sold else 0.0,
        "revpar": round(revenue / available, 2),
        "room_revenue": revenue,
    }


def build_document(args: argparse.Namespace) -> dict:
    year, month = (int(p) for p in args.month.split("-"))
    days = month_days(year, month)
    rng = random.Random(args.seed)
    events = pick_events(days, rng)
    rows = build_days(days, args.rooms, args.occupancy, args.adr, events, rng)

    return {
        "schema": SCHEMA,
        "synthetic": True,
        "notice": "Fabricated demo data. Not the operating results of any real property.",
        "property": {
            "id": args.property_id,
            "name": args.property_name,
            "locale": args.locale,
            "timezone": args.timezone,
            "rooms": args.rooms,
            "currency": "USD",
        },
        "period": {
            "start": days[0].isoformat(),
            "end": days[-1].isoformat(),
            "grain": "day",
        },
        "metrics": [
            {"key": "occupancy", "label": "Occupancy", "unit": "percent", "precision": 1},
            {"key": "adr", "label": "ADR", "unit": "currency", "precision": 2},
            {"key": "revpar", "label": "RevPAR", "unit": "currency", "precision": 2},
            {"key": "rooms_sold", "label": "Rooms sold", "unit": "count", "precision": 0},
            {"key": "room_revenue", "label": "Room revenue", "unit": "currency", "precision": 0},
        ],
        "summary": summarize(rows, args.rooms),
        "annotations": build_annotations(events),
        "days": rows,
    }


def check_invariants(doc: dict) -> None:
    """The viewer trusts these. Fail here rather than on someone's screen."""
    rooms = doc["property"]["rooms"]
    for row in doc["days"]:
        assert row["rooms_sold"] <= rooms, f"{row['date']}: oversold"
        assert abs(row["occupancy"] - row["rooms_sold"] / rooms) < 5e-4, row["date"]
        assert abs(row["room_revenue"] - row["rooms_sold"] * row["adr"]) < 0.5, row["date"]
        assert abs(row["revpar"] - row["room_revenue"] / rooms) < 0.5, row["date"]
        assert sum(row["segments"].values()) == row["rooms_sold"], row["date"]


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--month", default="2026-06", help="Month to generate, YYYY-MM (default: 2026-06)")
    p.add_argument("--rooms", type=int, default=240, help="Rooms in the house (default: 240)")
    p.add_argument("--occupancy", type=float, default=0.72, help="Baseline occupancy before lifts (default: 0.72)")
    p.add_argument("--adr", type=float, default=268.0, help="Baseline ADR before lifts (default: 268)")
    p.add_argument("--seed", type=int, default=1421, help="Random seed (default: 1421)")
    p.add_argument("--property-id", default="demo-bayview", help="Property id in the output")
    p.add_argument("--property-name", default="Bayview Demo Hotel", help="Property name in the output")
    p.add_argument("--locale", default="Pacific Coast, CA", help="Human-readable location")
    p.add_argument("--timezone", default="America/Los_Angeles", help="IANA timezone")
    p.add_argument("--out", default="public/property.demo.json", help="Output path (default: public/property.demo.json)")
    return p.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    doc = build_document(args)
    check_invariants(doc)

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(doc, indent=2) + "\n", encoding="utf-8")

    s = doc["summary"]
    print(f"wrote {out} -- {len(doc['days'])} days, "
          f"occ {s['occupancy']:.1%}, ADR ${s['adr']:,.2f}, RevPAR ${s['revpar']:,.2f}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
