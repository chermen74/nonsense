"""Toast orders -> BUILD_SPEC §3 `checks[]`.

Input is the shape returned by `GET /orders/v2/ordersBulk`, looped over
business dates (§8). One Toast *order* carries one or more *checks*; §3's
record is per order, so the checks are summed.

Real-export handling:

* Toast timestamps are UTC with a `+0000` offset; §3 wants local hotel time.
* Voided orders and voided selections are present in the payload and must not
  be counted as revenue.
* The food/bev split comes from `selections[].salesCategory`, which is
  property-configured -- hence the mapping table rather than a string test.
* A room-charge tender carries the room number, which is what links a check to
  a stay (§6.4). Any other tender is a walk-in, `room: null`.
* An order open at the business-date rollover has no `closedDate` yet.
"""

from __future__ import annotations

from datetime import datetime, timezone
from typing import Any, Iterable
from zoneinfo import ZoneInfo

# --- property-specific configuration ---

#: Toast restaurant GUID -> the outlet id in layout.json.
RESTAURANT_GUID_TO_OUTLET: dict[str, str] = {}

#: salesCategory name -> "food" | "bev". Unmapped categories are reported by
#: `unmapped()` and counted as food, so revenue is never silently dropped.
SALES_CATEGORY_MAP: dict[str, str] = {
    "food": "food", "entree": "food", "appetizer": "food", "dessert": "food",
    "breakfast": "food", "kids": "food", "retail": "food",
    "beverage": "bev", "bev": "bev", "liquor": "bev", "beer": "bev",
    "wine": "bev", "cocktail": "bev", "na beverage": "bev", "coffee": "bev",
}

ROOM_CHARGE_TENDERS = frozenset({"room charge", "roomcharge", "charge to room"})

_unmapped_categories: set[str] = set()


def unmapped() -> set[str]:
    return set(_unmapped_categories)


def _local(ts: str, tz: ZoneInfo) -> str:
    """Toast's `2026-08-14T18:05:00.000+0000` -> local ISO 8601 with offset."""
    cleaned = ts.replace("Z", "+0000")
    if "." in cleaned:
        head, tail = cleaned.split(".", 1)
        offset = tail[3:] if len(tail) > 3 else "+0000"
        cleaned = head + offset
    if len(cleaned) >= 5 and cleaned[-5] in "+-" and ":" not in cleaned[-5:]:
        cleaned = cleaned[:-2] + ":" + cleaned[-2:]
    return datetime.fromisoformat(cleaned).astimezone(tz).isoformat()


def _orders(payload: Any) -> Iterable[dict]:
    if isinstance(payload, list):
        return payload
    return payload.get("orders", [])


def _outlet(order: dict) -> str | None:
    guid = order.get("restaurantGuid") or (order.get("restaurant") or {}).get("guid")
    if guid in RESTAURANT_GUID_TO_OUTLET:
        return RESTAURANT_GUID_TO_OUTLET[guid]
    return order.get("outletId")          # synthetic exports carry it directly


def _split(order: dict) -> tuple[float, float, str | None]:
    food = bev = 0.0
    room: str | None = None

    for check in order.get("checks", []):
        if check.get("voided"):
            continue
        for sel in check.get("selections", []):
            if sel.get("voided"):
                continue
            category = ((sel.get("salesCategory") or {}).get("name") or "").strip().lower()
            bucket = SALES_CATEGORY_MAP.get(category)
            if bucket is None:
                if category:
                    _unmapped_categories.add(category)
                bucket = "food"
            amount = float(sel.get("price", 0.0) or 0.0)
            if bucket == "bev":
                bev += amount
            else:
                food += amount

        for payment in check.get("payments", []):
            name = ((payment.get("otherPayment") or {}).get("name") or "").strip().lower()
            if name in ROOM_CHARGE_TENDERS:
                room = str(payment.get("roomNumber") or payment.get("note") or "").strip() or None

    return round(food, 2), round(bev, 2), room


def to_checks(payload: Any, *, timezone_name: str) -> list[dict]:
    tz = ZoneInfo(timezone_name)
    checks: list[dict] = []

    for order in _orders(payload):
        if order.get("voided") or order.get("deleted"):
            continue
        if not order.get("closedDate"):
            continue                                   # still open at rollover
        outlet = _outlet(order)
        if not outlet:
            continue

        food, bev, room = _split(order)
        if food == 0.0 and bev == 0.0:
            continue                                   # fully voided / comped to zero

        checks.append({
            "id": order.get("guid", ""),
            "outlet": outlet,
            "opened": _local(order["openedDate"], tz),
            "closed": _local(order["closedDate"], tz),
            "covers": int(order.get("numberOfGuests", 1) or 1),
            "food": food,
            "bev": bev,
            "room": room,
        })

    checks.sort(key=lambda c: (c["closed"], c["id"]))
    return checks
