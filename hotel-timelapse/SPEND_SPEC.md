# SPEND_SPEC — build-cost guardrails

What building and running hotel-timelapse is allowed to cost. This is a
constraint on the work, not a description of it. `BUILD_SPEC.md` says what to
build; this file says what you may spend getting there.

## Standing budget

| Line | Ceiling | Notes |
|---|---|---|
| Hosting | **$0/mo** | Static files. GitHub Pages, Netlify or Cloudflare free tier. |
| Domain | $0 | Use the default `*.github.io` host until someone asks otherwise. |
| Runtime services | **$0** | No server, no database, no auth provider, no analytics vendor. |
| Third-party APIs at runtime | **$0** | The page makes no outbound calls. See BUILD_SPEC "Constraints". |
| Paid dependencies / licenses | **$0** | Anything with a license fee needs approval first. |
| Agent spend per build session | **$25** | Soft ceiling. See below. |
| Agent spend, project to date | **$150** | Hard ceiling. Stop and report. |

Anything not on this table has a ceiling of $0 until it's added to it.

## Agent run limits

Per session working on this repo:

- **Soft ceiling $25.** On reaching it: stop, summarise what's done and what's
  left, and ask before continuing. Don't quietly keep going.
- **Hard ceiling $150 project-to-date.** On reaching it: stop and report. No
  exceptions without a human raising the number in this file.
- **No autonomous fan-out.** Don't spawn parallel agents or run multi-agent
  workflows for this project unless a human asks for it in those words. This is
  a single-page static viewer; it does not need a fleet.
- **No scheduled or recurring runs.** No cron, no self-scheduled check-ins that
  outlive the task.
- **Prefer reading the spec over exploring.** The specs are short by design.
  Re-reading `BUILD_SPEC.md` costs less than searching the tree.

## Rules that keep the cost at zero

1. **No dependencies.** Every added package is a supply chain, an audit, an
   upgrade treadmill, and eventually a bill. The chart is a few dozen lines of
   SVG. Write the few dozen lines.
2. **No build step.** No bundler, no transpiler, no CI minutes spent compiling.
   The deployable artifact is the source.
3. **No backend.** The moment this needs a server it has a monthly bill, a
   patching schedule and an on-call story. Data arrives as a file.
4. **No live data integration in v1.** PMS/RMS/STR feeds are per-call money and
   a compliance conversation. Not in this version.
5. **No telemetry.** No analytics script, no error-reporting SaaS, no pixel.
   Free tiers become invoices, and this page has no business phoning home.

## Data spend — the part that isn't money

Real property data has a cost that doesn't show up on an invoice.

- **No real operating data in this repository. Ever.** Not in commits, not in
  fixtures, not in an issue, not "temporarily".
- The demo data is fabricated by `prep/gen_synthetic_month.py` and carries
  `"synthetic": true` plus an on-page badge.
- The demo property is fictional. It is not named after, and its room count and
  results do not mirror, any real hotel.
- `.gitignore` excludes `public/property.*.json` except the demo file, so a real
  export dropped in `public/` is not committed by accident. Don't defeat this.

## Raising a ceiling

Edit this file in a commit of its own that says what's going up, by how much,
and why. A number changed in passing inside a feature commit doesn't count as
approval.
