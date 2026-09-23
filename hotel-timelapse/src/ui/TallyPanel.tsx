/**
 * The §7 tally panel. Every figure here is `accruedThrough(t)` — nothing is
 * smoothed or interpolated toward a target, because a rolling number would put
 * a value on screen that the accrual never held. The "tick" is a flash on the
 * lines that changed.
 *
 * §7 also makes the lines a control: clicking one filters the scene to that
 * department's movement. Clicking it again clears the filter. The room windows
 * keep their own lighting either way — the spec filters movement, and a room
 * going dark because you clicked "Food" would be a lie about the month.
 */

import { useEffect, useRef, useState } from 'react'
import { useSim } from '../store'
import { totalRevenue } from '../sim/accrue'
import type { TallyLine } from '../sim/segments'
import { clockLabel } from '../sim/tz'

function useMoney(currency: string) {
  const whole = new Intl.NumberFormat(undefined, {
    style: 'currency', currency, minimumFractionDigits: 0, maximumFractionDigits: 0,
  })
  const plain = new Intl.NumberFormat(undefined, { minimumFractionDigits: 0, maximumFractionDigits: 0 })
  return { whole: (v: number) => whole.format(v), plain: (v: number) => plain.format(v) }
}

function Line({ label, value, indent, strong, line }: {
  label: string; value: string; indent?: boolean; strong?: boolean; line?: TallyLine
}) {
  const filter = useSim((s) => s.filter)
  const toggleFilter = useSim((s) => s.toggleFilter)
  const prev = useRef(value)
  const [flash, setFlash] = useState(false)
  useEffect(() => {
    if (prev.current !== value) {
      prev.current = value
      setFlash(true)
      const id = setTimeout(() => setFlash(false), 220)
      return () => clearTimeout(id)
    }
  }, [value])

  const on = line !== undefined && filter === line
  const className = `tally-line${indent ? ' indent' : ''}${strong ? ' strong' : ''}` +
                    `${flash ? ' tick' : ''}${on ? ' on' : ''}`
  const body = <><span>{label}</span><b>{value}</b></>

  if (line === undefined) return <div className={className}>{body}</div>
  return (
    <button type="button" className={className} aria-pressed={on}
            title={on ? 'Show all movement' : `Show only ${label.toLowerCase()} movement`}
            onClick={() => toggleFilter(line)}>
      {body}
    </button>
  )
}

export function TallyPanel() {
  const { accrual, property, t } = useSim((s) => ({ accrual: s.accrual, property: s.property, t: s.t }))
  if (!accrual || !property) return null

  const money = useMoney(property.currency)
  const r = accrual.through(t)
  const stats = accrual.stats(t)
  const banquet = r.bqt_food + r.bqt_bev + r.bqt_rental + r.bqt_av

  return (
    <section className="tally" aria-live="polite">
      <header>
        <h2>MTD Revenue</h2>
        <p>{clockLabel(t, accrual.tz)}</p>
      </header>

      <Line label="Rooms" value={money.whole(r.rooms)} line="rooms" />
      <Line label="Food" value={money.plain(r.food)} line="food" />
      <Line label="Beverage" value={money.plain(r.bev)} line="bev" />
      <Line label="Banquet" value={money.plain(banquet)} line="banquet" />
      <Line label="Food" value={money.plain(r.bqt_food)} indent line="banquet" />
      <Line label="Beverage" value={money.plain(r.bqt_bev)} indent line="banquet" />
      <Line label="Rental" value={money.plain(r.bqt_rental)} indent line="banquet" />
      <Line label="AV" value={money.plain(r.bqt_av)} indent line="banquet" />

      <hr />
      <Line label="Total" value={money.whole(totalRevenue(r))} strong />

      <p className="stats">
        Occ {(stats.occupancy * 100).toFixed(1)}% · ADR {money.whole(stats.adr)} · In-hs {stats.inHouseGuests}
      </p>
    </section>
  )
}
