/**
 * The §7 tally panel. Every figure here is `accruedThrough(t)` — nothing is
 * smoothed or interpolated toward a target, because a rolling number would put
 * a value on screen that the accrual never held. The "tick" is a flash on the
 * lines that changed.
 */

import { useEffect, useRef, useState } from 'react'
import { useSim } from '../store'
import { totalRevenue } from '../sim/accrue'
import { clockLabel } from '../sim/tz'

function useMoney(currency: string) {
  const whole = new Intl.NumberFormat(undefined, {
    style: 'currency', currency, minimumFractionDigits: 0, maximumFractionDigits: 0,
  })
  const plain = new Intl.NumberFormat(undefined, { minimumFractionDigits: 0, maximumFractionDigits: 0 })
  return { whole: (v: number) => whole.format(v), plain: (v: number) => plain.format(v) }
}

function Line({ label, value, indent, strong }: { label: string; value: string; indent?: boolean; strong?: boolean }) {
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
  return (
    <div className={`tally-line${indent ? ' indent' : ''}${strong ? ' strong' : ''}${flash ? ' tick' : ''}`}>
      <span>{label}</span>
      <b>{value}</b>
    </div>
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

      <Line label="Rooms" value={money.whole(r.rooms)} />
      <Line label="Food" value={money.plain(r.food)} />
      <Line label="Beverage" value={money.plain(r.bev)} />
      <Line label="Banquet" value={money.plain(banquet)} />
      <Line label="Food" value={money.plain(r.bqt_food)} indent />
      <Line label="Beverage" value={money.plain(r.bqt_bev)} indent />
      <Line label="Rental" value={money.plain(r.bqt_rental)} indent />
      <Line label="AV" value={money.plain(r.bqt_av)} indent />

      <hr />
      <Line label="Total" value={money.whole(totalRevenue(r))} strong />

      <p className="stats">
        Occ {(stats.occupancy * 100).toFixed(1)}% · ADR {money.whole(stats.adr)} · In-hs {stats.inHouseGuests}
      </p>
    </section>
  )
}
