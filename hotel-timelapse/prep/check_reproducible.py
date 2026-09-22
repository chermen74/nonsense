#!/usr/bin/env python3
"""The committed demo month must be exactly what the generator produces.

Guards against a hand-edited data file: if someone tweaks a number in
public/data/2026-08.json, it no longer matches its generator and this fails.
`meta.generated_at` is the one field excluded, since it is a wall-clock stamp.

    python3 prep/check_reproducible.py [--month 2026-08]
"""

from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def strip_volatile(doc: dict) -> dict:
    doc = json.loads(json.dumps(doc))
    doc.get("meta", {}).pop("generated_at", None)
    return doc


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--month", default="2026-08")
    args = ap.parse_args()

    committed_path = ROOT / "public" / "data" / f"{args.month}.json"
    if not committed_path.exists():
        print(f"{committed_path} is missing", file=sys.stderr)
        return 1
    committed = json.loads(committed_path.read_text())

    with tempfile.TemporaryDirectory() as tmp:
        staging = Path(tmp) / "public"
        staging.mkdir()
        for name in ("layout.json", "property.demo.json"):
            shutil.copy(ROOT / "public" / name, staging / name)

        result = subprocess.run(
            [sys.executable, str(ROOT / "prep" / "gen_synthetic_month.py"),
             "--month", args.month, "--out", str(staging)],
            capture_output=True, text=True,
        )
        if result.returncode != 0:
            print(result.stdout + result.stderr, file=sys.stderr)
            return result.returncode

        regenerated = json.loads((staging / "data" / f"{args.month}.json").read_text())

    if strip_volatile(committed) != strip_volatile(regenerated):
        print(f"{committed_path.name} is NOT what the generator produces.", file=sys.stderr)
        for key in ("stays", "checks", "events"):
            a, b = len(committed.get(key, [])), len(regenerated.get(key, []))
            if a != b:
                print(f"  {key}: committed {a}, regenerated {b}", file=sys.stderr)
        print("  Re-run: python3 prep/gen_synthetic_month.py --month "
              f"{args.month} --out public", file=sys.stderr)
        return 1

    print(f"{committed_path.name} reproduces exactly from the generator "
          f"({len(committed['stays'])} stays, {len(committed['checks'])} checks, "
          f"{len(committed['events'])} events).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
