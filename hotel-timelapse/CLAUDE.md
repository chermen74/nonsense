# CLAUDE.md

Conventions for agents working in this repository. Read `BUILD_SPEC.md` for what
the project is and `SPEND_SPEC.md` for what you may spend building it — both are
short, and both are binding.

## What this repo is

A static, dependency-free web viewer that plays a hotel property's daily
operating data as a timelapse. One JSON file in, one animated month out. No
server, no build step, no packages.

## Layout

```
CLAUDE.md          this file
BUILD_SPEC.md      the requirement
SPEND_SPEC.md      cost and conduct guardrails
KICKOFF_PROMPT.md  the prompt that starts a build session
prep/              data preparation; Python 3.11+, standard library only
public/            the deployable static site
```

## Hard rules

1. **No real hotel data in this repository.** Not in commits, fixtures, issues
   or branches. The only data file that ships is the fabricated demo, generated
   by `prep/gen_synthetic_month.py` and marked `"synthetic": true`.
2. **No runtime dependencies.** No npm packages, no CDN links, no charting
   library, no fonts fetched over the network. If it needs installing, it
   doesn't belong here.
3. **No build step.** `public/` is the source and the artifact. Opening
   `public/index.html` from disk must work.
4. **No backend, no telemetry, no outbound requests** from the page.
5. **The generator defines the data contract.** If code and `BUILD_SPEC.md`
   disagree about the schema, `prep/gen_synthetic_month.py` is right — change
   the spec deliberately, in its own commit, not by drift.

## Working here

- `prep/` is Python 3.11+, stdlib only. Type hints, docstrings on public
  functions, `argparse` for entry points, deterministic given a `--seed`.
- `public/` is plain ES modules. No transpilation, so no syntax newer than
  baseline-available. Two-space indent, single quotes, no semicolon golf.
- Keep the specs current. A behaviour change that contradicts `BUILD_SPEC.md`
  isn't done until the spec is updated in the same change.
- Comment sparingly, and only about *why*. The code says what.

## Regenerating the demo data

```bash
python3 prep/gen_synthetic_month.py                       # rewrites public/property.demo.json
python3 prep/gen_synthetic_month.py --month 2026-12 --seed 7 --out /tmp/dec.json
```

The default invocation is deterministic and must reproduce the committed file
byte for byte. If it doesn't, that's a bug in the generator, not a reason to
commit the new output.

## Verifying a change

There's no test runner yet. Before calling anything done:

```bash
python3 prep/gen_synthetic_month.py --out /tmp/regen.json
diff /tmp/regen.json public/property.demo.json          # must be empty
```

Then open `public/index.html` in a browser, play a full month, and confirm the
page makes no network requests.

## Out of scope for now

Live PMS/RMS/STR integrations, multi-property comparison, forecasting, budget
variance, user accounts, and photo/camera timelapse. Some of these may come
later; none of them are v1, and none should be started without being asked for.
