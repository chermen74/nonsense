/**
 * What §14's department zoom needs, beyond the four lines the panel already
 * has — build order §16 step 14.
 *
 * Three things: a day-by-day series, so the productivity sparkline and the
 * daily-net strip have something to draw; the names behind each line, so
 * hovering a bar says who the money went to; and the department's footprint,
 * so the scene knows what to dim.
 *
 * The daily money lines are differences of the same curves the panel reads —
 * `dept(id, dayEnd) − dept(id, dayStart)` — rather than a second pass over the
 * records. There is one accrual in this codebase and every readout is a view
 * of it, so a strip that disagreed with the panel would be impossible to
 * explain and easy to ship. The day's labor *hours* are the one thing counted
 * separately, because hours are not money and §12's curves hold money.
 *
 * Contributors are curves too, one per name, so hovering a bar at any instant
 * costs a binary search each and is month-to-date by construction.
 */

import { Curve, type Ramp } from './accrue'
import type { CostAccrual, DeptLines } from './costs'
import { dayKey, wallClock } from './tz'
import type { Department, Layout, MonthData } from '../types'

/** The four bars §14 draws, and what the hover breaks down. */
export type CostLine = 'revenue' | 'cos' | 'labor' | 'other'

export interface DayCell {
  /** `YYYY-MM-DD` in the property's own zone. */
  date: string
  from: number
  to: number
  lines: DeptLines
  /** Punched hours worked in this department on this day. */
  hours: number
  /** Covers, attendees or occupied rooms — whatever this department turns. */
  driver: number
}

export interface Contributor { name: string; amount: number }

/**
 * A department's footprint in plan, as axis-aligned boxes. §14 dims
 * "everything outside the zone", and a zone is wherever the department's own
 * work happens: its outlets, its function rooms, its back-of-house box, the
 * guest floors if it sells them, and always the ground its anchor stands on.
 */
export class Zone {
  constructor(private readonly boxes: Array<{ x: number; z: number; w: number; d: number }>) {}

  contains(x: number, z: number): boolean {
    for (const b of this.boxes) {
      if (Math.abs(x - b.x) <= b.w / 2 && Math.abs(z - b.z) <= b.d / 2) return true
    }
    return false
  }
}

export interface DeptProfile {
  id: string
  name: string
  days: DayCell[]
  /** What the sparkline's second line counts, for its own label. */
  driverLabel: string
  zone: Zone
  /** The biggest single day's labor hours and driver, for scaling the spark. */
  peakHours: number
  peakDriver: number
  /** Largest absolute daily net, for scaling the strip. */
  peakNet: number
  /** Who the money went to, month to date through `t`. */
  contributors(line: CostLine, t: number, limit?: number): Contributor[]
  /** The day `t` falls in, for §14's hover on the strip. */
  dayAt(t: number): DayCell | null
}

const ANCHOR_PAD = 14

function zoneFor(dept: Department, layout: Layout): Zone {
  const boxes: Array<{ x: number; z: number; w: number; d: number }> = [
    { x: dept.anchor.x, z: dept.anchor.z, w: ANCHOR_PAD, d: ANCHOR_PAD },
  ]
  const src = dept.sources
  for (const outlet of layout.outlets) {
    if (src?.outlets?.includes(outlet.id)) boxes.push(outlet)
  }
  for (const room of layout.function_rooms) {
    if (src?.function_rooms?.includes(room.id)) boxes.push(room)
  }
  for (const box of layout.boh ?? []) {
    if (box.dept === dept.id) boxes.push(box)
  }
  if (src?.rooms) {
    // The guest floors, wing by wing, with room for the corridor behind them.
    for (const wing of layout.wings) {
      const span = (wing.rooms_per_floor - 1) * wing.room_pitch
      boxes.push({
        x: wing.origin.x + (wing.dir.x * span) / 2,
        z: wing.origin.z + (wing.dir.z * span) / 2,
        w: Math.abs(wing.dir.x) > Math.abs(wing.dir.z) ? span + 12 : 14,
        d: Math.abs(wing.dir.x) > Math.abs(wing.dir.z) ? 14 : span + 12,
      })
    }
  }
  return new Zone(boxes)
}

/**
 * What a department's productivity is measured against.
 *
 * An operated department is measured on what it sells: covers for an outlet,
 * attendees for banquets, occupied rooms for rooms. A support or undistributed
 * department has nothing of its own to sell, so it is measured on occupied
 * rooms — hours per occupied room is the denominator the industry already
 * uses for exactly these departments, and it needs no extra configuration.
 */
function driverLabelFor(dept: Department): string {
  if (dept.sources?.outlets?.length) return 'covers'
  if (dept.sources?.function_rooms?.length) return 'attendees'
  return 'occupied rooms'
}

/** Overlap of a shift with a day, in hours. */
function overlapHours(from: number, to: number, dayFrom: number, dayTo: number): number {
  const lo = Math.max(from, dayFrom)
  const hi = Math.min(to, dayTo)
  return hi > lo ? (hi - lo) / 3_600_000 : 0
}

export function buildProfiles(
  data: MonthData, layout: Layout, costs: CostAccrual,
): Map<string, DeptProfile> {
  const tz = data.meta.tz
  const periodStart = Date.parse(data.meta.period_start)
  const periodEnd = Date.parse(data.meta.period_end)

  // The period's local days, from its own start rather than from a UTC grid.
  const dates: string[] = []
  for (let t = periodStart; t < periodEnd; t += 12 * 3600_000) {
    const key = dayKey(t, tz)
    if (dates[dates.length - 1] !== key) dates.push(key)
  }
  const bounds = dates.map((date, i) => ({
    date,
    from: Math.max(wallClock(date, 0, 0, tz), periodStart),
    to: i + 1 < dates.length
      ? Math.min(wallClock(dates[i + 1], 0, 0, tz), periodEnd)
      : periodEnd,
  }))
  const dayIndex = new Map(dates.map((d, i) => [d, i]))

  // --- the drivers, counted once for every department that shares one -----
  const occupied = new Array(dates.length).fill(0)
  for (const stay of data.stays) {
    for (const night of stay.nights) {
      const i = dayIndex.get(night.date)
      if (i !== undefined) occupied[i]++
    }
  }
  const coversByOutlet = new Map<string, number[]>()
  for (const check of data.checks) {
    const i = dayIndex.get(dayKey(Date.parse(check.closed), tz))
    if (i === undefined) continue
    let row = coversByOutlet.get(check.outlet)
    if (!row) coversByOutlet.set(check.outlet, row = new Array(dates.length).fill(0))
    row[i] += check.covers
  }
  const attendeesByRoom = new Map<string, number[]>()
  for (const event of data.events) {
    const i = dayIndex.get(dayKey(Date.parse(event.start), tz))
    if (i === undefined) continue
    let row = attendeesByRoom.get(event.function_room)
    if (!row) attendeesByRoom.set(event.function_room, row = new Array(dates.length).fill(0))
    row[i] += event.attendees
  }

  // --- hours, by department and day ---------------------------------------
  const hoursByDept = new Map<string, number[]>()
  for (const dept of costs.departments) hoursByDept.set(dept.id, new Array(dates.length).fill(0))
  for (const shift of data.shifts ?? []) {
    const row = hoursByDept.get(shift.dept)
    if (!row) continue
    const from = Date.parse(shift.in)
    const to = Date.parse(shift.out)
    if (!(to > from)) continue
    // A punch can straddle midnight even after §15's split, so it is spread
    // across whichever days it actually touches.
    const first = dayIndex.get(dayKey(from, tz)) ?? 0
    for (let i = first; i < bounds.length; i++) {
      if (bounds[i].from >= to) break
      row[i] += overlapHours(from, to, bounds[i].from, bounds[i].to)
    }
  }

  // --- who the money went to ----------------------------------------------
  const named = new Map<string, Map<CostLine, Map<string, Ramp[]>>>()
  const push = (deptId: string, line: CostLine, name: string, ramp: Ramp) => {
    if (!hoursByDept.has(deptId)) return       // a department the layout lacks
    let byLine = named.get(deptId)
    if (!byLine) named.set(deptId, byLine = new Map())
    let byName = byLine.get(line)
    if (!byName) byLine.set(line, byName = new Map())
    const list = byName.get(name)
    if (list) list.push(ramp)
    else byName.set(name, [ramp])
  }

  const outletDept = new Map<string, Department>()
  const functionRoomDept = new Map<string, Department>()
  let roomsDept: Department | null = null
  for (const dept of costs.departments) {
    if (dept.sources?.rooms) roomsDept = dept
    for (const id of dept.sources?.outlets ?? []) outletDept.set(id, dept)
    for (const id of dept.sources?.function_rooms ?? []) functionRoomDept.set(id, dept)
  }
  const outletName = new Map(layout.outlets.map((o) => [o.id, o.name]))
  const roomName = new Map(layout.function_rooms.map((f) => [f.id, f.name]))

  // Rooms revenue by market segment: the one breakdown a rooms department is
  // ever actually asked for.
  if (roomsDept) {
    for (const stay of data.stays) {
      for (const night of stay.nights) {
        push(roomsDept.id, 'revenue', stay.market, {
          t0: wallClock(night.date, 15, 0, tz),
          t1: wallClock(night.date, 23, 0, tz),
          amount: night.rate,
        })
      }
    }
  }
  const cosPct = data.meta.cos_pct ?? {}
  for (const check of data.checks) {
    const dept = outletDept.get(check.outlet)
    if (!dept) continue
    const closed = Date.parse(check.closed)
    const label = outletName.get(check.outlet) ?? check.outlet
    push(dept.id, 'revenue', `${label} — food`, { t0: closed, t1: closed, amount: check.food })
    push(dept.id, 'revenue', `${label} — beverage`, { t0: closed, t1: closed, amount: check.bev })
    const pct = cosPct[check.outlet]
    if (!pct) continue
    push(dept.id, 'cos', 'Food', { t0: closed, t1: closed, amount: check.food * pct.food })
    push(dept.id, 'cos', 'Beverage', { t0: closed, t1: closed, amount: check.bev * pct.bev })
  }
  const banquetPct = cosPct.BQT
  for (const event of data.events) {
    const dept = functionRoomDept.get(event.function_room)
    if (!dept) continue
    const t0 = Date.parse(event.start)
    const t1 = Date.parse(event.end)
    const label = roomName.get(event.function_room) ?? event.function_room
    push(dept.id, 'revenue', `${label} — food`, { t0, t1, amount: event.food })
    push(dept.id, 'revenue', `${label} — beverage`, { t0, t1, amount: event.bev })
    push(dept.id, 'revenue', 'Room rental', { t0, t1, amount: event.room_rental })
    push(dept.id, 'revenue', 'Audio visual', { t0, t1, amount: event.av })
    if (!banquetPct) continue
    push(dept.id, 'cos', 'Food', { t0, t1, amount: event.food * banquetPct.food })
    push(dept.id, 'cos', 'Beverage', { t0, t1, amount: event.bev * banquetPct.bev })
  }
  const load = 1 + (data.meta.benefits_load ?? 0)
  for (const shift of data.shifts ?? []) {
    const t0 = Date.parse(shift.in)
    const t1 = Date.parse(shift.out)
    if (!(t1 > t0)) continue
    push(shift.dept, 'labor', shift.role,
         { t0, t1, amount: ((t1 - t0) / 3_600_000) * shift.rate * load })
  }
  for (const row of data.salaried ?? []) {
    push(row.dept, 'labor', 'Salaried', {
      t0: periodStart, t1: periodEnd, amount: row.monthly * load,
    })
  }
  for (const expense of data.expenses ?? []) {
    const at = Date.parse(expense.date)
    push(expense.dept, 'other', expense.vendor, { t0: at, t1: at, amount: expense.amount })
  }

  // --- assemble ------------------------------------------------------------
  const profiles = new Map<string, DeptProfile>()
  for (const dept of costs.departments) {
    const hours = hoursByDept.get(dept.id)!
    const driverRow = new Array(dates.length).fill(0)
    if (dept.sources?.outlets?.length) {
      for (const id of dept.sources.outlets) {
        const row = coversByOutlet.get(id)
        if (row) for (let i = 0; i < row.length; i++) driverRow[i] += row[i]
      }
    } else if (dept.sources?.function_rooms?.length) {
      for (const id of dept.sources.function_rooms) {
        const row = attendeesByRoom.get(id)
        if (row) for (let i = 0; i < row.length; i++) driverRow[i] += row[i]
      }
    } else {
      for (let i = 0; i < driverRow.length; i++) driverRow[i] = occupied[i]
    }

    const days: DayCell[] = bounds.map((bound, i) => {
      const open = costs.dept(dept.id, bound.from)
      const close = costs.dept(dept.id, bound.to)
      return {
        date: bound.date, from: bound.from, to: bound.to,
        lines: {
          revenue: close.revenue - open.revenue,
          cos: close.cos - open.cos,
          labor: close.labor - open.labor,
          other: close.other - open.other,
          profit: close.profit - open.profit,
        },
        hours: hours[i],
        driver: driverRow[i],
      }
    })

    const curves = new Map<CostLine, Array<{ name: string; curve: Curve }>>()
    for (const [line, byName] of named.get(dept.id) ?? []) {
      curves.set(line, [...byName].map(([name, ramps]) => ({ name, curve: Curve.build(ramps) })))
    }

    profiles.set(dept.id, {
      id: dept.id,
      name: dept.name,
      days,
      driverLabel: driverLabelFor(dept),
      zone: zoneFor(dept, layout),
      peakHours: days.reduce((max, d) => Math.max(max, d.hours), 0),
      peakDriver: days.reduce((max, d) => Math.max(max, d.driver), 0),
      peakNet: days.reduce((max, d) => Math.max(max, Math.abs(d.lines.profit)), 0),
      contributors(line, t, limit = 5) {
        const all = curves.get(line) ?? []
        return all
          .map((c) => ({ name: c.name, amount: c.curve.at(t) }))
          .filter((c) => Math.abs(c.amount) > 0.005)
          .sort((a, b) => b.amount - a.amount)
          .slice(0, limit)
      },
      dayAt(t) {
        for (const day of days) if (t >= day.from && t < day.to) return day
        return t >= periodEnd ? days[days.length - 1] ?? null : null
      },
    })
  }
  return profiles
}
