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
from datetime import date, datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT))

from adapters import dataplus, delphi, opera, paycom, toast  # noqa: E402

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

print("\n=== Paycom punches -> shifts[] ===")
paycom.DEPT_CODE_MAP.update({"100": "ROOMS", "200": "GRILL", "250": "KITCHEN",
                             "300": "HSKP", "600": "AG"})
punches = (SAMPLES / "paycom-punches.sample.csv").read_text()
shifts = paycom.to_shifts(punches, utc_offset="-07:00",
                          period_start="2026-08-01", period_end="2026-09-01")
by_id = {s["id"]: s for s in shifts}

check("a clean punch pair keeps its own id", "P0000007" in by_id, True)
check("...mapped to the layout department", by_id["P0000007"]["dept"], "GRILL")
check("...as local ISO with the property offset",
      (by_id["P0000007"]["in"], by_id["P0000007"]["out"]),
      ("2026-08-14T16:00:00-07:00", "2026-08-14T23:30:00-07:00"))
check("paid absence with no punch pair is dropped",
      [s for s in shifts if s["employee"] == "H0104"], [])
check("an unmapped department is dropped, not guessed",
      [s for s in shifts if s["employee"] == "H0105"], [])
check("...and reported for the tie-out log", "999" in paycom.unmapped(), True)
check("a zero-length punch pair is dropped",
      [s for s in shifts if s["employee"] == "H0106"], [])


def hours(shift: dict) -> float:
    return (datetime.fromisoformat(shift["out"])
            - datetime.fromisoformat(shift["in"])).total_seconds() / 3600.0


def worked(employee: str) -> list[dict]:
    return sorted((s for s in shifts if s["employee"] == employee), key=lambda s: s["in"])


# §11's own example record runs 08:02 -> 16:31, which is 8h29m -- so the §15
# daily rule earns its last 29 minutes at premium.
attendant = worked("H0101")
check("a shift past eight hours splits at the threshold", len(attendant), 2)
check("...eight hours at the base rate",
      (hours(attendant[0]), attendant[0]["rate"]), (8.0, 24.50))
check("...twenty-nine minutes at time and a half",
      (round(hours(attendant[1]) * 60), attendant[1]["rate"]), (29, round(24.50 * 1.5, 4)))

# §15: split at midnight. The night auditor works 23:00 -> 07:00.
audit = worked("H0102")
check("a shift across midnight becomes two records", len(audit), 2)
check("...the first ending at midnight", audit[0]["out"], "2026-08-15T00:00:00-07:00")
check("...the second starting there", audit[1]["in"], "2026-08-15T00:00:00-07:00")
check("...with no hours lost or gained", round(sum(hours(s) for s in audit), 2), 8.0)
check("...and neither half is overtime", {s["rate"] for s in audit}, {32.50})

# Both rules at once: the cook works 14:00 -> 00:30, ten and a half hours.
cook = worked("H0103")
check("a long shift across midnight splits on both rules", len(cook), 3)
check("...eight straight hours first",
      (cook[0]["in"], cook[0]["out"], cook[0]["rate"]),
      ("2026-08-14T14:00:00-07:00", "2026-08-14T22:00:00-07:00", 29.50))
check("...then premium up to midnight",
      (cook[1]["out"], cook[1]["rate"]),
      ("2026-08-15T00:00:00-07:00", round(29.50 * 1.5, 4)))
check("...and the remainder after it", cook[2]["in"], "2026-08-15T00:00:00-07:00")
check("...still totalling ten and a half hours",
      round(sum(hours(s) for s in cook), 2), 10.5)

# §15: weekly overtime past 40 straight-time hours, without pyramiding.
week = worked("H0110")
premium = [s for s in week if s["rate"] > 26.00]
check("five eight-hour days stay at the base rate", len(week) - len(premium), 5)
check("the sixth day is premium from the first minute", len(premium), 1)
check("...at time and a half", premium[0]["rate"], 39.0)
check("...for all six of its hours", round(hours(premium[0]), 2), 6.0)

check("no employee is ever in two places at once",
      all(a["out"] <= b["in"]
          for who in {s["employee"] for s in shifts}
          for a, b in zip(worked(who), worked(who)[1:])), True)

print("\n=== Paycom register -> salaried[] ===")
salaried = paycom.to_salaried((SAMPLES / "paycom-register.sample.csv").read_text())
by_dept = {row["dept"]: row for row in salaried}
check("two semi-monthly periods make the month", by_dept["AG"]["monthly"], 61500.00)
check("headcount is the people, not the periods", by_dept["AG"]["headcount"], 6)
check("every mapped department appears", sorted(by_dept), ["AG", "ROOMS"])

print("\n=== Data Plus AP + GL journal -> expenses[] ===")
dataplus.GL_ACCOUNT_MAP.update({"6210": "GUEST_SUPPLIES", "6215": "CLEANING_SUPPLIES",
                                "6220": "LINEN", "6225": "LAUNDRY", "6510": "R&M",
                                "6710": "ELECTRIC"})
dataplus.DEPT_CODE_MAP.update({"100": "ROOMS", "300": "HSKP", "500": "ENG",
                               "600": "AG", "900": "UTIL"})
expenses = dataplus.to_expenses(
    (SAMPLES / "dataplus-ap.sample.csv").read_text(),
    (SAMPLES / "dataplus-journal.sample.csv").read_text(),
    utc_offset="-07:00", period_start="2026-08-01", period_end="2026-09-01")
by_ref = {e["id"]: e for e in expenses}

check("a posted invoice is kept", "INV-0001832" in by_ref, True)
check("...dated on the invoice, not the post date",
      by_ref["INV-0001832"]["date"], "2026-08-11T09:00:00-07:00")
check("...with the GL account mapped to a category",
      by_ref["INV-0001832"]["category"], "GUEST_SUPPLIES")
check("...and the department code to a layout department",
      by_ref["INV-0001832"]["dept"], "ROOMS")
check("a void invoice is dropped", "INV-0001834" in by_ref, False)
check("an invoice dated before the period is dropped", "INV-0001836" in by_ref, False)
check("an unknown GL account falls into MISC rather than being dropped",
      by_ref["INV-0001835"]["category"], "MISC")
check("...and is reported for the tie-out log", "account 7777" in dataplus.unmapped(), True)
check("a credit memo keeps its sign", by_ref["INV-0001837"]["amount"], -612.40)
check("a month-end journal becomes an accrual",
      (by_ref["JE-2608-001"]["timing"], by_ref["JE-2608-001"]["category"]),
      ("accrual", "ELECTRIC"))
check("...landing at the last minute of the month",
      by_ref["JE-2608-001"]["date"], "2026-08-31T23:59:00-07:00")
check("a reclass entry is excluded", "JE-2608-900" in by_ref, False)
check("everything kept carries a §11 category",
      all(e["category"] in dataplus.CATEGORIES for e in expenses), True)

print("\nALL CHECKS PASS" if failures == 0 else f"\n{failures} CHECK(S) FAILED")
sys.exit(0 if failures == 0 else 1)
