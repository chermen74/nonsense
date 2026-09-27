"""Opera Cloud (OHIP) reservations -> BUILD_SPEC §3 `stays[]`.

Input is the shape returned by the OHIP reservation search,
`GET /rsv/v1/hotels/{hotelId}/reservations`, or the equivalent Back Office
`RV_NA` + reservation detail export flattened to the same structure.

Per §8 the nightly rate comes from `roomStay.roomRates[]` by date, never from
folio postings -- postings mix in packages, taxes and adjustments, so they do
not reconstruct the rate actually charged for a night.

The messy parts this handles, all of which real exports contain:

* A `roomRates[]` entry covers a *date range*, not a night. A three-night stay
  at one rate arrives as a single entry and has to be expanded per night.
* Cancellations and no-shows come back in the same payload as arrivals.
* Children are counted separately from adults.
* Market codes are property-configured, so they are mapped, not trusted.
* A reservation may have no room assigned yet.
* Day-use reservations have arrival == departure and occupy no night.
* A monthly extract includes stays that straddle the month boundary; only the
  nights inside the period belong in that month's file.
"""

from __future__ import annotations

from datetime import date, datetime, timedelta
from typing import Any, Iterable

# --- property-specific configuration (§8: mappings stay in adapter config) ---

#: Opera market codes -> the three §3 buckets. A new property extends this map;
#: anything unmapped falls back to TRANSIENT and is reported by `unmapped()`.
MARKET_CODE_MAP: dict[str, str] = {
    "TRANS": "TRANSIENT", "BAR": "TRANSIENT", "LEIS": "TRANSIENT",
    "PKG": "TRANSIENT", "OTA": "TRANSIENT", "DISC": "TRANSIENT",
    "GRPC": "GROUP", "GRPS": "GROUP", "WED": "GROUP", "TOUR": "GROUP",
    "CORP": "CONTRACT", "NEG": "CONTRACT", "CREW": "CONTRACT", "GOVT": "CONTRACT",
}

#: Reservation statuses that represent a stay that actually happened. §8:
#: "Filter to CHECKED_OUT and in-house at period end."
OCCUPYING_STATUSES = frozenset({"CheckedOut", "InHouse", "Checkedout", "CHECKED_OUT", "IN_HOUSE"})

_unmapped_markets: set[str] = set()


def unmapped() -> set[str]:
    """Market codes seen but absent from MARKET_CODE_MAP, for the tie-out log."""
    return set(_unmapped_markets)


def _iso_date(value: str) -> date:
    return date.fromisoformat(value[:10])


def _reservations(payload: Any) -> Iterable[dict]:
    """Accept the full OHIP envelope, the inner list, or a bare list."""
    if isinstance(payload, list):
        return payload
    if "reservations" in payload:
        return payload["reservations"].get("reservationInfo", [])
    return payload.get("reservationInfo", [])


def _confirmation(res: dict) -> str:
    for entry in res.get("reservationIdList", []):
        if entry.get("type") == "Confirmation":
            return str(entry.get("id"))
    for entry in res.get("reservationIdList", []):
        return str(entry.get("id"))
    return "UNKNOWN"


def _guest_count(room_stay: dict) -> int:
    counts = room_stay.get("guestCounts", room_stay)
    adults = int(counts.get("adultCount", 0) or 0)
    children = int(counts.get("childCount", 0) or 0)
    return max(adults + children, 1)


def _market(room_stay: dict) -> str:
    raw = (room_stay.get("marketCode") or "").upper()
    mapped = MARKET_CODE_MAP.get(raw)
    if mapped is None:
        if raw:
            _unmapped_markets.add(raw)
        return "TRANSIENT"
    return mapped


def _nights(room_stay: dict) -> list[dict]:
    """Expand every roomRates[] range into one entry per occupied night.

    OHIP's `end` is the departure side of the range, so the night of `end`
    itself is not occupied by that entry.
    """
    out: list[dict] = []
    for rate in room_stay.get("roomRates", []):
        start = _iso_date(rate["start"])
        end = _iso_date(rate["end"])
        if end <= start:
            end = start + timedelta(days=1)   # single-night entries sometimes repeat the date
        amount = rate.get("total", {}).get("amountBeforeTax")
        if amount is None:
            amount = rate.get("rateAmount", {}).get("amount", 0.0)
        night = start
        while night < end:
            out.append({"date": night.isoformat(), "rate": round(float(amount), 2)})
            night += timedelta(days=1)
    out.sort(key=lambda n: n["date"])
    return out


def to_stays(
    payload: Any,
    *,
    period_start: date | None = None,
    period_end: date | None = None,
) -> list[dict]:
    """Convert an OHIP reservation payload into §3 `stays[]`.

    `period_start`/`period_end` clip `nights` to the business month, the way a
    monthly extract does; a guest already in house on the first night keeps its
    real arrival timestamp so occupancy is right from the first frame.
    """
    stays: list[dict] = []

    for res in _reservations(payload):
        room_stay = res.get("roomStay", {})

        if room_stay.get("reservationStatus") not in OCCUPYING_STATUSES:
            continue                                  # cancelled, no-show, still reserved

        room = room_stay.get("roomId") or room_stay.get("roomNumber")
        if not room:
            continue                                  # never assigned a room

        times = room_stay.get("actualTimes", {})
        arrive = times.get("actualArrivalTime")
        depart = times.get("actualDepartureTime")
        if not arrive or not depart:
            continue                                  # no actual times: not a completed stay

        all_nights = _nights(room_stay)
        nights = all_nights
        if period_start and period_end:
            lo, hi = period_start.isoformat(), period_end.isoformat()
            nights = [n for n in all_nights if lo <= n["date"] < hi]

        if not nights:
            if not all_nights:
                continue                              # day use: never occupies a night
            # A stay can overlap the period without owning a night inside it:
            # a guest checking out on the first morning slept the last night of
            # the previous month. They carry no revenue for this month, but they
            # are in the hotel when the replay opens and they walk out through
            # the lobby, so occupancy and departure movement need them. `nights`
            # stays empty -- the revenue is the previous month's.
            overlaps = arrive[:10] < hi and depart[:10] >= lo
            if not overlaps:
                continue

        stays.append({
            "id": _confirmation(res),
            "room": str(room),
            "arrive": arrive,
            "depart": depart,
            "guests": _guest_count(room_stay),
            "market": _market(room_stay),
            "nights": nights,
        })

    stays.sort(key=lambda s: (s["arrive"], s["id"]))
    return stays
