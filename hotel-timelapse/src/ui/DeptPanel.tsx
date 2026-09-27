/**
 * §14's department zoom — build order §16 step 14.
 *
 * The four lines as bars against the department's own biggest number, the
 * profit as the visible residual, and the two secondary readouts §14 asks for:
 * labor hours against whatever the department turns, day by day, and the
 * month's daily net as a strip.
 *
 * Like the global waterfall this paints itself. React renders the skeleton
 * once and a store subscription writes transforms and text onto the nodes, so
 * the bars extend on every frame of a 600× play-through without a single
 * reconciliation. The sparkline and the strip are the exception: they are
 * whole-month shapes that do not depend on `t`, so they are drawn once as SVG
 * and only the "you are here" marker moves.
 *
 * §14 asks the hover to name "the top vendors/roles behind it MTD", which is
 * the one place a number on screen is not on the panel's own curves — it is on
 * the contributor curves in `profile.ts`, built from the same records. They
 * sum to the line they break down, which the tests hold them to.
 */

import { useEffect, useMemo, useRef, useState } from 'react'
import { useSim } from '../store'
import { clockLabel } from '../sim/tz'
import type { CostLine, DayCell, DeptProfile } from '../sim/profile'

const LINES: Array<{ key: CostLine; label: string; cost: boolean }> = [
  { key: 'revenue', label: 'Revenue', cost: false },
  { key: 'cos', label: 'Cost of sales', cost: true },
  { key: 'labor', label: 'Labor', cost: true },
  { key: 'other', label: 'Other expense', cost: true },
]

function setText(el: HTMLElement | null, text: string) {
  if (el && el.textContent !== text) el.textContent = text
}

export function DeptPanel({ id }: { id: string }) {
  const costs = useSim((s) => s.costs)
  const accrual = useSim((s) => s.accrual)
  const property = useSim((s) => s.property)
  const profiles = useSim((s) => s.profiles)
  const setZoom = useSim((s) => s.setZoom)
  const profile = profiles?.get(id) ?? null

  /** Which bar the pointer is on, for §14's contributor list. */
  const [openLine, setOpenLine] = useState<CostLine | null>(null)
  const bars = useRef(new Map<CostLine, HTMLElement | null>())
  const figures = useRef(new Map<CostLine, HTMLElement | null>())
  const clockEl = useRef<HTMLParagraphElement>(null)
  const profitBar = useRef<HTMLElement>(null)
  const profitEl = useRef<HTMLElement>(null)
  const marginEl = useRef<HTMLElement>(null)
  const nowEl = useRef<SVGLineElement>(null)
  const listEl = useRef<HTMLUListElement>(null)

  const money = useMemo(() => property
    ? new Intl.NumberFormat(undefined, {
        style: 'currency', currency: property.currency, maximumFractionDigits: 0,
      })
    : null, [property])

  useEffect(() => {
    if (!costs || !accrual || !profile || !money) return
    // One scale for the whole panel, fixed at what the department ends the
    // month with, so the bars grow instead of renormalising every frame.
    const file = costs.fileTotals.get(id)
    const scale = Math.max(
      file?.revenue ?? 0,
      (file?.cos ?? 0) + (file?.labor ?? 0) + (file?.other ?? 0),
      1,
    )
    const span = accrual.periodEnd - accrual.periodStart

    const paint = (t: number) => {
      setText(clockEl.current, `${clockLabel(t, accrual.tz)} · MTD`)
      const lines = costs.dept(id, t)
      for (const { key } of LINES) {
        const bar = bars.current.get(key)
        if (bar) bar.style.transform = `scaleX(${Math.min(lines[key] / scale, 1).toFixed(5)})`
        const fig = figures.current.get(key)
        if (fig) {
          // A line a department does not have reads as a dash, the way a P&L
          // prints it, rather than as a bracketed zero.
          setText(fig, lines[key] < 0.5 ? '—'
            : key === 'revenue' ? money.format(lines.revenue)
            : `(${money.format(lines[key])})`)
        }
      }
      if (profitBar.current) {
        profitBar.current.style.transform =
          `scaleX(${Math.min(Math.abs(lines.profit) / scale, 1).toFixed(5)})`
        profitBar.current.classList.toggle('neg', lines.profit < -0.5)
      }
      setText(profitEl.current, lines.profit < -0.5
        ? `(${money.format(-lines.profit)})` : money.format(lines.profit))
      profitEl.current?.classList.toggle('neg', lines.profit < -0.5)
      setText(marginEl.current, lines.revenue > 0
        ? `${((lines.profit / lines.revenue) * 100).toFixed(1)}%` : '—')
      if (nowEl.current) {
        const x = ((t - accrual.periodStart) / span) * 100
        nowEl.current.setAttribute('x1', String(x))
        nowEl.current.setAttribute('x2', String(x))
      }
      // §14's hover: the names behind the bar the pointer is on.
      if (listEl.current && openLine) {
        const rows = profile.contributors(openLine, t, 5)
        const want = rows.map((r) => `${r.name} ${money.format(r.amount)}`).join('|')
        if (listEl.current.dataset.shown !== want) {
          listEl.current.dataset.shown = want
          listEl.current.replaceChildren(...rows.map((r) => {
            const li = document.createElement('li')
            const name = document.createElement('span')
            name.textContent = r.name
            const amount = document.createElement('b')
            amount.textContent = money.format(r.amount)
            li.append(name, amount)
            return li
          }))
        }
      }
    }

    paint(useSim.getState().t)
    let last = useSim.getState().t
    return useSim.subscribe((s) => {
      if (s.t === last) return
      last = s.t
      paint(s.t)
    })
  }, [costs, accrual, profile, money, id, openLine])

  if (!costs || !accrual || !profile || !money) return null

  return (
    <section className="tally dept" aria-live="off">
      <header>
        <h2 title={profile.name}>{profile.name}</h2>
        <button type="button" className="dept-back" onClick={() => setZoom(null)}>
          ← All departments
        </button>
      </header>
      <p className="dept-clock" ref={clockEl} />

      <ol className="dept-lines">
        {LINES.map(({ key, label, cost }) => (
          <li key={key}
              className={`dept-line ${cost ? 'cost' : 'rev'}${openLine === key ? ' open' : ''}`}
              onPointerEnter={() => setOpenLine(key)}
              onPointerLeave={() => setOpenLine((l) => (l === key ? null : l))}>
            <span className="dept-label">{label}</span>
            <div className="dept-track">
              <i className={`dept-bar ${key}`} ref={(el) => { bars.current.set(key, el) }} />
            </div>
            <b ref={(el) => { figures.current.set(key, el) }} />
          </li>
        ))}
        {/* §14's own mock puts the margin to the right of the figure. */}
        <li className="dept-line profit">
          <span className="dept-label">Dept profit</span>
          <div className="dept-track">
            <i className="dept-bar profit" ref={profitBar} />
          </div>
          <b ref={profitEl} />
          <em ref={marginEl} />
        </li>
      </ol>

      {/* §14: "hover any bar → the top vendors/roles behind it MTD". */}
      <ul className="dept-who" ref={listEl} hidden />

      <Spark profile={profile} nowRef={nowEl} />
      {/* §14's daily-net strip runs along the timeline scrubber, in the
          transport, where the days it draws line up with the days you scrub
          through. It is <DailyStrip />. */}
    </section>
  )
}

/**
 * §14's productivity sparkline: labor hours against what the department turns,
 * day by day. Two lines on two scales on purpose — the shape of the gap is the
 * reading, not the absolute heights, and forcing hours and covers onto one
 * axis would make one of them a flat line.
 */
function Spark({ profile, nowRef }: {
  profile: DeptProfile
  nowRef: React.Ref<SVGLineElement>
}) {
  const { days, peakHours, peakDriver, driverLabel } = profile
  const path = (pick: (d: DayCell) => number, peak: number) => days
    .map((d, i) => `${i === 0 ? 'M' : 'L'}${(((i + 0.5) / days.length) * 100).toFixed(2)} ${
      (peak > 0 ? 24 - (pick(d) / peak) * 22 : 24).toFixed(2)}`)
    .join(' ')

  return (
    <figure className="dept-spark">
      <figcaption>Labor hours vs {driverLabel}</figcaption>
      <svg viewBox="0 0 100 26" preserveAspectRatio="none" role="img"
           aria-label={`Labor hours and ${driverLabel} by day`}>
        <path className="spark-driver" d={path((d) => d.driver, peakDriver)} />
        <path className="spark-hours" d={path((d) => d.hours, peakHours)} />
        <line ref={nowRef} className="spark-now" x1="0" x2="0" y1="0" y2="26" />
      </svg>
      <span className="spark-key">
        <i className="hours" />hours&nbsp;&nbsp;<i className="driver" />{driverLabel}
      </span>
    </figure>
  )
}
