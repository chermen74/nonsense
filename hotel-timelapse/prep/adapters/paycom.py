"""Paycom punch and payroll-register exports -> SPEND_SPEC §11 `shifts[]` and
`salaried[]`.

Input is the daily punch CSV and the semi-monthly payroll register (§15).

Real-export handling:

* Paycom department codes are numeric and property-specific; they map to the
  department ids in layout.json through a config dict, never by string match.
* Timestamps are `MM/DD/YYYY hh:mm AM/PM` local wall clock with no offset.
* A shift worked across midnight is one punch pair in Paycom but two operating
  days for the hotel, so it is split at 00:00 (§15).
* California overtime: time-and-a-half beyond 8 hours in a day and beyond 40
  straight-time hours in a week (§15).
* Non-worked punch types -- PTO, holiday, sick -- carry no floor presence and
  no in/out to draw, so they are filtered here rather than in `src/`.

§11 gives a shift a single `rate`, so a shift that crosses an overtime
threshold is emitted as two records: the straight-time hours and, after the
threshold instant, the premium hours at 1.5x. That keeps one rate per record
and keeps the punch's own clock times intact, which is what the floor shows.
"""

from __future__ import annotations

import csv
import io
from datetime import datetime, timedelta
from typing import Any, Iterable

# --- property-specific configuration ---

#: Paycom department code -> layout.json departments[].id
DEPT_CODE_MAP: dict[str, str] = {}

#: Punch types that put somebody on the floor. Everything else is paid absence.
WORKED_PUNCH_TYPES = frozenset({"reg", "regular", "ot", "overtime", "dt", "worked"})

#: California thresholds (§15).
DAILY_STRAIGHT_HOURS = 8.0
WEEKLY_STRAIGHT_HOURS = 40.0
OVERTIME_MULTIPLIER = 1.5

_unmapped_depts: set[str] = set()


def unmapped() -> set[str]:
    return set(_unmapped_depts)


def _stamp(raw: str) -> datetime | None:
    """`08/14/2026 08:02 AM` -> naive local datetime."""
    text = (raw or "").strip().upper().replace(".", "")
    if not text:
        return None
    for fmt in ("%m/%d/%Y %I:%M %p", "%m/%d/%Y %H:%M", "%Y-%m-%d %H:%M:%S",
                "%Y-%m-%d %H:%M", "%m/%d/%y %I:%M %p"):
        try:
            return datetime.strptime(text, fmt)
        except ValueError:
            continue
    return None


def _rate(raw: str | float | None) -> float:
    if raw is None or raw == "":
        return 0.0
    if isinstance(raw, (int, float)):
        return round(float(raw), 4)
    text = "".join(ch for ch in str(raw) if ch.isdigit() or ch in ".-")
    if not text or text in {"-", "."}:
        return 0.0
    return round(float(text), 4)


def _iso(moment: datetime, offset: str) -> str:
    return moment.strftime("%Y-%m-%dT%H:%M:%S") + offset


def _split_midnight(start: datetime, end: datetime) -> list[tuple[datetime, datetime]]:
    """§15: a punch pair spanning midnight is two operating days."""
    out: list[tuple[datetime, datetime]] = []
    cursor = start
    while cursor.date() < end.date():
        boundary = datetime.combine(cursor.date() + timedelta(days=1), datetime.min.time())
        out.append((cursor, boundary))
        cursor = boundary
    if cursor < end:
        out.append((cursor, end))
    return out


def _week_key(day: datetime) -> tuple[int, int]:
    iso = day.isocalendar()
    return (iso[0], iso[1])


def to_shifts(
    punch_csv: str,
    *,
    utc_offset: str,
    period_start: str | None = None,
    period_end: str | None = None,
) -> list[dict[str, Any]]:
    """Punch CSV -> §11 `shifts[]`, midnight-split and overtime-split.

    `period_start`/`period_end` are `YYYY-MM-DD`; punches outside are dropped,
    because a payroll export usually spans whole pay periods rather than whole
    months.
    """
    rows = list(csv.DictReader(io.StringIO(punch_csv)))

    segments: list[dict[str, Any]] = []
    for row in rows:
        kind = (row.get("Punch_Type") or "REG").strip().lower()
        if kind not in WORKED_PUNCH_TYPES:
            continue
        start = _stamp(row.get("Punch_In", ""))
        end = _stamp(row.get("Punch_Out", ""))
        if start is None or end is None or end <= start:
            continue                      # still clocked in, or a bad pair
        code = (row.get("Dept_Code") or "").strip()
        dept = DEPT_CODE_MAP.get(code)
        if dept is None:
            _unmapped_depts.add(code)
            continue
        rate = _rate(row.get("Pay_Rate"))
        if rate <= 0:
            continue
        for piece_start, piece_end in _split_midnight(start, end):
            segments.append({
                "id": (row.get("Punch_ID") or row.get("Employee_Code") or "P").strip(),
                "employee": (row.get("Employee_Code") or "").strip(),
                "dept": dept,
                "role": (row.get("Position") or "").strip(),
                "rate": rate,
                "start": piece_start,
                "end": piece_end,
            })

    # Overtime is a running total per employee, so the order matters.
    segments.sort(key=lambda s: (s["employee"], s["start"], s["id"]))

    daily: dict[tuple[str, Any], float] = {}
    weekly: dict[tuple[str, Any], float] = {}
    out: list[dict[str, Any]] = []
    used_ids: dict[str, int] = {}

    for seg in segments:
        if period_start and seg["start"].date().isoformat() < period_start:
            continue
        if period_end and seg["start"].date().isoformat() >= period_end:
            continue

        day_key = (seg["employee"], seg["start"].date())
        week_key = (seg["employee"], _week_key(seg["start"]))
        remaining = (seg["end"] - seg["start"]).total_seconds() / 3600.0
        cursor = seg["start"]
        pieces: list[tuple[datetime, datetime, float]] = []

        while remaining > 1e-9:
            to_daily = max(0.0, DAILY_STRAIGHT_HOURS - daily.get(day_key, 0.0))
            to_weekly = max(0.0, WEEKLY_STRAIGHT_HOURS - weekly.get(week_key, 0.0))
            if to_daily > 1e-9 and to_weekly > 1e-9:
                take = min(remaining, to_daily, to_weekly)
                multiplier = 1.0
            else:
                take = remaining
                multiplier = OVERTIME_MULTIPLIER
            finish = cursor + timedelta(hours=take)
            pieces.append((cursor, finish, multiplier))
            daily[day_key] = daily.get(day_key, 0.0) + take
            # Only straight time counts toward the 40, so an hour already paid
            # daily overtime cannot also push the week into overtime.
            if multiplier == 1.0:
                weekly[week_key] = weekly.get(week_key, 0.0) + take
            cursor = finish
            remaining -= take

        for index, (piece_start, piece_end, multiplier) in enumerate(pieces):
            base = seg["id"]
            if len(pieces) > 1 or used_ids.get(base):
                seen = used_ids.get(base, 0)
                shift_id = f"{base}-{seen + index + 1}"
            else:
                shift_id = base
            out.append({
                "id": shift_id,
                "employee": seg["employee"],
                "dept": seg["dept"],
                "role": seg["role"],
                "in": _iso(piece_start, utc_offset),
                "out": _iso(piece_end, utc_offset),
                "rate": round(seg["rate"] * multiplier, 4),
            })
        used_ids[seg["id"]] = used_ids.get(seg["id"], 0) + len(pieces)

    out.sort(key=lambda s: (s["in"], s["id"]))
    return out


def to_salaried(register_csv: str) -> list[dict[str, Any]]:
    """Payroll register -> §11 `salaried[]`, one row per department.

    The register is semi-monthly, so two periods make the month (§15).
    """
    totals: dict[str, dict[str, float]] = {}
    for row in csv.DictReader(io.StringIO(register_csv)):
        code = (row.get("Dept_Code") or "").strip()
        dept = DEPT_CODE_MAP.get(code)
        if dept is None:
            _unmapped_depts.add(code)
            continue
        gross = _rate(row.get("Gross_Semi_Monthly"))
        heads = int(_rate(row.get("Employee_Count")) or 0)
        entry = totals.setdefault(dept, {"headcount": 0.0, "monthly": 0.0})
        # Headcount is the same people in both halves of the month, not twice
        # as many, so it is the maximum rather than the sum.
        entry["headcount"] = max(entry["headcount"], float(heads))
        entry["monthly"] += gross

    return [
        {"dept": dept, "headcount": int(v["headcount"]), "monthly": round(v["monthly"], 2)}
        for dept, v in sorted(totals.items())
        if v["monthly"] > 0
    ]
