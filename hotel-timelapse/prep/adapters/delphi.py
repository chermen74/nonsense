"""Delphi / Salesforce BEO export -> BUILD_SPEC §3 `events[]`.

Input is the weekly Scheduled Reports CSV (§8): one row per booked function,
with revenue split across F&B, room rental and AV.

Real-export handling:

* Function-room names are free text in Delphi and must be mapped to the ids in
  layout.json rather than matched by string.
* Money arrives formatted -- `$21,600.00`, blanks, parentheses for credits.
* Definite/Tentative/Prospect statuses share the file; only definite business
  belongs in an actuals replay.
* Times are local wall clock, split across separate date and time columns.
"""

from __future__ import annotations

import csv
import io
import re
from datetime import datetime
from typing import Any, Iterable

# --- property-specific configuration ---

#: Delphi function-room name -> layout.json function_rooms[].id
FUNCTION_ROOM_MAP: dict[str, str] = {}

BOOKED_STATUSES = frozenset({"definite", "actual", "turned definite"})

_unmapped_rooms: set[str] = set()


def unmapped() -> set[str]:
    return set(_unmapped_rooms)


def _money(raw: str | float | None) -> float:
    if raw is None or raw == "":
        return 0.0
    if isinstance(raw, (int, float)):
        return round(float(raw), 2)
    text = str(raw).strip()
    negative = text.startswith("(") and text.endswith(")")
    text = re.sub(r"[^0-9.\-]", "", text)
    if not text or text in {"-", "."}:
        return 0.0
    value = float(text)
    return round(-value if negative else value, 2)


def _stamp(day: str, clock: str, offset: str) -> str:
    """`2026-08-15` + `5:00 PM` -> `2026-08-15T17:00:00-07:00`."""
    clean = clock.strip().upper().replace(".", "")
    for fmt in ("%I:%M %p", "%I%p", "%H:%M", "%H%M"):
        try:
            parsed = datetime.strptime(clean, fmt)
            break
        except ValueError:
            continue
    else:
        raise ValueError(f"unreadable BEO time: {clock!r}")
    return f"{day}T{parsed.hour:02d}:{parsed.minute:02d}:00{offset}"


def _rows(payload: Any) -> Iterable[dict]:
    if isinstance(payload, str):
        return list(csv.DictReader(io.StringIO(payload)))
    return payload


def to_events(payload: Any, *, utc_offset: str) -> list[dict]:
    """Convert a BEO export into §3 `events[]`.

    `utc_offset` is the property's offset for the period, e.g. `-07:00`.
    """
    events: list[dict] = []

    for row in _rows(payload):
        status = (row.get("Status") or "").strip().lower()
        if status and status not in BOOKED_STATUSES:
            continue                                   # tentative / prospect / lost

        name = (row.get("Function Room") or "").strip()
        room_id = FUNCTION_ROOM_MAP.get(name, row.get("FunctionRoomId"))
        if not room_id:
            if name:
                _unmapped_rooms.add(name)
            continue

        start_day = (row.get("Date") or row.get("Start Date") or "").strip()
        end_day = (row.get("End Date") or start_day).strip()

        events.append({
            "id": (row.get("Booking ID") or row.get("BookingId") or "").strip(),
            "function_room": room_id,
            "name": (row.get("Post As") or row.get("Booking Name") or "Function").strip(),
            "start": _stamp(start_day, row.get("Start Time", "") or "", utc_offset),
            "end": _stamp(end_day, row.get("End Time", "") or "", utc_offset),
            "attendees": int(float(row.get("Guaranteed") or row.get("Expected") or 0)),
            "food": _money(row.get("Food Revenue")),
            "bev": _money(row.get("Beverage Revenue")),
            "room_rental": _money(row.get("Room Rental")),
            "av": _money(row.get("AV Revenue")),
        })

    events.sort(key=lambda e: (e["start"], e["id"]))
    return events
