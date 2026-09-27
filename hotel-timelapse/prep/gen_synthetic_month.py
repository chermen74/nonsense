#!/usr/bin/env python3
"""Generate a synthetic month for hotel-timelapse -- BUILD_SPEC §9 step 1.

No real property data is involved. The month is invented here, but it is not
invented *in the app's schema*: it is fabricated in the shape the source
systems actually export, then run through the same adapters a real deployment
uses (§0, §8). That way the ingestion path is exercised from the first commit
instead of being stubbed, and swapping in a genuine Opera extract later is a
change of input, not a change of code.

    Opera Cloud OHIP reservations  --prep/adapters/opera.py-->    stays[]
    Toast ordersBulk               --prep/adapters/toast.py-->    checks[]
    Delphi BEO CSV                 --prep/adapters/delphi.py-->   events[]
    Paycom punches + register      --prep/adapters/paycom.py-->   shifts[], salaried[]
    Data Plus AP + GL journal      --prep/adapters/dataplus.py--> expenses[]

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
from adapters import dataplus, delphi, opera, paycom, toast  # noqa: E402

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


# ------------------------------------------------- Paycom (payroll) --------
#
# SPEND_SPEC §16 step 9. Staffing follows the operation rather than a flat
# roster: housekeeping scales with departures and stayovers, the outlets with
# their own covers, banquets with the day's attendees. That is what makes the
# productivity sparkline in §14 mean anything.

#: layout.json department id -> (Paycom numeric code, register description)
PAYCOM_DEPTS = {
    "ROOMS": ("100", "Front Office"),
    "GRILL": ("200", "Harbor Grill"),
    "TAPROOM": ("210", "Tap Room"),
    "BQT": ("220", "Banquets"),
    "KITCHEN": ("250", "Kitchen"),
    "HSKP": ("300", "Housekeeping"),
    "ENG": ("500", "Property Operations"),
    "AG": ("600", "Administrative & General"),
    "SM": ("700", "Sales & Marketing"),
    "UTIL": ("900", "Utilities"),
}

#: dept -> role -> hourly base before seniority spread. Coastal California, 2026.
ROLE_RATES = {
    ("HSKP", "Room Attendant"): 26.50,
    ("HSKP", "House Attendant"): 25.00,
    ("HSKP", "Laundry Attendant"): 24.25,
    ("HSKP", "Inspector"): 31.00,
    ("HSKP", "Turndown Attendant"): 25.75,
    ("ROOMS", "Guest Service Agent"): 28.00,
    ("ROOMS", "Night Auditor"): 32.50,
    ("ROOMS", "Bell Attendant"): 24.00,
    ("ROOMS", "Concierge"): 27.50,
    ("ROOMS", "PBX Operator"): 25.00,
    ("GRILL", "Server"): 25.50,
    ("GRILL", "Busser"): 23.50,
    ("GRILL", "Host"): 24.00,
    ("TAPROOM", "Bartender"): 27.00,
    ("TAPROOM", "Server"): 25.50,
    ("TAPROOM", "Busser"): 23.50,
    ("KITCHEN", "Line Cook"): 29.50,
    ("KITCHEN", "Prep Cook"): 26.00,
    ("KITCHEN", "Dishwasher"): 23.75,
    ("BQT", "Banquet Server"): 26.00,
    ("BQT", "Banquet Houseman"): 25.25,
    ("ENG", "Engineer"): 38.50,
    ("ENG", "Groundskeeper"): 26.75,
    ("AG", "Accounting Clerk"): 30.00,
    ("SM", "Sales Coordinator"): 29.00,
}

#: Semi-monthly gross per department, as the payroll register reports it.
SALARIED_REGISTER = [
    ("ROOMS", 3, 13900.00),
    ("HSKP", 2, 8200.00),
    ("GRILL", 2, 7600.00),
    ("TAPROOM", 1, 3550.00),
    ("BQT", 2, 7300.00),
    ("KITCHEN", 4, 19250.00),
    ("ENG", 2, 9100.00),
    ("SM", 5, 23000.00),
    ("AG", 6, 30750.00),
]

BENEFITS_LOAD = 0.29


def _employee_pool(rng: random.Random) -> dict[tuple[str, str], list[dict]]:
    """A stable roster per role, so the same people recur all month.

    Overtime only means something if a person can work six days in a week, so
    the pool is deliberately a little smaller than peak demand.
    """
    pool: dict[tuple[str, str], list[dict]] = {}
    seq = 0
    sizes = {
        ("HSKP", "Room Attendant"): 58, ("HSKP", "House Attendant"): 11,
        ("HSKP", "Laundry Attendant"): 10, ("HSKP", "Inspector"): 8,
        ("HSKP", "Turndown Attendant"): 14,
        ("ROOMS", "Guest Service Agent"): 14, ("ROOMS", "Night Auditor"): 3,
        ("ROOMS", "Bell Attendant"): 8, ("ROOMS", "Concierge"): 4,
        ("ROOMS", "PBX Operator"): 4,
        ("GRILL", "Server"): 38, ("GRILL", "Busser"): 16, ("GRILL", "Host"): 5,
        ("TAPROOM", "Bartender"): 10, ("TAPROOM", "Server"): 20, ("TAPROOM", "Busser"): 8,
        ("KITCHEN", "Line Cook"): 40, ("KITCHEN", "Prep Cook"): 20,
        ("KITCHEN", "Dishwasher"): 24,
        ("BQT", "Banquet Server"): 26, ("BQT", "Banquet Houseman"): 10,
        ("ENG", "Engineer"): 8, ("ENG", "Groundskeeper"): 5,
        ("AG", "Accounting Clerk"): 4, ("SM", "Sales Coordinator"): 3,
    }
    for key in sorted(sizes):
        base = ROLE_RATES[key]
        people = []
        for _ in range(sizes[key]):
            seq += 1
            # Seniority spread, quantised to a quarter as payroll systems do.
            spread = round(rng.uniform(-0.75, 2.50) * 4) / 4
            people.append({
                "code": f"H{seq:04d}",
                "last": rng.choice(PAYCOM_SURNAMES),
                "first": rng.choice(PAYCOM_FORENAMES),
                "rate": round(base + spread, 2),
            })
        pool[key] = people
    return pool


PAYCOM_SURNAMES = [
    "Alvarez", "Nguyen", "Okafor", "Silva", "Ramirez", "Cruz", "Delgado", "Mendoza",
    "Torres", "Bautista", "Reyes", "Castillo", "Ibarra", "Vargas", "Salazar", "Duong",
    "Herrera", "Molina", "Padilla", "Quintero", "Serrano", "Vega", "Zamora", "Acosta",
]
PAYCOM_FORENAMES = [
    "Marisol", "Tuan", "Chidi", "Rosa", "Javier", "Elena", "Miguel", "Lupe",
    "Andres", "Noemi", "Ivan", "Paloma", "Hector", "Yesenia", "Rafael", "Minh",
]


def _shift_plan(occupied: int, departures: int, covers: dict[str, int],
                banquet_heads: int, weekday: int) -> list[tuple[str, str, int, tuple[int, int], float]]:
    """(dept, role, how many, start hh:mm, scheduled hours) for one day."""
    plan: list[tuple[str, str, int, tuple[int, int], float]] = []

    # Housekeeping on credits: a departure room is roughly 1.5 stayovers of work.
    # Roughly half an hour of attendant time per stayover and a full hour per
    # departure -- about 0.5 MPOR, which is where a full-service resort sits.
    stayovers = max(0, occupied - departures)
    attendants = max(10, round(departures / 9.0 + stayovers / 14.0))
    plan.append(("HSKP", "Room Attendant", attendants, (8, 0), 8.0))
    plan.append(("HSKP", "House Attendant", max(4, attendants // 6), (7, 30), 8.0))
    plan.append(("HSKP", "Laundry Attendant", max(4, attendants // 7), (6, 30), 8.0))
    plan.append(("HSKP", "Inspector", max(3, attendants // 10), (8, 30), 8.0))
    plan.append(("HSKP", "Turndown Attendant", max(4, round(occupied / 55.0)), (17, 0), 6.0))

    plan.append(("ROOMS", "Guest Service Agent", 4, (7, 0), 8.0))
    plan.append(("ROOMS", "Guest Service Agent", 5, (15, 0), 8.0))
    plan.append(("ROOMS", "Night Auditor", 1, (23, 0), 8.0))
    plan.append(("ROOMS", "Bell Attendant", 4 + (1 if weekday in (4, 5) else 0), (10, 0), 8.0))
    plan.append(("ROOMS", "Concierge", 2, (9, 0), 8.0))
    plan.append(("ROOMS", "PBX Operator", 2, (8, 0), 8.0))

    grill = covers.get("GRILL", 0)
    plan.append(("GRILL", "Server", max(4, round(grill / 20.0)), (15, 30), 8.0))
    plan.append(("GRILL", "Server", max(3, round(grill / 38.0)), (6, 30), 7.5))
    plan.append(("GRILL", "Busser", max(2, round(grill / 42.0)), (16, 0), 7.5))
    plan.append(("GRILL", "Host", 2, (16, 0), 7.0))

    tap = covers.get("TAPROOM", 0)
    plan.append(("TAPROOM", "Bartender", max(2, round(tap / 40.0)), (15, 0), 8.0))
    plan.append(("TAPROOM", "Server", max(3, round(tap / 26.0)), (16, 0), 8.0))
    plan.append(("TAPROOM", "Busser", max(2, round(tap / 58.0)), (16, 30), 7.0))

    total_covers = grill + tap + banquet_heads
    plan.append(("KITCHEN", "Line Cook", max(5, round(total_covers / 32.0)), (14, 0), 8.0))
    plan.append(("KITCHEN", "Prep Cook", max(3, round(total_covers / 70.0)), (6, 0), 8.0))
    plan.append(("KITCHEN", "Dishwasher", max(3, round(total_covers / 60.0)), (15, 0), 8.0))

    if banquet_heads > 0:
        # One server per fifteen covers is the banquet rule of thumb.
        plan.append(("BQT", "Banquet Server", max(3, round(banquet_heads / 13.0)), (15, 0), 8.0))
        plan.append(("BQT", "Banquet Houseman", max(2, round(banquet_heads / 40.0)), (12, 0), 8.0))

    plan.append(("ENG", "Engineer", 3 if weekday in (5, 6) else 4, (7, 0), 8.0))
    plan.append(("ENG", "Groundskeeper", 2, (6, 30), 8.0))
    plan.append(("AG", "Accounting Clerk", 0 if weekday in (5, 6) else 2, (8, 0), 8.0))
    plan.append(("SM", "Sales Coordinator", 0 if weekday in (5, 6) else 1, (8, 30), 8.0))
    return plan


PUNCH_COLUMNS = ["Punch_ID", "Employee_Code", "Last_Name", "First_Name", "Dept_Code",
                 "Dept_Desc", "Position", "Punch_In", "Punch_Out", "Pay_Rate", "Punch_Type"]
REGISTER_COLUMNS = ["Dept_Code", "Dept_Desc", "Employee_Count", "Gross_Semi_Monthly",
                    "Period_End"]


def build_paycom_exports(
    days: list[date], stays: list[dict], checks: list[dict], events: list[dict],
    rng: random.Random,
) -> tuple[str, str, int, int]:
    """Fabricate the punch CSV and the payroll register."""
    paycom.DEPT_CODE_MAP.update({code: dept for dept, (code, _) in PAYCOM_DEPTS.items()})

    by_day_occupied: dict[str, int] = {}
    for stay in stays:
        for night in stay["nights"]:
            by_day_occupied[night["date"]] = by_day_occupied.get(night["date"], 0) + 1
    by_day_departures: dict[str, int] = {}
    for stay in stays:
        day = stay["depart"][:10]
        by_day_departures[day] = by_day_departures.get(day, 0) + 1

    by_day_covers: dict[str, dict[str, int]] = {}
    for check in checks:
        day = check["closed"][:10]
        by_day_covers.setdefault(day, {})
        by_day_covers[day][check["outlet"]] = by_day_covers[day].get(check["outlet"], 0) + check["covers"]

    by_day_banquet: dict[str, int] = {}
    for event in events:
        day = event["start"][:10]
        by_day_banquet[day] = by_day_banquet.get(day, 0) + event["attendees"]

    pool = _employee_pool(rng)
    rotation: dict[tuple[str, str], int] = {}
    rows: list[dict] = []
    absences = 0
    short = 0
    seq = 0

    for day in days:
        key = day.isoformat()
        plan = _shift_plan(
            by_day_occupied.get(key, 0), by_day_departures.get(key, 0),
            by_day_covers.get(key, {}), by_day_banquet.get(key, 0), day.weekday(),
        )
        # Nobody works two places at once. The rotation spreads days off, and
        # this set is the guard that a busy day cannot quietly double-book
        # somebody when a role's pool runs short.
        working: set[str] = set()
        for dept, role, count, (hh, mm), hours in plan:
            people = pool[(dept, role)]
            cursor = rotation.get((dept, role), 0)
            code, desc = PAYCOM_DEPTS[dept]
            filled = 0
            attempts = 0
            while filled < count and attempts < len(people):
                person = people[cursor % len(people)]
                cursor += 1
                attempts += 1
                if person["code"] in working:
                    continue
                working.add(person["code"])
                filled += 1
                if filled >= count:
                    rotation[(dept, role)] = cursor % len(people)
                seq += 1
                punch_id = f"P{seq:07d}"

                # Paid absence: a record with no punch pair, which the adapter
                # filters. Roughly one shift in fifty.
                if rng.random() < 0.02:
                    absences += 1
                    rows.append({
                        "Punch_ID": punch_id, "Employee_Code": person["code"],
                        "Last_Name": person["last"], "First_Name": person["first"],
                        "Dept_Code": code, "Dept_Desc": desc, "Position": role,
                        "Punch_In": "", "Punch_Out": "",
                        "Pay_Rate": f"{person['rate']:.2f}", "Punch_Type": "PTO",
                    })
                    filled = filled  # absence still consumes the person's day
                    continue

                # People punch a few minutes either side of the schedule. A
                # scheduled shift mostly comes in at or under its hours -- a
                # manager who lets every shift run long does not keep the job --
                # so roughly one in seven runs over and earns the §15 premium.
                start = datetime.combine(day, datetime.min.time()).replace(hour=hh, minute=mm)
                start += timedelta(minutes=rng.randint(-7, 9))
                roll = rng.random()
                if roll < 0.56:
                    worked = hours
                elif roll < 0.86:
                    worked = hours - rng.choice([0.25, 0.5])
                else:
                    worked = hours + rng.choice([0.25, 0.5, 0.75, 1.5])
                end = start + timedelta(hours=worked)
                rows.append({
                    "Punch_ID": punch_id, "Employee_Code": person["code"],
                    "Last_Name": person["last"], "First_Name": person["first"],
                    "Dept_Code": code, "Dept_Desc": desc, "Position": role,
                    "Punch_In": start.strftime("%m/%d/%Y %I:%M %p"),
                    "Punch_Out": end.strftime("%m/%d/%Y %I:%M %p"),
                    "Pay_Rate": f"{person['rate']:.2f}", "Punch_Type": "REG",
                })

            short += count - filled

    punch_csv = _csv(PUNCH_COLUMNS, rows)

    register_rows = []
    for dept, heads, semi in SALARIED_REGISTER:
        code, desc = PAYCOM_DEPTS[dept]
        for period_end in (days[14], days[-1]):
            register_rows.append({
                "Dept_Code": code, "Dept_Desc": desc, "Employee_Count": str(heads),
                "Gross_Semi_Monthly": f"{semi:,.2f}",
                "Period_End": period_end.strftime("%m/%d/%Y"),
            })
    return punch_csv, _csv(REGISTER_COLUMNS, register_rows), absences, short


# ------------------------------------------- Data Plus (AP and the GL) -----

#: GL account -> §11 category. 6999 is deliberately absent from the adapter's
#: map so the MISC fallback is exercised by real generated data.
GL_ACCOUNTS = {
    "6210": "GUEST_SUPPLIES", "6215": "CLEANING_SUPPLIES", "6220": "LINEN",
    "6225": "LAUNDRY", "6310": "COMMISSIONS", "6315": "OTA_FEES",
    "6410": "CHINA_GLASS", "6415": "MENU_PAPER", "6420": "KITCHEN_SUPPLIES",
    "6510": "R&M", "6515": "CONTRACT_SERVICES", "6710": "ELECTRIC",
    "6715": "GAS", "6720": "WATER", "6810": "MARKETING", "6815": "IT",
    "6910": "CREDIT_CARD_FEES",
}

#: dept, account, vendors, month total, how many invoices, timing.
EXPENSE_PLAN = [
    ("ROOMS", "6210", ["American Hotel Register", "Guest Supply", "Sysco Guest Amenities"], 63_400, 9, "invoice"),
    ("HSKP", "6215", ["Ecolab", "Waxie Sanitary Supply"], 19_800, 6, "invoice"),
    ("HSKP", "6220", ["Standard Textile", "Venus Group"], 35_600, 5, "invoice"),
    ("HSKP", "6225", ["Crown Linen Service"], 44_200, 4, "invoice"),
    ("ROOMS", "6310", ["BCD Travel", "HelmsBriscoe"], 38_900, 5, "invoice"),
    ("ROOMS", "6315", ["Expedia Group", "Booking.com"], 124_600, 2, "invoice"),
    ("GRILL", "6410", ["Steelite International"], 7_900, 3, "invoice"),
    ("TAPROOM", "6410", ["Libbey Foodservice"], 4_300, 2, "invoice"),
    ("GRILL", "6415", ["Imperial Dade"], 6_100, 4, "invoice"),
    ("KITCHEN", "6420", ["Sysco Central California", "Restaurant Depot"], 26_700, 8, "invoice"),
    ("ENG", "6510", ["Ferguson Facilities Supply", "Grainger", "Pacific Coast Elevator"], 81_300, 11, "invoice"),
    ("ENG", "6515", ["Cintas Fire Protection", "Monterey Landscape Co", "Otis Elevator"], 96_800, 7, "invoice"),
    ("UTIL", "6720", ["Cal-Am Water"], 33_100, 2, "invoice"),
    ("SM", "6810", ["Meta Platforms", "Google Ads", "Coastal Creative"], 91_500, 6, "invoice"),
    ("AG", "6815", ["Infor HMS", "Cisco Meraki", "Lumen Technologies"], 38_400, 5, "invoice"),
    ("AG", "6999", ["Monterey County Clerk", "Shred-It"], 14_200, 4, "invoice"),
]

#: Month-end journal entries: the §12 accrual wave.
ACCRUAL_PLAN = [
    ("UTIL", "6710", "Accrued electric - PG&E", 96_400.00),
    ("UTIL", "6715", "Accrued natural gas - SoCalGas", 23_700.00),
    ("AG", "6910", "Accrued credit card discount fees", 131_900.00),
    ("ENG", "6510", "Accrued R&M - work in progress", 12_400.00),
    ("SM", "6810", "Accrued digital media - August flight", 9_800.00),
]

AP_COLUMNS = ["Invoice_Number", "Vendor_Name", "GL_Account", "Dept_Code",
              "Invoice_Date", "Post_Date", "Amount", "Status"]
JE_COLUMNS = ["JE_Number", "Description", "GL_Account", "Dept_Code", "JE_Date",
              "Amount", "Type"]


def _split_amount(total: float, parts: int, rng: random.Random) -> list[float]:
    """Break a month total into invoices that still sum to it exactly."""
    weights = [rng.uniform(0.6, 1.4) for _ in range(parts)]
    scale = total / sum(weights)
    amounts = [round(w * scale, 2) for w in weights]
    amounts[-1] = round(total - sum(amounts[:-1]), 2)
    return amounts


def build_dataplus_exports(
    days: list[date], rng: random.Random,
) -> tuple[str, str, int, int]:
    dataplus.GL_ACCOUNT_MAP.update(GL_ACCOUNTS)
    dataplus.DEPT_CODE_MAP.update({code: dept for dept, (code, _) in PAYCOM_DEPTS.items()})

    ap_rows: list[dict] = []
    voided = 0
    seq = 0
    for dept, account, vendors, total, count, _timing in EXPENSE_PLAN:
        code, _ = PAYCOM_DEPTS[dept]
        for amount in _split_amount(float(total), count, rng):
            seq += 1
            day = days[rng.randrange(len(days))]
            posted = day + timedelta(days=rng.randint(1, 4))
            ap_rows.append({
                "Invoice_Number": f"INV-{seq:07d}",
                "Vendor_Name": rng.choice(vendors),
                "GL_Account": f"{account}-{code}",
                "Dept_Code": code,
                "Invoice_Date": day.strftime("%m/%d/%Y"),
                "Post_Date": posted.strftime("%m/%d/%Y"),
                "Amount": f"{amount:,.2f}",
                "Status": "POSTED",
            })

    # A couple of voided invoices, which the adapter must not count.
    for _ in range(3):
        seq += 1
        voided += 1
        day = days[rng.randrange(len(days))]
        ap_rows.append({
            "Invoice_Number": f"INV-{seq:07d}", "Vendor_Name": "Grainger",
            "GL_Account": f"6510-500", "Dept_Code": "500",
            "Invoice_Date": day.strftime("%m/%d/%Y"),
            "Post_Date": day.strftime("%m/%d/%Y"),
            "Amount": f"{rng.uniform(200, 2400):,.2f}", "Status": "VOID",
        })
    ap_rows.sort(key=lambda r: (r["Invoice_Date"], r["Invoice_Number"]))

    je_rows = []
    last = days[-1]
    for index, (dept, account, description, amount) in enumerate(ACCRUAL_PLAN, start=1):
        code, _ = PAYCOM_DEPTS[dept]
        je_rows.append({
            "JE_Number": f"JE-{last.strftime('%y%m')}-{index:03d}",
            "Description": description,
            "GL_Account": f"{account}-{code}",
            "Dept_Code": code,
            "JE_Date": last.strftime("%m/%d/%Y"),
            "Amount": f"{amount:,.2f}",
            "Type": "ACCRUAL",
        })
    # A reclass entry, which §15 excludes.
    je_rows.append({
        "JE_Number": f"JE-{last.strftime('%y%m')}-900",
        "Description": "Reclass guest supplies to housekeeping",
        "GL_Account": "6210-300", "Dept_Code": "300",
        "JE_Date": last.strftime("%m/%d/%Y"),
        "Amount": "4,100.00", "Type": "RECLASS",
    })

    return _csv(AP_COLUMNS, ap_rows), _csv(JE_COLUMNS, je_rows), voided, 1


def _csv(columns: list[str], rows: list[dict]) -> str:
    buf = io.StringIO()
    writer = csv.DictWriter(buf, fieldnames=columns, lineterminator="\n")
    writer.writeheader()
    writer.writerows(rows)
    return buf.getvalue()


def cost_of_sales_pct(layout: dict) -> dict[str, dict[str, float]]:
    """§11 `meta.cos_pct`, per F&B outlet and banquets."""
    pct = {"BQT": {"food": 0.28, "bev": 0.20}}
    defaults = {"GRILL": {"food": 0.31, "bev": 0.23}, "TAPROOM": {"food": 0.33, "bev": 0.26}}
    for outlet in layout["outlets"]:
        pct[outlet["id"]] = defaults.get(outlet["id"], {"food": 0.32, "bev": 0.25})
    return pct


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

    # --- the cost side (SPEND_SPEC §11) ------------------------------------
    departments = {d["id"] for d in layout.get("departments", [])}
    assert departments, "layout.json declares no departments"

    seen_shift_ids: set[str] = set()
    by_employee: dict[str, list[dict]] = {}
    for shift in doc.get("shifts", []):
        assert shift["dept"] in departments, f"{shift['id']}: unknown department {shift['dept']}"
        assert shift["id"] not in seen_shift_ids, f"{shift['id']}: duplicate shift id"
        seen_shift_ids.add(shift["id"])
        assert shift["in"] < shift["out"], f"{shift['id']}: clocks out before in"
        assert shift["rate"] > 0, f"{shift['id']}: non-positive rate"
        assert period_start <= shift["in"][:10] < period_end, \
            f"{shift['id']}: punched in outside the period"
        # §15 splits at midnight, so a shift ends on its own day -- or exactly
        # at the following midnight, which is where the night auditor's does.
        next_midnight = (date.fromisoformat(shift["in"][:10]) + timedelta(days=1)).isoformat()
        assert (shift["out"][:10] == shift["in"][:10]
                or shift["out"][:19] == f"{next_midnight}T00:00:00"), \
            f"{shift['id']}: spans midnight; §15 splits those"
        by_employee.setdefault(shift["employee"], []).append(shift)

    for employee, worked in by_employee.items():
        worked.sort(key=lambda x: x["in"])
        for earlier, later in zip(worked, worked[1:]):
            assert earlier["out"] <= later["in"], \
                f"{employee}: {earlier['id']} and {later['id']} overlap"

    for row in doc.get("salaried", []):
        assert row["dept"] in departments, f"salaried: unknown department {row['dept']}"
        assert row["monthly"] > 0, f"salaried {row['dept']}: non-positive gross"
        assert row["headcount"] > 0, f"salaried {row['dept']}: no heads"

    seen_expense_ids: set[str] = set()
    for expense in doc.get("expenses", []):
        assert expense["dept"] in departments, \
            f"{expense['id']}: unknown department {expense['dept']}"
        assert expense["id"] not in seen_expense_ids, f"{expense['id']}: duplicate expense id"
        seen_expense_ids.add(expense["id"])
        assert expense["category"] in dataplus.CATEGORIES, \
            f"{expense['id']}: category {expense['category']} is not in §11"
        assert expense["amount"] != 0, f"{expense['id']}: zero-value expense"
        assert expense["timing"] in {"invoice", "accrual"}, \
            f"{expense['id']}: unknown timing {expense['timing']}"
        assert period_start <= expense["date"][:10] < period_end, \
            f"{expense['id']}: dated outside the period"

    cos_pct = doc["meta"].get("cos_pct", {})
    for outlet in outlets | {"BQT"}:
        assert outlet in cos_pct, f"meta.cos_pct has no entry for {outlet}"


def summarise(doc: dict, rooms_count: int) -> dict:
    nights = [n for s in doc["stays"] for n in s["nights"]]
    room_revenue = sum(n["rate"] for n in nights)
    food = sum(c["food"] for c in doc["checks"])
    bev = sum(c["bev"] for c in doc["checks"])
    banquet = sum(e["food"] + e["bev"] + e["room_rental"] + e["av"] for e in doc["events"])
    days = len({n["date"] for n in nights})
    available = rooms_count * days
    total_revenue = room_revenue + food + bev + banquet

    # §12: cost of sales is derived from revenue, never a record.
    cos_pct = doc["meta"].get("cos_pct", {})
    cos = 0.0
    for check in doc["checks"]:
        pct = cos_pct.get(check["outlet"], {})
        cos += check["food"] * pct.get("food", 0.0) + check["bev"] * pct.get("bev", 0.0)
    bqt = cos_pct.get("BQT", {})
    for event in doc["events"]:
        cos += event["food"] * bqt.get("food", 0.0) + event["bev"] * bqt.get("bev", 0.0)

    labor_hours = 0.0
    hourly_wages = 0.0
    for shift in doc.get("shifts", []):
        hours = (datetime.fromisoformat(shift["out"]) - datetime.fromisoformat(shift["in"])
                 ).total_seconds() / 3600.0
        labor_hours += hours
        hourly_wages += hours * shift["rate"]
    salaried_wages = sum(r["monthly"] for r in doc.get("salaried", []))
    wages = hourly_wages + salaried_wages
    labor = wages * (1.0 + doc["meta"].get("benefits_load", 0.0))
    other = sum(e["amount"] for e in doc.get("expenses", []))
    gop = total_revenue - cos - labor - other

    return {
        "days": days,
        "rooms_sold": len(nights),
        "occupancy": len(nights) / available if available else 0.0,
        "adr": room_revenue / len(nights) if nights else 0.0,
        "revpar": room_revenue / available if available else 0.0,
        "rooms": room_revenue, "food": food, "bev": bev, "banquet": banquet,
        "total": total_revenue,
        "cos": cos, "labor": labor, "other": other,
        "wages": wages, "hourly_wages": hourly_wages, "salaried_wages": salaried_wages,
        "labor_hours": labor_hours,
        "gop": gop,
        "gop_margin": gop / total_revenue if total_revenue else 0.0,
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

    # --- the cost side (SPEND_SPEC §16 step 9) -----------------------------
    # Payroll follows the operation, so the punches are built from the stays,
    # checks and events the adapters just produced rather than from a flat
    # roster invented alongside them.
    punch_csv, register_csv, absences, short_staffed = build_paycom_exports(
        days, stays, checks, events_out, rng)
    ap_csv, je_csv, voided_invoices, reclass_entries = build_dataplus_exports(days, rng)

    shifts = paycom.to_shifts(
        punch_csv, utc_offset=offset,
        period_start=period_start.isoformat(), period_end=period_end.isoformat())
    salaried = paycom.to_salaried(register_csv)
    expenses = dataplus.to_expenses(
        ap_csv, je_csv, utc_offset=offset,
        period_start=period_start.isoformat(), period_end=period_end.isoformat())

    doc = {
        "meta": {
            "month": args.month,
            "tz": prop["timezone"],
            "period_start": f"{period_start.isoformat()}T00:00:00{offset}",
            "period_end": f"{period_end.isoformat()}T00:00:00{offset}",
            "source": "synthetic",
            "generated_at": datetime.now(tz).isoformat(timespec="seconds"),
            "notice": ("Fabricated demo data. Generated in Opera Cloud / Toast / Delphi / "
                       "Paycom / Data Plus export shape and run through prep/adapters/, so "
                       "the ingestion path matches a real deployment. Not the operating "
                       "results of any hotel."),
            "cos_pct": cost_of_sales_pct(layout),
            "benefits_load": BENEFITS_LOAD,
        },
        "stays": stays,
        "checks": checks,
        "events": events_out,
        "shifts": shifts,
        "salaried": salaried,
        "expenses": expenses,
        "fixed_charges": {"monthly": 310000.00},
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
        (raw / f"paycom-punches-{args.month}.csv").write_text(punch_csv, encoding="utf-8")
        (raw / f"paycom-register-{args.month}.csv").write_text(register_csv, encoding="utf-8")
        (raw / f"dataplus-ap-{args.month}.csv").write_text(ap_csv, encoding="utf-8")
        (raw / f"dataplus-journal-{args.month}.csv").write_text(je_csv, encoding="utf-8")

    s = summarise(doc, len(rooms))
    print(f"wrote {target}")
    print(f"  {prop['name']} · {len(rooms)} rooms · {args.month} · {s['days']} days")
    print(f"  stays   {len(stays):>6}   from {len(opera_export['reservations']['reservationInfo'])} "
          f"Opera reservations ({filtered_reservations} non-occupying filtered)")
    print(f"  checks  {len(checks):>6}   from {len(toast_export['orders'])} Toast orders "
          f"({voided_orders} voided filtered)")
    print(f"  events  {len(events_out):>6}   from Delphi BEO CSV ({tentative_rows} tentative filtered)")
    print(f"  shifts  {len(shifts):>6}   from Paycom punches ({absences} paid absences filtered)")
    print(f"  expense {len(expenses):>6}   from Data Plus AP + GL "
          f"({voided_invoices} void, {reclass_entries} reclass filtered)")
    print(f"  occupancy {s['occupancy']:.1%} · ADR ${s['adr']:,.2f} · RevPAR ${s['revpar']:,.2f}")
    print(f"  rooms ${s['rooms']:,.2f} · food ${s['food']:,.2f} · bev ${s['bev']:,.2f} "
          f"· banquet ${s['banquet']:,.2f}")
    print(f"  TOTAL REVENUE ${s['total']:,.2f}")
    print(f"  wages ${s['wages']:,.2f} (hourly ${s['hourly_wages']:,.2f} over "
          f"{s['labor_hours']:,.0f} h, salaried ${s['salaried_wages']:,.2f}) "
          f"· benefits load {BENEFITS_LOAD:.0%}")
    print(f"  labor ${s['labor']:,.2f} · cost of sales ${s['cos']:,.2f} "
          f"· other expense ${s['other']:,.2f}")
    print(f"  GOP ${s['gop']:,.2f}  ({s['gop_margin']:.1%} of revenue)")

    for label, codes in (("market codes", opera.unmapped()),
                         ("sales categories", toast.unmapped()),
                         ("function rooms", delphi.unmapped()),
                         ("payroll departments", paycom.unmapped()),
                         ("GL accounts", dataplus.unmapped())):
        if codes:
            print(f"  note: unmapped {label}: {sorted(codes)}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
