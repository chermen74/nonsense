"""Data Plus AP invoice export and GL accrual journal -> SPEND_SPEC §11
`expenses[]`.

Two files feed one list (§15):

* the AP invoice export, which becomes `timing: "invoice"` records dated on the
  **invoice** date rather than the post date -- the hotel incurred the cost when
  the vendor delivered, not when accounting keyed it;
* the month-end GL journal, which becomes `timing: "accrual"` records for
  everything the GL knows about that AP and payroll do not explain.

Real-export handling:

* GL accounts are `<account>-<dept>`; the account half maps to a §11 category
  and the department half to a layout.json department id, both through config
  dicts rather than string matching.
* An unknown account falls into MISC. §11 is explicit: never drop a record,
  because a dropped cost is a month that does not tie.
* Money arrives formatted, and credit memos arrive in parentheses.
* Voided and reclass entries share the files and are filtered (§15).
"""

from __future__ import annotations

import csv
import io
import re
from datetime import datetime
from typing import Any

# --- property-specific configuration ---

#: GL account number -> SPEND_SPEC §11 Other Expense category
GL_ACCOUNT_MAP: dict[str, str] = {}

#: Data Plus department code -> layout.json departments[].id
DEPT_CODE_MAP: dict[str, str] = {}

#: §11's category list. Anything else becomes MISC.
CATEGORIES = frozenset({
    "GUEST_SUPPLIES", "CLEANING_SUPPLIES", "LINEN", "LAUNDRY", "COMMISSIONS",
    "OTA_FEES", "CHINA_GLASS", "MENU_PAPER", "KITCHEN_SUPPLIES", "R&M",
    "CONTRACT_SERVICES", "ELECTRIC", "GAS", "WATER", "MARKETING", "IT",
    "CREDIT_CARD_FEES", "MISC",
})

POSTED_STATUSES = frozenset({"posted", "paid", "open", "approved"})
#: §15: reclass entries move a cost between accounts; they are not new cost.
EXCLUDED_JE_TYPES = frozenset({"reclass", "reversal", "void"})

_unmapped_accounts: set[str] = set()
_unmapped_depts: set[str] = set()


def unmapped() -> set[str]:
    return {f"account {a}" for a in _unmapped_accounts} | {f"dept {d}" for d in _unmapped_depts}


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


def _day(raw: str) -> str | None:
    """`08/11/2026` or `2026-08-11` -> `2026-08-11`."""
    text = (raw or "").strip()
    if not text:
        return None
    for fmt in ("%m/%d/%Y", "%Y-%m-%d", "%m/%d/%y", "%d-%b-%Y"):
        try:
            return datetime.strptime(text, fmt).date().isoformat()
        except ValueError:
            continue
    return None


def _split_account(raw: str) -> tuple[str, str]:
    """`6220-300` -> (`6220`, `300`). Tolerates a bare account."""
    text = (raw or "").strip()
    if "-" in text:
        account, _, dept = text.partition("-")
        return account.strip(), dept.strip()
    return text, ""


def _category(account: str) -> str:
    mapped = GL_ACCOUNT_MAP.get(account)
    if mapped is None:
        _unmapped_accounts.add(account)
        return "MISC"
    return mapped if mapped in CATEGORIES else "MISC"


def _dept(code: str, fallback: str) -> str | None:
    for candidate in (code, fallback):
        if not candidate:
            continue
        mapped = DEPT_CODE_MAP.get(candidate)
        if mapped is not None:
            return mapped
    _unmapped_depts.add(code or fallback)
    return None


def to_expenses(
    ap_csv: str,
    accrual_csv: str = "",
    *,
    utc_offset: str,
    period_start: str | None = None,
    period_end: str | None = None,
) -> list[dict[str, Any]]:
    out: list[dict[str, Any]] = []

    def in_period(day: str) -> bool:
        if period_start and day < period_start:
            return False
        if period_end and day >= period_end:
            return False
        return True

    for row in csv.DictReader(io.StringIO(ap_csv)):
        status = (row.get("Status") or "POSTED").strip().lower()
        if status not in POSTED_STATUSES:
            continue
        # §15: invoice date, not post date.
        day = _day(row.get("Invoice_Date", ""))
        if day is None or not in_period(day):
            continue
        account, account_dept = _split_account(row.get("GL_Account", ""))
        dept = _dept((row.get("Dept_Code") or "").strip(), account_dept)
        if dept is None:
            continue
        amount = _money(row.get("Amount"))
        if amount == 0:
            continue
        out.append({
            "id": (row.get("Invoice_Number") or "").strip(),
            "dept": dept,
            "category": _category(account),
            "vendor": (row.get("Vendor_Name") or "").strip(),
            "date": f"{day}T09:00:00{utc_offset}",
            "amount": amount,
            "timing": "invoice",
        })

    for row in csv.DictReader(io.StringIO(accrual_csv or "")):
        kind = (row.get("Type") or "ACCRUAL").strip().lower()
        if kind in EXCLUDED_JE_TYPES:
            continue
        day = _day(row.get("JE_Date", ""))
        if day is None or not in_period(day):
            continue
        account, account_dept = _split_account(row.get("GL_Account", ""))
        dept = _dept((row.get("Dept_Code") or "").strip(), account_dept)
        if dept is None:
            continue
        amount = _money(row.get("Amount"))
        if amount == 0:
            continue
        out.append({
            "id": (row.get("JE_Number") or "").strip(),
            "dept": dept,
            "category": _category(account),
            "vendor": (row.get("Description") or "").strip(),
            # §12: an accrual lands at the last instant of its day, which for a
            # month-end journal is the month-end wave the waterfall shows.
            "date": f"{day}T23:59:00{utc_offset}",
            "amount": amount,
            "timing": "accrual",
        })

    out.sort(key=lambda e: (e["date"], e["id"]))
    return out
