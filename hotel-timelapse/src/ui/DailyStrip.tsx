/**
 * §14's daily-net bar strip — build order §16 step 14.
 *
 * "Green above zero, red below, so the user sees which days were profitable
 * and which weren't (banquet nights vs. Monday breakfasts)", and §14 puts it
 * along the timeline scrubber rather than in the panel: a day's bar sits
 * under the part of the scrub track that reaches it, so the shape of the
 * month and the position of the clock are read together.
 *
 * The strip appears when a department is open, because a daily net is a
 * department's net. Hovering a day gives §14's mini-P&L; clicking one jumps
 * the clock there, which is what you want the instant you see a red bar.
 *
 * Every figure comes off the same curves the panel reads — a day's net is
 * `dept(id, dayEnd) − dept(id, dayStart)` — so the strip cannot disagree with
 * the bars above it.
 */

import { useState } from 'react'
import { useSim } from '../store'
import type { DayCell, DeptProfile } from '../sim/profile'

export function DailyStrip() {
  const zoom = useSim((s) => s.zoom)
  const profiles = useSim((s) => s.profiles)
  const property = useSim((s) => s.property)
  const setT = useSim((s) => s.setT)
  const pause = useSim((s) => s.pause)
  const [open, setOpen] = useState<DayCell | null>(null)

  const profile: DeptProfile | null = zoom ? profiles?.get(zoom) ?? null : null
  if (!profile || !property) return null
  const money = new Intl.NumberFormat(undefined, {
    style: 'currency', currency: property.currency, maximumFractionDigits: 0,
  })
  const signed = (v: number) => (v < -0.5 ? `(${money.format(-v)})` : money.format(v))

  return (
    <div className="strip" onPointerLeave={() => setOpen(null)}>
      <div className="strip-bars">
        {profile.days.map((day) => {
          const share = profile.peakNet > 0
            ? Math.abs(day.lines.profit) / profile.peakNet : 0
          const up = day.lines.profit >= 0
          return (
            <button key={day.date} type="button"
                    className={`strip-day ${up ? 'up' : 'down'}${open === day ? ' on' : ''}`}
                    aria-label={`${day.date}: ${signed(day.lines.profit)}`}
                    onPointerEnter={() => setOpen(day)}
                    onFocus={() => setOpen(day)}
                    onClick={() => { pause(); setT(day.from) }}>
              <i style={{ height: `${Math.max(share * 50, 1.5)}%` }} />
            </button>
          )
        })}
      </div>
      {open && (
        <div className="strip-day-card">
          <strong>{open.date}</strong>
          <span>Revenue<b>{money.format(open.lines.revenue)}</b></span>
          <span>Cost<b>{money.format(open.lines.cos + open.lines.labor + open.lines.other)}</b></span>
          <span>Net<b className={open.lines.profit < -0.5 ? 'neg' : ''}>
            {signed(open.lines.profit)}</b></span>
          <span>Productivity<b>
            {open.driver > 0 ? `${(open.hours / open.driver).toFixed(2)} h` : `${open.hours.toFixed(0)} h`}
            {open.driver > 0 ? ` / ${profile.driverLabel.replace(/s$/, '')}` : ''}
          </b></span>
        </div>
      )}
    </div>
  )
}
