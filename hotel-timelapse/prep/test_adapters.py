#!/usr/bin/env python3
"""Adapter tests against the curated exports in prep/sample_exports/.

Each fixture record exists because a real export contains that case. Run:

    python3 prep/test_adapters.py
"""

from __future__ import annotations

import csv
import io
import json
import sys
from datetime import date
from pathlib import Path

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT))

from adapters import delphi, opera, toast  # noqa: E402

SAMPLES = ROOT / "sample_exports"
TZ = "America/Los_Angeles"

failures = 0


def check(name: str, actual: object, expected: object) -> None:
    global failures
    ok = actual == expected
    if not ok:
        failures += 1
    print(f"{'PASS' if ok else 'FAIL'}  {name}")
    if not ok:
        print(f"        got      {actual!r}")
        print(f"        expected {expected!r}")


print("=== Opera Cloud (OHIP) -> stays[] ===")
payload = json.loads((SAMPLES / "opera-reservations.sample.json").read_text())
stays = opera.to_stays(payload, period_start=date(2026, 8, 1), period_end=date(2026, 9, 1))
by_id = {s["id"]: s for s in stays}

check("only occupying reservations survive", sorted(by_id),
      ["CNF0000001", "CNF0000002", "CNF0000003", "CNF0000004", "CNF0000010", "CNF0000011"])
check("a 3-night single range expands to 3 nights",
      [n["date"] for n in by_id["CNF0000001"]["nights"]],
      ["2026-08-14", "2026-08-15", "2026-08-16"])
check("each expanded night carries the range's rate",
      {n["rate"] for n in by_id["CNF0000001"]["nights"]}, {389.00})
check("a mid-stay rate change stays split by night",
      [(n["date"], n["rate"]) for n in by_id["CNF0000002"]["nights"]],
      [("2026-08-15", 512.00), ("2026-08-16", 430.50)])
check("children are added to adults", by_id["CNF0000003"]["guests"], 4)
check("a stay straddling the month keeps only August nights",
      [n["date"] for n in by_id["CNF0000004"]["nights"]], ["2026-08-01"])
check("...but keeps its real July arrival, so it is in house at 00:00 Aug 1",
      by_id["CNF0000004"]["arrive"][:10], "2026-07-30")
check("a stay running past month end keeps only August nights",
      [n["date"] for n in by_id["CNF0000010"]["nights"]], ["2026-08-31"])
check("a guest checking out on the first morning is kept",
      "CNF0000011" in by_id, True)
check("...with no nights, because the revenue is the previous month's",
      by_id["CNF0000011"]["nights"], [])
check("...but a real departure timestamp, so they still walk out",
      by_id["CNF0000011"]["depart"], "2026-08-01T09:15:00-07:00")
check("group market code maps to GROUP", by_id["CNF0000002"]["market"], "GROUP")
check("unmapped market code falls back to TRANSIENT", by_id["CNF0000010"]["market"], "TRANSIENT")
check("...and is reported rather than swallowed", "WHOLESALE" in opera.unmapped(), True)

print("\n=== Toast -> checks[] ===")
toast.RESTAURANT_GUID_TO_OUTLET.update({"guid-grill": "GRILL", "guid-taproom": "TAPROOM"})
orders = json.loads((SAMPLES / "toast-orders.sample.json").read_text())
checks = toast.to_checks(orders, timezone_name=TZ)
by_guid = {c["id"]: c for c in checks}

check("voided and still-open orders are dropped", sorted(by_guid),
      ["ord-0001", "ord-0002", "ord-0003", "ord-0004"])
check("UTC stamps become local hotel time",
      by_guid["ord-0001"]["opened"], "2026-08-14T18:05:00-07:00")
check("food and bev split by sales category",
      (by_guid["ord-0001"]["food"], by_guid["ord-0001"]["bev"]), (60.50, 40.00))
check("a room-charge tender links the check to its room",
      by_guid["ord-0001"]["room"], "1214")
check("a card-paid walk-in has no room", by_guid["ord-0002"]["room"], None)
check("a voided selection is not counted",
      (by_guid["ord-0003"]["food"], by_guid["ord-0003"]["bev"]), (22.00, 0.00))
check("an unmapped sales category lands in food rather than vanishing",
      (by_guid["ord-0004"]["food"], by_guid["ord-0004"]["bev"]), (15.00, 0.00))
check("...and is reported", "merchandise" in toast.unmapped(), True)

print("\n=== Delphi BEO CSV -> events[] ===")
delphi.FUNCTION_ROOM_MAP.update({"Ballroom": "BALLROOM", "Meeting Room 1": "MTG1"})
csv_text = (SAMPLES / "delphi-beo.sample.csv").read_text()
events = delphi.to_events(csv_text, utc_offset="-07:00")
by_beo = {e["id"]: e for e in events}

check("tentative and unmapped rooms are excluded", sorted(by_beo), ["BEO00001", "BEO00002"])
check("an unmapped function room is reported", "Sunset Terrace" in delphi.unmapped(), True)
check("12-hour times become local ISO", by_beo["BEO00001"]["start"], "2026-08-15T17:00:00-07:00")
check("end time too", by_beo["BEO00001"]["end"], "2026-08-15T23:00:00-07:00")
check("formatted money parses",
      (by_beo["BEO00001"]["food"], by_beo["BEO00001"]["bev"],
       by_beo["BEO00001"]["room_rental"], by_beo["BEO00001"]["av"]),
      (21600.00, 7200.00, 2500.00, 1800.00))
check("a blank revenue column is zero, not an error", by_beo["BEO00002"]["av"], 0.0)
check("function room name maps to the layout id", by_beo["BEO00002"]["function_room"], "MTG1")

print("\nALL CHECKS PASS" if failures == 0 else f"\n{failures} CHECK(S) FAILED")
sys.exit(0 if failures == 0 else 1)
