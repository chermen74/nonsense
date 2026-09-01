# KICKOFF_PROMPT

Paste this into a fresh agent session in this repository to start the v1 build.
It assumes nothing has been built yet beyond the scaffold.

---

Build v1 of hotel-timelapse.

Read `BUILD_SPEC.md` and `SPEND_SPEC.md` first, in full. `BUILD_SPEC.md` is the
requirement; `SPEND_SPEC.md` is binding on how you work, not advisory. Then read
`prep/gen_synthetic_month.py` — its output is the data contract — and look at
`public/property.demo.json` to see a real instance of it.

Build the static viewer described in `BUILD_SPEC.md`:

- `public/index.html`, `public/app.js`, `public/styles.css`. ES modules, no
  framework, no bundler, no CDN, no `npm install`. Opening `public/index.html`
  from disk must work.
- The chart is hand-written SVG or canvas. Do not add a charting library.
- Implement the transport, the day readout, the series switch, the annotations,
  the keyboard controls, the error states and the "Demo data" badge, all as
  specified.
- Validate `schema` and the four data invariants on load; fail loudly and legibly
  on the page if any of them break.

Constraints while you work:

- Do not install anything. Do not add a `package.json`.
- Do not touch `prep/gen_synthetic_month.py`'s output shape. If you believe the
  schema is wrong, say so and stop — don't fork the contract.
- Do not commit real hotel data, and don't invent data that could be mistaken
  for a real property's results.
- Don't spawn parallel agents or workflows for this.
- Stop and report at the session ceiling in `SPEND_SPEC.md`.

Verify before you call it done, and show the output:

1. `python3 prep/gen_synthetic_month.py --out /tmp/regen.json` then diff against
   `public/property.demo.json` — must be identical.
2. Load `public/index.html` in a browser, play the month start to finish, and
   screenshot at least the mid-playback state.
3. Corrupt a copy of the JSON, load it, and show the error state.
4. Confirm the page issues no network requests.

Commit in logical steps with messages that say what changed and why. Then stop
and report: what's built, what's verified, what you'd do next.
