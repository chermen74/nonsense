/**
 * accruedThrough(t) — BUILD_SPEC §4.
 *
 * Every accruing line is a set of ramps: `amount` spread linearly from `t0` to
 * `t1`, or landing whole at `t0` when the two are equal. Ramps are compiled once
 * into a piecewise-linear cumulative curve, so the tally at time t is a binary
 * search plus one multiply — never a scan of 40k records.
 */

import type { MonthData } from '../types'
import { wallClock } from './tz'

export interface Ramp { t0: number; t1: number; amount: number }

/** A compiled piecewise-linear cumulative curve. */
export class Curve {
  private readonly times: Float64Array
  private readonly cum: Float64Array
  private readonly slope: Float64Array
  readonly total: number

  private constructor(times: Float64Array, cum: Float64Array, slope: Float64Array, total: number) {
    this.times = times
    this.cum = cum
    this.slope = slope
    this.total = total
  }

  static build(ramps: Ramp[]): Curve {
    const steps = new Map<number, number>()   // instant amounts at a time
    const slopes = new Map<number, number>()  // slope added/removed at a time
    let total = 0

    for (const r of ramps) {
      if (r.amount === 0) continue
      total += r.amount
      if (r.t1 <= r.t0) {
        steps.set(r.t0, (steps.get(r.t0) ?? 0) + r.amount)
      } else {
        const per = r.amount / (r.t1 - r.t0)
        slopes.set(r.t0, (slopes.get(r.t0) ?? 0) + per)
        slopes.set(r.t1, (slopes.get(r.t1) ?? 0) - per)
      }
    }

    const keys = [...new Set([...steps.keys(), ...slopes.keys()])].sort((a, b) => a - b)
    const times = new Float64Array(keys.length)
    const cum = new Float64Array(keys.length)
    const slope = new Float64Array(keys.length)

    let running = 0
    let currentSlope = 0
    for (let i = 0; i < keys.length; i++) {
      const t = keys[i]
      if (i > 0) running += currentSlope * (t - keys[i - 1])
      running += steps.get(t) ?? 0
      currentSlope += slopes.get(t) ?? 0
      times[i] = t
      cum[i] = running
      slope[i] = currentSlope
    }

    return new Curve(times, cum, slope, total)
  }

  at(t: number): number {
    const n = this.times.length
    if (n === 0 || t < this.times[0]) return 0
    let lo = 0
    let hi = n - 1
    while (lo < hi) {
      const mid = (lo + hi + 1) >> 1
      if (this.times[mid] <= t) lo = mid
      else hi = mid - 1
    }
    return this.cum[lo] + this.slope[lo] * (t - this.times[lo])
  }
}

export interface RevenueLines {
  rooms: number
  food: number
  bev: number
  bqt_food: number
  bqt_bev: number
  bqt_rental: number
  bqt_av: number
}

export const REVENUE_KEYS: Array<keyof RevenueLines> =
  ['rooms', 'food', 'bev', 'bqt_food', 'bqt_bev', 'bqt_rental', 'bqt_av']

export interface Stats {
  /** Stays currently in house. */
  inHouseStays: number
  /** Guests currently in house. */
  inHouseGuests: number
  /** Occupied rooms ÷ rooms in the house. */
  occupancy: number
  /** Revenue-weighted MTD ADR: rooms revenue accrued ÷ room-nights accrued. */
  adr: number
}

export interface Accrual {
  through(t: number): RevenueLines
  stats(t: number): Stats
  /** Line totals summed straight off the file, for the §4 load-time check. */
  fileTotals: RevenueLines
  periodStart: number
  periodEnd: number
  rooms: number
  tz: string
}

const ROOMS_ACCRUAL_START_HOUR = 15
const ROOMS_ACCRUAL_END_HOUR = 23

export function buildAccrual(data: MonthData, roomsInHouse: number): Accrual {
  const tz = data.meta.tz
  const periodStart = Date.parse(data.meta.period_start)
  const periodEnd = Date.parse(data.meta.period_end)

  const ramps: Record<keyof RevenueLines, Ramp[]> = {
    rooms: [], food: [], bev: [], bqt_food: [], bqt_bev: [], bqt_rental: [], bqt_av: [],
  }
  const fileTotals: RevenueLines = {
    rooms: 0, food: 0, bev: 0, bqt_food: 0, bqt_bev: 0, bqt_rental: 0, bqt_av: 0,
  }

  // Room nights: linear 15:00 → 23:00 on the night's own date (§4).
  const nightCount: Ramp[] = []
  const occupancy: Ramp[] = []
  const guests: Ramp[] = []

  for (const stay of data.stays) {
    for (const night of stay.nights) {
      const t0 = wallClock(night.date, ROOMS_ACCRUAL_START_HOUR, 0, tz)
      const t1 = wallClock(night.date, ROOMS_ACCRUAL_END_HOUR, 0, tz)
      ramps.rooms.push({ t0, t1, amount: night.rate })
      nightCount.push({ t0, t1, amount: 1 })
      fileTotals.rooms += night.rate
    }
    const arrive = Date.parse(stay.arrive)
    const depart = Date.parse(stay.depart)
    occupancy.push({ t0: arrive, t1: arrive, amount: 1 })
    occupancy.push({ t0: depart, t1: depart, amount: -1 })
    guests.push({ t0: arrive, t1: arrive, amount: stay.guests })
    guests.push({ t0: depart, t1: depart, amount: -stay.guests })
  }

  // Outlet checks: food and bev land whole at `closed` (§4).
  for (const check of data.checks) {
    const closed = Date.parse(check.closed)
    ramps.food.push({ t0: closed, t1: closed, amount: check.food })
    ramps.bev.push({ t0: closed, t1: closed, amount: check.bev })
    fileTotals.food += check.food
    fileTotals.bev += check.bev
  }

  // Banquets: every line accrues linearly from start to end (§4).
  for (const ev of data.events) {
    const t0 = Date.parse(ev.start)
    const t1 = Date.parse(ev.end)
    ramps.bqt_food.push({ t0, t1, amount: ev.food })
    ramps.bqt_bev.push({ t0, t1, amount: ev.bev })
    ramps.bqt_rental.push({ t0, t1, amount: ev.room_rental })
    ramps.bqt_av.push({ t0, t1, amount: ev.av })
    fileTotals.bqt_food += ev.food
    fileTotals.bqt_bev += ev.bev
    fileTotals.bqt_rental += ev.room_rental
    fileTotals.bqt_av += ev.av
  }

  const curves = {} as Record<keyof RevenueLines, Curve>
  for (const key of REVENUE_KEYS) curves[key] = Curve.build(ramps[key])

  const nightCurve = Curve.build(nightCount)
  const occCurve = Curve.build(occupancy)
  const guestCurve = Curve.build(guests)

  return {
    fileTotals,
    periodStart,
    periodEnd,
    rooms: roomsInHouse,
    tz,
    through(t: number): RevenueLines {
      return {
        rooms: curves.rooms.at(t),
        food: curves.food.at(t),
        bev: curves.bev.at(t),
        bqt_food: curves.bqt_food.at(t),
        bqt_bev: curves.bqt_bev.at(t),
        bqt_rental: curves.bqt_rental.at(t),
        bqt_av: curves.bqt_av.at(t),
      }
    },
    stats(t: number): Stats {
      const stays = Math.max(0, Math.round(occCurve.at(t)))
      const nights = nightCurve.at(t)
      return {
        inHouseStays: stays,
        inHouseGuests: Math.max(0, Math.round(guestCurve.at(t))),
        occupancy: roomsInHouse > 0 ? stays / roomsInHouse : 0,
        adr: nights > 1e-9 ? curves.rooms.at(t) / nights : 0,
      }
    },
  }
}

export function totalRevenue(l: RevenueLines): number {
  return l.rooms + l.food + l.bev + l.bqt_food + l.bqt_bev + l.bqt_rental + l.bqt_av
}

export interface ReconLine { key: keyof RevenueLines; atPeriodEnd: number; fileSum: number; delta: number }

/**
 * §4: "accruedThrough(period_end) must equal the summed totals of the file.
 * Log both." Returns the comparison so the caller can print and assert it.
 */
export function reconcile(a: Accrual): { lines: ReconLine[]; worst: number } {
  const end = a.through(a.periodEnd)
  const lines = REVENUE_KEYS.map((key) => {
    const atPeriodEnd = end[key]
    const fileSum = a.fileTotals[key]
    return { key, atPeriodEnd, fileSum, delta: atPeriodEnd - fileSum }
  })
  const worst = lines.reduce((m, l) => Math.max(m, Math.abs(l.delta)), 0)
  return { lines, worst }
}
