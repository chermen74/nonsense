#!/usr/bin/env python3
"""Generate a synthetic month for hotel-timelapse -- BUILD_SPEC §9 step 1.

No real property data is involved. The month is invented here, but it is not
invented *in the app's schema*: it is fabricated in the shape the source
systems actually export, then run through the same adapters a real deployment
uses (§0, §8). That way the ingestion path is exercised from the first commit
instead of being stubbed, and swapping in a genuine Opera extract later is a
change of input, not a change of code.

    Opera Cloud OHIP reservations  --prep/adapters/opera.py-->   stays[]
    Toast ordersBulk               --prep/adapters/toast.py-->   checks[]
    Delphi BEO CSV                 --prep/adapters/delphi.py--> events[]

Usage:
    python3 prep/gen_synthetic_month.py --month 2026-08 --out public

`--out` is the directory holding property.json and layout.json; the month file
is written to <out>/data/<month>.json. `--emit-raw <dir>` also writes the three
fabricated exports, so the shape the adapters consume can be inspected.

Standard library only. Deterministic for a given --seed.
"""

from __future__ import annotations

import argparse
import calendar
import csv
import io
import json
import math
import random
import sys
import uuid
from datetime import date, datetime, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo

sys.path.insert(0, str(Path(__file__).resolve().parent))
from adapters import delphi, opera, toast  # noqa: E402

# --- demand shape for the demo property: a coastal California resort in peak
# --- season. Every number here is invention, tuned to look like a real August.

DOW_OCCUPANCY = {0: -0.09, 1: -0.07, 2: -0.03, 3: 0.01, 4: 0.08, 5: 0.10, 6: -0.02}
DOW_RATE = {0: 0.88, 1: 0.89, 2: 0.93, 3: 0.99, 4: 1.16, 5: 1.22, 6: 0.97}

MARKET_MIX = [("TRANSIENT", 0.64), ("GROUP", 0.24), ("CONTRACT", 0.12)]
MARKET_RATE_FACTOR = {"TRANSIENT": 1.0, "GROUP": 0.88, "CONTRACT": 0.74}
MARKET_OPERA_CODES = {
    "TRANSIENT": ["TRANS", "BAR", "LEIS", "PKG", "OTA"],
    "GROUP": ["GRPC", "GRPS", "WED"],
    "CONTRACT": ["CORP", "NEG", "CREW"],
}
RATE_PLANS = {"TRANSIENT": ["BAR", "ADVP", "AAA"], "GROUP": ["GRP26"], "CONTRACT": ["CORPNEG", "CREW"]}

TAX_RATE = 0.1165


def month_days(year: int, month: int) -> list[date]:
    return [date(year, month, d) for d in range(1, calendar.monthrange(year, month)[1] + 1)]


def offset_for(moment: datetime, tz: ZoneInfo) -> str:
    """`-07:00` for a local wall-clock moment, so a DST month stays honest."""
    return moment.replace(tzinfo=tz).strftime("%z")[:3] + ":" + moment.replace(tzinfo=tz).strftime("%z")[3:]


def local_iso(moment: datetime, tz: ZoneInfo) -> str:
    return moment.replace(tzinfo=tz).isoformat()


def to_utc_toast(moment: datetime, tz: ZoneInfo) -> str:
    """Toast stamps are UTC with a `+0000` offset and milliseconds."""
    utc = moment.replace(tzinfo=tz).astimezone(ZoneInfo("UTC"))
    return utc.strftime("%Y-%m-%dT%H:%M:%S.000+0000")


# ---------------------------------------------------------------- geometry --

def rooms_from_layout(layout: dict) -> list[str]:
    """Mirror of src/sim/rooms.ts: room numbers come from layout.json alone."""
    numbers: list[str] = []
    for wing in layout["wings"]:
        for floor in range(1, wing["floors"] + 1):
            rng = wing["room_numbers"][f"floor_{floor}"]
            first = int(rng.split("-")[0])
            numbers.extend(str(first + i) for i in range(wing["rooms_per_floor"]))
    return numbers


# ------------------------------------------------------------------ demand --

def occupancy_target(day: date, days: list[date], events: dict[date, float], rng: random.Random) -> float:
    idx = days.index(day) if day in days else 0
    seasonal = 0.03 * math.sin(2 * math.pi * idx / max(len(days), 1))
    occ = 0.86 + DOW_OCCUPANCY[day.weekday()] + seasonal + events.get(day, 0.0)
    return min(max(occ * rng.uniform(0.98, 1.02), 0.45), 0.97)


def nightly_rate(day: date, market: str, base: float, events: dict[date, float], rng: random.Random) -> float:
    lift = 1.0 + events.get(day, 0.0) * 1.4
    rate = base * DOW_RATE[day.weekday()] * MARKET_RATE_FACTOR[market] * lift
    return round(rate * rng.uniform(0.95, 1.06), 2)


def compression_events(days: list[date], rng: random.Random) -> dict[date, float]:
    """Two or three demand spikes, the kind a resort August actually has."""
    lifts: dict[date, float] = {}
    candidates = [d for d in days if 4 <= d.day <= len(days) - 4]
    chosen: list[date] = []
    for _ in range(rng.randint(2, 3)):
        pool = [d for d in candidates if all(abs((d - c).days) > 6 for c in chosen)]
        if not pool:
            break
        chosen.append(rng.choice(pool))
    for start in sorted(chosen):
        for i in range(rng.randint(2, 3)):
            day = start + timedelta(days=i)
            if day in days:
                lifts[day] = max(lifts.get(day, 0.0), 0.09 * (1 - i * 0.3))
    return lifts


# ------------------------------------------------- Opera Cloud (OHIP) side --

def pick_market(rng: random.Random) -> str:
    roll = rng.random()
    cumulative = 0.0
    for name, share in MARKET_MIX:
        cumulative += share
        if roll <= cumulative:
            return name
    return "TRANSIENT"


def length_of_stay(market: str, arrival: date, rng: random.Random) -> int:
    if market == "GROUP":
        return rng.choice([2, 2, 3, 3, 4])
    if market == "CONTRACT":
        return rng.choice([1, 1, 2])
    if arrival.weekday() in (4, 5):
        return rng.choice([1, 2, 2, 3])
    return rng.choice([1, 1, 2, 2, 3])


def guest_counts(market: str, rng: random.Random) -> tuple[int, int]:
    if market == "CONTRACT":
        return 1, 0
    if market == "GROUP":
        return rng.choice([1, 2, 2]), 0
    adults = rng.choice([1, 2, 2, 2, 3])
    children = rng.choice([0, 0, 0, 0, 1, 2]) if adults >= 2 else 0
    return adults, children


def arrival_moment(day: date, rng: random.Random) -> datetime:
    if rng.random() < 0.12:                                  # late arrivals
        hour, minute = rng.choice([21, 22, 23]), rng.randint(0, 59)
    else:
        hour = min(max(int(rng.gauss(16.6, 1.9)), 14), 20)
        minute = rng.randint(0, 59)
    return datetime(day.year, day.month, day.day, hour, minute)


def departure_moment(day: date, rng: random.Random) -> datetime:
    hour = min(max(int(rng.gauss(10.2, 1.3)), 6), 12)
    return datetime(day.year, day.month, day.day, hour, rng.randint(0, 59))


def rate_ranges(nights: list[tuple[date, float]]) -> list[tuple[date, date, float]]:
    """Collapse consecutive same-rate nights into one roomRates[] range.

    This is what Opera actually emits, and it is why the adapter has to expand
    ranges rather than assume one entry per night.
    """
    ranges: list[tuple[date, date, float]] = []
    for night, rate in nights:
        if ranges and ranges[-1][2] == rate and ranges[-1][1] == night:
            ranges[-1] = (ranges[-1][0], night + timedelta(days=1), rate)
        else:
            ranges.append((night, night + timedelta(days=1), rate))
    return ranges


def build_opera_export(
    days: list[date], rooms: list[str], hotel_id: str, base_adr: float,
    events: dict[date, float], tz: ZoneInfo, rng: random.Random,
) -> tuple[dict, list[dict], int]:
    """Fabricate OHIP reservations, plus a plain in-house index for the POS side."""
    # Start a few days early so the month opens with guests already in house.
    sim_days = [days[0] - timedelta(days=i) for i in range(4, 0, -1)] + days
    free_from: dict[str, date] = {room: sim_days[0] for room in rooms}
    occupied: dict[date, int] = {}
    reservations: list[dict] = []
    in_house: list[dict] = []
    seq = 0

    for day in sim_days:
        target = int(round(occupancy_target(day, days, events, rng) * len(rooms)))
        while occupied.get(day, 0) < target:
            market = pick_market(rng)
            los = length_of_stay(market, day, rng)
            checkout = day + timedelta(days=los)

            candidates = [r for r in rooms if free_from[r] <= day]
            if not candidates:
                break
            room = rng.choice(candidates)
            free_from[room] = checkout

            nights = [(day + timedelta(days=i),
                       nightly_rate(day + timedelta(days=i), market, base_adr, events, rng))
                      for i in range(los)]
            for night, _ in nights:
                occupied[night] = occupied.get(night, 0) + 1

            adults, children = guest_counts(market, rng)
            arrive = arrival_moment(day, rng)
            depart = departure_moment(checkout, rng)
            seq += 1
            confirmation = f"CNF{seq:07d}"

            reservations.append({
                "hotelId": hotel_id,
                "reservationIdList": [
                    {"id": str(3_000_000 + seq), "type": "Reservation"},
                    {"id": confirmation, "type": "Confirmation"},
                ],
                "reservationGuests": [{"givenName": f"Guest{seq:05d}", "surname": "Synthetic"}],
                "roomStay": {
                    "reservationStatus": "InHouse" if checkout > days[-1] else "CheckedOut",
                    "roomId": room,
                    "arrivalDate": day.isoformat(),
                    "departureDate": checkout.isoformat(),
                    "guestCounts": {"adultCount": adults, "childCount": children},
                    "marketCode": rng.choice(MARKET_OPERA_CODES[market]),
                    "ratePlanCode": rng.choice(RATE_PLANS[market]),
                    "actualTimes": {
                        "actualArrivalTime": local_iso(arrive, tz),
                        "actualDepartureTime": local_iso(depart, tz),
                    },
                    "roomRates": [
                        {
                            "start": start.isoformat(),
                            "end": end.isoformat(),
                            "roomId": room,
                            "ratePlanCode": "BAR",
                            "total": {
                                "amountBeforeTax": rate,
                                "amountAfterTax": round(rate * (1 + TAX_RATE), 2),
                                "currencyCode": "USD",
                            },
                        }
                        for start, end, rate in rate_ranges(nights)
                    ],
                },
            })

            in_house.append({
                "room": room, "market": market, "guests": adults + children,
                "arrive": arrive, "depart": depart,
                "nights": [n for n, _ in nights],
            })

    # Real exports are not tidy: cancellations, no-shows, an unassigned room and
    # a day-use booking all come back in the same payload. The adapter drops them.
    noise = 0
    for _ in range(max(6, len(reservations) // 40)):
        seq += 1
        noise += 1
        day = rng.choice(days)
        status = rng.choice(["Cancelled", "NoShow", "Reserved"])
        assigned = None if status == "Reserved" else rng.choice(rooms)
        rate = nightly_rate(day, "TRANSIENT", base_adr, events, rng)
        reservations.append({
            "hotelId": hotel_id,
            "reservationIdList": [{"id": str(3_000_000 + seq), "type": "Reservation"},
                                  {"id": f"CNF{seq:07d}", "type": "Confirmation"}],
            "reservationGuests": [{"givenName": f"Guest{seq:05d}", "surname": "Synthetic"}],
            "roomStay": {
                "reservationStatus": status,
                "roomId": assigned,
                "arrivalDate": day.isoformat(),
                "departureDate": (day + timedelta(days=1)).isoformat(),
                "guestCounts": {"adultCount": 2, "childCount": 0},
                "marketCode": "TRANS",
                "ratePlanCode": "BAR",
                "actualTimes": {},
                "roomRates": [{"start": day.isoformat(), "end": (day + timedelta(days=1)).isoformat(),
                               "roomId": assigned, "ratePlanCode": "BAR",
                               "total": {"amountBeforeTax": rate, "currencyCode": "USD"}}],
            },
        })

    day_use = rng.choice(days)
    seq += 1
    noise += 1
    reservations.append({
        "hotelId": hotel_id,
        "reservationIdList": [{"id": str(3_000_000 + seq), "type": "Reservation"},
                              {"id": f"CNF{seq:07d}", "type": "Confirmation"}],
        "reservationGuests": [{"givenName": f"Guest{seq:05d}", "surname": "Synthetic"}],
        "roomStay": {
            "reservationStatus": "CheckedOut", "roomId": rng.choice(rooms),
            "arrivalDate": day_use.isoformat(), "departureDate": day_use.isoformat(),
            "guestCounts": {"adultCount": 1, "childCount": 0},
            "marketCode": "TRANS", "ratePlanCode": "DAYUSE",
            "actualTimes": {
                "actualArrivalTime": local_iso(datetime(day_use.year, day_use.month, day_use.day, 10, 0), tz),
                "actualDepartureTime": local_iso(datetime(day_use.year, day_use.month, day_use.day, 16, 0), tz),
            },
            "roomRates": [],
        },
    })

    rng.shuffle(reservations)
    return {"reservations": {"reservationInfo": reservations}}, in_house, noise


# ------------------------------------------------------------ Toast (POS) --

MENU = {
    "breakfast": [("Breakfast", "food", 14, 28), ("Coffee", "bev", 4, 9)],
    "lunch":     [("Entree", "food", 17, 34), ("Beer", "bev", 8, 14)],
    "dinner":    [("Entree", "food", 28, 62), ("Appetizer", "food", 12, 24),
                  ("Wine", "bev", 14, 32), ("Cocktail", "bev", 15, 22)],
}

SERVICE_WINDOWS = {
    "GRILL": [("breakfast", 7, 10, 0.42), ("dinner", 17, 21, 0.22)],
    "TAPROOM": [("lunch", 11, 14, 0.16), ("dinner", 17, 22, 0.20)],
}


def build_toast_export(
    days: list[date], layout: dict, in_house: list[dict], tz: ZoneInfo, rng: random.Random,
) -> tuple[dict, int]:
    """Fabricate ordersBulk payloads driven by who is actually in the hotel."""
    guids = {o["id"]: str(uuid.UUID(int=rng.getrandbits(128), version=4)) for o in layout["outlets"]}
    seats = {o["id"]: o["seats"] for o in layout["outlets"]}
    toast.RESTAURANT_GUID_TO_OUTLET.update({g: oid for oid, g in guids.items()})

    # Who is in house on each night, so a room charge names a real occupied room.
    by_night: dict[date, list[dict]] = {}
    for stay in in_house:
        for night in stay["nights"]:
            by_night.setdefault(night, []).append(stay)

    orders: list[dict] = []
    voided = 0

    for day in days:
        resident = by_night.get(day, [])
        heads = sum(s["guests"] for s in resident)
        for outlet_id, windows in SERVICE_WINDOWS.items():
            if outlet_id not in seats:
                continue
            for meal, open_h, close_h, capture in windows:
                covers = int(heads * capture * rng.uniform(0.8, 1.2))
                covers = min(covers, seats[outlet_id] * (close_h - open_h) // 2)
                served = 0
                while served < covers:
                    party = rng.choice([1, 2, 2, 2, 3, 4, 4, 6])
                    served += party
                    hour = rng.randint(open_h, max(open_h, close_h - 1))
                    opened = datetime(day.year, day.month, day.day, hour, rng.randint(0, 59))
                    closed = opened + timedelta(minutes=rng.randint(35, 115))

                    selections = []
                    for _ in range(party):
                        for name, bucket, lo, hi in MENU[meal]:
                            if bucket == "bev" and rng.random() > (0.75 if meal == "dinner" else 0.45):
                                continue
                            if name == "Appetizer" and rng.random() > 0.4:
                                continue
                            selections.append({
                                "guid": str(uuid.UUID(int=rng.getrandbits(128), version=4)),
                                "displayName": name,
                                "salesCategory": {"name": name if name != "Entree" else "Food"},
                                "price": round(rng.uniform(lo, hi), 2),
                                "voided": False,
                            })
                    if not selections:
                        continue

                    # Roughly half of in-house diners charge it to the room.
                    charge_room = resident and rng.random() < 0.52
                    payments = []
                    if charge_room:
                        stay = rng.choice(resident)
                        payments.append({
                            "type": "OTHER",
                            "otherPayment": {"name": "Room Charge"},
                            "roomNumber": stay["room"],
                            "amount": round(sum(s["price"] for s in selections), 2),
                        })
                    else:
                        payments.append({"type": "CREDIT", "amount":
                                         round(sum(s["price"] for s in selections), 2)})

                    is_void = rng.random() < 0.012
                    if is_void:
                        voided += 1

                    orders.append({
                        "guid": str(uuid.UUID(int=rng.getrandbits(128), version=4)),
                        "restaurantGuid": guids[outlet_id],
                        "businessDate": int(day.strftime("%Y%m%d")),
                        "openedDate": to_utc_toast(opened, tz),
                        "closedDate": None if rng.random() < 0.004 else to_utc_toast(closed, tz),
                        "numberOfGuests": party,
                        "voided": is_void,
                        "checks": [{
                            "guid": str(uuid.UUID(int=rng.getrandbits(128), version=4)),
                            "voided": False,
                            "selections": selections,
                            "payments": payments,
                        }],
                    })

    rng.shuffle(orders)
    return {"orders": orders}, voided


# ----------------------------------------------------- Delphi (BEO export) --

BEO_COLUMNS = ["Booking ID", "Post As", "Function Room", "Status", "Date", "End Date",
               "Start Time", "End Time", "Guaranteed", "Food Revenue", "Beverage Revenue",
               "Room Rental", "AV Revenue"]

WEDDING_NAMES = ["Alvarez", "Nakamura", "Okonkwo", "Pereira", "Lindqvist", "Haddad", "Whitfield"]
MEETING_NAMES = ["Quarterly Sales Meeting", "Regional Training", "Board Offsite",
                 "Product Workshop", "Partner Briefing", "Leadership Council"]


def build_delphi_export(days: list[date], layout: dict, rng: random.Random) -> tuple[str, int]:
    ballroom = next((f for f in layout["function_rooms"] if f["capacity"] >= 200), None)
    meeting = next((f for f in layout["function_rooms"] if f["capacity"] < 200), None)
    delphi.FUNCTION_ROOM_MAP.update(
        {f["name"]: f["id"] for f in layout["function_rooms"]}
    )

    rows: list[dict] = []
    seq = 0
    tentative = 0

    def money(value: float) -> str:
        return f"${value:,.2f}"

    for day in days:
        # Saturday weddings in the ballroom.
        if ballroom and day.weekday() == 5 and rng.random() < 0.85:
            seq += 1
            pax = rng.randint(140, min(320, ballroom["capacity"]))
            per_head_food = rng.uniform(105, 165)
            rows.append({
                "Booking ID": f"BEO{seq:05d}",
                "Post As": f"Wedding — {rng.choice(WEDDING_NAMES)}",
                "Function Room": ballroom["name"], "Status": "Definite",
                "Date": day.isoformat(), "End Date": day.isoformat(),
                "Start Time": "5:00 PM", "End Time": "11:00 PM",
                "Guaranteed": pax,
                "Food Revenue": money(pax * per_head_food),
                "Beverage Revenue": money(pax * rng.uniform(38, 62)),
                "Room Rental": money(rng.choice([2500, 3500, 4500])),
                "AV Revenue": money(rng.uniform(1200, 2800)),
            })

        # Midweek corporate meetings in the small room.
        if meeting and day.weekday() in (1, 2, 3) and rng.random() < 0.55:
            seq += 1
            pax = rng.randint(18, min(55, meeting["capacity"]))
            rows.append({
                "Booking ID": f"BEO{seq:05d}",
                "Post As": rng.choice(MEETING_NAMES),
                "Function Room": meeting["name"], "Status": "Definite",
                "Date": day.isoformat(), "End Date": day.isoformat(),
                "Start Time": "8:00 AM", "End Time": "4:00 PM",
                "Guaranteed": pax,
                "Food Revenue": money(pax * rng.uniform(62, 98)),
                "Beverage Revenue": money(pax * rng.uniform(14, 26)),
                "Room Rental": money(rng.choice([750, 950, 1200])),
                "AV Revenue": money(rng.uniform(350, 900)),
            })

        # A general session during a compression weekend.
        if ballroom and day.weekday() == 3 and rng.random() < 0.3:
            seq += 1
            pax = rng.randint(180, min(400, ballroom["capacity"]))
            rows.append({
                "Booking ID": f"BEO{seq:05d}",
                "Post As": "Conference General Session",
                "Function Room": ballroom["name"], "Status": "Definite",
                "Date": day.isoformat(), "End Date": day.isoformat(),
                "Start Time": "8:30 AM", "End Time": "2:30 PM",
                "Guaranteed": pax,
                "Food Revenue": money(pax * rng.uniform(58, 86)),
                "Beverage Revenue": money(pax * rng.uniform(12, 22)),
                "Room Rental": money(5500), "AV Revenue": money(rng.uniform(3200, 6800)),
            })

    # Tentative business sits in the same export and must not be replayed.
    for _ in range(2):
        seq += 1
        tentative += 1
        day = rng.choice(days)
        rows.append({
            "Booking ID": f"BEO{seq:05d}", "Post As": "Prospective Group Block",
            "Function Room": (ballroom or meeting)["name"], "Status": "Tentative",
            "Date": day.isoformat(), "End Date": day.isoformat(),
            "Start Time": "6:00 PM", "End Time": "10:00 PM",
            "Guaranteed": 120, "Food Revenue": money(14000), "Beverage Revenue": money(5200),
            "Room Rental": money(3000), "AV Revenue": money(1500),
        })

    buffer = io.StringIO()
    writer = csv.DictWriter(buffer, fieldnames=BEO_COLUMNS)
    writer.writeheader()
    writer.writerows(sorted(rows, key=lambda r: r["Booking ID"]))
    return buffer.getvalue(), tentative


# ------------------------------------------------------------ assembly ------

def check_invariants(doc: dict, layout: dict, rooms: set[str]) -> None:
    """Fail here rather than on someone's screen."""
    period_start = doc["meta"]["period_start"][:10]
    period_end = doc["meta"]["period_end"][:10]
    outlets = {o["id"] for o in layout["outlets"]}
    function_rooms = {f["id"] for f in layout["function_rooms"]}

    occupied: dict[tuple[str, str], str] = {}
    for stay in doc["stays"]:
        assert stay["room"] in rooms, f"{stay['id']}: room {stay['room']} is not in layout.json"
        if not stay["nights"]:
            # Only legal for a stay that straddles a period boundary; see the
            # note in prep/adapters/opera.py.
            assert stay["arrive"][:10] < period_start or stay["depart"][:10] >= period_end, \
                f"{stay['id']}: no nights, and does not straddle the period"

        assert stay["guests"] >= 1, f"{stay['id']}: no guests"
        assert stay["arrive"] < stay["depart"], f"{stay['id']}: departs before it arrives"
        for night in stay["nights"]:
            assert period_start <= night["date"] < period_end, \
                f"{stay['id']}: night {night['date']} is outside the period"
            assert night["rate"] > 0, f"{stay['id']}: non-positive rate on {night['date']}"
            key = (stay["room"], night["date"])
            assert key not in occupied, \
                f"room {stay['room']} double-booked on {night['date']} ({occupied[key]} and {stay['id']})"
            occupied[key] = stay["id"]

    in_house_rooms = {r for r, _ in occupied}
    for check in doc["checks"]:
        assert check["outlet"] in outlets, f"{check['id']}: unknown outlet {check['outlet']}"
        assert check["opened"] <= check["closed"], f"{check['id']}: closes before it opens"
        assert check["food"] + check["bev"] > 0, f"{check['id']}: zero-value check"
        if check["room"] is not None:
            assert check["room"] in in_house_rooms, \
                f"{check['id']}: room charge to {check['room']}, never occupied this month"

    for event in doc["events"]:
        assert event["function_room"] in function_rooms, \
            f"{event['id']}: unknown function room {event['function_room']}"
        assert event["start"] < event["end"], f"{event['id']}: ends before it starts"
        assert event["attendees"] > 0, f"{event['id']}: no attendees"


def summarise(doc: dict, rooms_count: int) -> dict:
    nights = [n for s in doc["stays"] for n in s["nights"]]
    room_revenue = sum(n["rate"] for n in nights)
    food = sum(c["food"] for c in doc["checks"])
    bev = sum(c["bev"] for c in doc["checks"])
    banquet = sum(e["food"] + e["bev"] + e["room_rental"] + e["av"] for e in doc["events"])
    days = len({n["date"] for n in nights})
    available = rooms_count * days
    return {
        "days": days,
        "rooms_sold": len(nights),
        "occupancy": len(nights) / available if available else 0.0,
        "adr": room_revenue / len(nights) if nights else 0.0,
        "revpar": room_revenue / available if available else 0.0,
        "rooms": room_revenue, "food": food, "bev": bev, "banquet": banquet,
        "total": room_revenue + food + bev + banquet,
    }


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--month", default="2026-08", help="Month to generate, YYYY-MM")
    p.add_argument("--out", default="public",
                   help="Directory holding property.json and layout.json (default: public)")
    p.add_argument("--emit-raw", metavar="DIR",
                   help="Also write the fabricated Opera/Toast/Delphi exports here")
    p.add_argument("--adr", type=float, default=425.0, help="Baseline ADR before lifts")
    p.add_argument("--seed", type=int, default=826, help="Random seed")
    return p.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    out_dir = Path(args.out)
    year, month = (int(part) for part in args.month.split("-"))
    days = month_days(year, month)

    layout = json.loads((out_dir / "layout.json").read_text())
    property_path = out_dir / "property.json"
    if not property_path.exists():
        property_path = out_dir / "property.demo.json"
    prop = json.loads(property_path.read_text())

    tz = ZoneInfo(prop["timezone"])
    rooms = rooms_from_layout(layout)
    if len(rooms) != prop["rooms"]:
        print(f"layout.json expands to {len(rooms)} rooms but {property_path.name} "
              f"says {prop['rooms']}", file=sys.stderr)
        return 1

    rng = random.Random(args.seed)
    events = compression_events(days, rng)

    # --- fabricate the source-system exports -------------------------------
    opera_export, in_house, filtered_reservations = build_opera_export(
        days, rooms, prop.get("id", "DEMO1").upper()[:8], args.adr, events, tz, rng)
    toast_export, voided_orders = build_toast_export(days, layout, in_house, tz, rng)
    delphi_csv, tentative_rows = build_delphi_export(days, layout, rng)

    # --- run them through the adapters a real deployment uses --------------
    period_start = days[0]
    period_end = days[-1] + timedelta(days=1)
    offset = offset_for(datetime(year, month, 15, 12), tz)

    stays = opera.to_stays(opera_export, period_start=period_start, period_end=period_end)
    checks = toast.to_checks(toast_export, timezone_name=prop["timezone"])
    events_out = delphi.to_events(delphi_csv, utc_offset=offset)

    doc = {
        "meta": {
            "month": args.month,
            "tz": prop["timezone"],
            "period_start": f"{period_start.isoformat()}T00:00:00{offset}",
            "period_end": f"{period_end.isoformat()}T00:00:00{offset}",
            "source": "synthetic",
            "generated_at": datetime.now(tz).isoformat(timespec="seconds"),
            "notice": ("Fabricated demo data. Generated in Opera Cloud / Toast / Delphi "
                       "export shape and run through prep/adapters/, so the ingestion path "
                       "matches a real deployment. Not the operating results of any hotel."),
        },
        "stays": stays,
        "checks": checks,
        "events": events_out,
    }

    check_invariants(doc, layout, set(rooms))

    data_dir = out_dir / "data"
    data_dir.mkdir(parents=True, exist_ok=True)
    target = data_dir / f"{args.month}.json"
    # Machine-read, and it ships in the repo, so compact rather than pretty.
    target.write_text(json.dumps(doc, separators=(",", ":")) + "\n", encoding="utf-8")

    if args.emit_raw:
        raw = Path(args.emit_raw)
        raw.mkdir(parents=True, exist_ok=True)
        (raw / f"opera-reservations-{args.month}.json").write_text(
            json.dumps(opera_export, indent=2) + "\n", encoding="utf-8")
        (raw / f"toast-orders-{args.month}.json").write_text(
            json.dumps(toast_export, indent=2) + "\n", encoding="utf-8")
        (raw / f"delphi-beo-{args.month}.csv").write_text(delphi_csv, encoding="utf-8")

    s = summarise(doc, len(rooms))
    print(f"wrote {target}")
    print(f"  {prop['name']} · {len(rooms)} rooms · {args.month} · {s['days']} days")
    print(f"  stays   {len(stays):>6}   from {len(opera_export['reservations']['reservationInfo'])} "
          f"Opera reservations ({filtered_reservations} non-occupying filtered)")
    print(f"  checks  {len(checks):>6}   from {len(toast_export['orders'])} Toast orders "
          f"({voided_orders} voided filtered)")
    print(f"  events  {len(events_out):>6}   from Delphi BEO CSV ({tentative_rows} tentative filtered)")
    print(f"  occupancy {s['occupancy']:.1%} · ADR ${s['adr']:,.2f} · RevPAR ${s['revpar']:,.2f}")
    print(f"  rooms ${s['rooms']:,.2f} · food ${s['food']:,.2f} · bev ${s['bev']:,.2f} "
          f"· banquet ${s['banquet']:,.2f}")
    print(f"  TOTAL REVENUE ${s['total']:,.2f}")

    for label, codes in (("market codes", opera.unmapped()),
                         ("sales categories", toast.unmapped()),
                         ("function rooms", delphi.unmapped())):
        if codes:
            print(f"  note: unmapped {label}: {sorted(codes)}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
