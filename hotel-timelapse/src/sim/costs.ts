/**
 * The cost side of `accruedThrough(t)` — SPEND_SPEC §12.
 *
 * Same keyframe machinery as §4's revenue: every cost is a set of ramps
 * compiled once into a piecewise-linear cumulative curve, so a department's
 * P&L at any instant is a handful of binary searches rather than a scan of six
 * thousand punches. §12 calls for exactly that — "labor is piecewise-linear
 * (slope changes at every punch), so punches are keyframes".
 *
 * The four accrual shapes, all locked by §12:
 *
 *   cost of sales   `cos_pct x revenue`, whole at each check's `closed`, and
 *                   linear across a banquet — it is derived, never a record
 *   hourly labor    linear from `in` to `out` at `rate x (1 + benefits_load)`
 *   salaried        linear across the whole period
 *   other expense   whole at `date`, whether invoiced or accrued; the month-end
 *                   journal therefore lands as §12's visible cascade
 *
 * Two readings worth naming. Support departments (kitchen, housekeeping) keep
 * their own costs here rather than being allocated into the outlets and rooms
 * they serve: §10 gives every department a scene anchor and §14 a bar, and an
 * allocated kitchen would have neither. GOP is identical either way, because
 * allocation only moves cost between departments — so it can land with §14's
 * waterfall, where it is a presentation choice, instead of being baked in
 * where it would be unverifiable.
 *
 * And a department owns revenue through `layout.departments[].sources`, named
 * in config rather than inferred from ids that happen to match, so a property
 * whose POS calls an outlet something other than its department still ties.
 */

import { Curve, type Ramp } from './accrue'
import { wallClock } from './tz'
import type { Department, Layout, MonthData } from '../types'

export interface DeptLines {
  revenue: number
  cos: number
  labor: number
  other: number
  /** §10: revenue less its own cost of sales, labor and other expense. */
  profit: number
}

export interface HotelLines {
  /** Σ profit of the operated departments. */
  deptProfitTotal: number
  /** Σ cost of the support and undistributed departments, as a positive. */
  undistributedTotal: number
  gop: number
  gopMargin: number
  /** §10: below GOP, linear across the month, and only when the file has it. */
  fixedCharges: number
}

const ZERO: DeptLines = { revenue: 0, cos: 0, labor: 0, other: 0, profit: 0 }

interface DeptCurves {
  revenue: Curve
  cos: Curve
  labor: Curve
  other: Curve
}

export interface CostAccrual {
  departments: Department[]
  dept(id: string, t: number): DeptLines
  hotel(t: number): HotelLines
  /** Every line summed straight off the file, for the §12 load-time check. */
  fileTotals: Map<string, DeptLines>
  fileGop: number
}

const ROOMS_ACCRUAL_START_HOUR = 15
const ROOMS_ACCRUAL_END_HOUR = 23

function hoursBetween(from: number, to: number): number {
  return (to - from) / 3_600_000
}

export function buildCosts(data: MonthData, layout: Layout): CostAccrual {
  const departments = layout.departments ?? []
  const tz = data.meta.tz
  const periodStart = Date.parse(data.meta.period_start)
  const periodEnd = Date.parse(data.meta.period_end)
  const load = 1 + (data.meta.benefits_load ?? 0)
  const cosPct = data.meta.cos_pct ?? {}

  const ramps = new Map<string, { revenue: Ramp[]; cos: Ramp[]; labor: Ramp[]; other: Ramp[] }>()
  const totals = new Map<string, DeptLines>()
  for (const dept of departments) {
    ramps.set(dept.id, { revenue: [], cos: [], labor: [], other: [] })
    totals.set(dept.id, { ...ZERO })
  }

  /** Which department owns each revenue source, from config. */
  const outletDept = new Map<string, string>()
  const functionRoomDept = new Map<string, string>()
  let roomsDept: string | null = null
  for (const dept of departments) {
    const src = dept.sources
    if (!src) continue
    if (src.rooms) roomsDept = dept.id
    for (const id of src.outlets ?? []) outletDept.set(id, dept.id)
    for (const id of src.function_rooms ?? []) functionRoomDept.set(id, dept.id)
  }

  const add = (deptId: string | null | undefined,
               line: 'revenue' | 'cos' | 'labor' | 'other',
               ramp: Ramp) => {
    if (!deptId) return
    const bucket = ramps.get(deptId)
    if (!bucket) return                 // data names a department the layout lacks
    bucket[line].push(ramp)
    totals.get(deptId)![line] += ramp.amount
  }

  // --- revenue, on §4's own timings so both sides of a line agree ---------
  for (const stay of data.stays) {
    for (const night of stay.nights) {
      add(roomsDept, 'revenue', {
        t0: wallClock(night.date, ROOMS_ACCRUAL_START_HOUR, 0, tz),
        t1: wallClock(night.date, ROOMS_ACCRUAL_END_HOUR, 0, tz),
        amount: night.rate,
      })
    }
  }

  for (const check of data.checks) {
    const dept = outletDept.get(check.outlet)
    const closed = Date.parse(check.closed)
    add(dept, 'revenue', { t0: closed, t1: closed, amount: check.food + check.bev })
    const pct = cosPct[check.outlet]
    if (pct) {
      add(dept, 'cos', {
        t0: closed, t1: closed,
        amount: check.food * pct.food + check.bev * pct.bev,
      })
    }
  }

  // §11 keys banquet cost of sales to the banquet department, not the room.
  const banquetPct = cosPct.BQT
  for (const event of data.events) {
    const dept = functionRoomDept.get(event.function_room)
    const t0 = Date.parse(event.start)
    const t1 = Date.parse(event.end)
    add(dept, 'revenue', {
      t0, t1, amount: event.food + event.bev + event.room_rental + event.av,
    })
    if (banquetPct) {
      add(dept, 'cos', { t0, t1, amount: event.food * banquetPct.food + event.bev * banquetPct.bev })
    }
  }

  // --- labor (§12) --------------------------------------------------------
  for (const shift of data.shifts ?? []) {
    const t0 = Date.parse(shift.in)
    const t1 = Date.parse(shift.out)
    if (!(t1 > t0)) continue
    add(shift.dept, 'labor', { t0, t1, amount: hoursBetween(t0, t1) * shift.rate * load })
  }
  for (const row of data.salaried ?? []) {
    add(row.dept, 'labor', { t0: periodStart, t1: periodEnd, amount: row.monthly * load })
  }

  // --- other expense (§12): whole at its date, invoiced or accrued --------
  for (const expense of data.expenses ?? []) {
    const at = Date.parse(expense.date)
    add(expense.dept, 'other', { t0: at, t1: at, amount: expense.amount })
  }

  const curves = new Map<string, DeptCurves>()
  for (const dept of departments) {
    const bucket = ramps.get(dept.id)!
    curves.set(dept.id, {
      revenue: Curve.build(bucket.revenue),
      cos: Curve.build(bucket.cos),
      labor: Curve.build(bucket.labor),
      other: Curve.build(bucket.other),
    })
  }

  for (const lines of totals.values()) {
    lines.profit = lines.revenue - lines.cos - lines.labor - lines.other
  }

  const operated = departments.filter((d) => d.type === 'operated')
  const overhead = departments.filter((d) => d.type !== 'operated')
  const fileGop =
    operated.reduce((sum, d) => sum + totals.get(d.id)!.profit, 0) +
    overhead.reduce((sum, d) => sum + totals.get(d.id)!.profit, 0)

  const monthlyFixed = data.fixed_charges?.monthly ?? 0
  const fixedCurve = Curve.build(
    monthlyFixed ? [{ t0: periodStart, t1: periodEnd, amount: monthlyFixed }] : [],
  )

  function dept(id: string, t: number): DeptLines {
    const c = curves.get(id)
    if (!c) return { ...ZERO }
    const revenue = c.revenue.at(t)
    const cos = c.cos.at(t)
    const labor = c.labor.at(t)
    const other = c.other.at(t)
    return { revenue, cos, labor, other, profit: revenue - cos - labor - other }
  }

  function hotel(t: number): HotelLines {
    let deptProfitTotal = 0
    for (const d of operated) deptProfitTotal += dept(d.id, t).profit
    let undistributedTotal = 0
    for (const d of overhead) undistributedTotal -= dept(d.id, t).profit
    const gop = deptProfitTotal - undistributedTotal
    // Revenue is the operated departments'; overhead has none, so this is the
    // hotel's top line and the margin GOP is measured against.
    let revenue = 0
    for (const d of operated) revenue += dept(d.id, t).revenue
    return {
      deptProfitTotal,
      undistributedTotal,
      gop,
      gopMargin: revenue > 0 ? gop / revenue : 0,
      fixedCharges: fixedCurve.at(t),
    }
  }

  return { departments, dept, hotel, fileTotals: totals, fileGop }
}

export interface CostReconLine {
  dept: string
  line: keyof DeptLines
  atPeriodEnd: number
  fileSum: number
  delta: number
}

/** §12: every line at `period_end` equals the file sum, and GOP ties. */
export function reconcileCosts(
  costs: CostAccrual, periodEnd: number,
): { lines: CostReconLine[]; worst: number; gopDelta: number } {
  const lines: CostReconLine[] = []
  let worst = 0
  for (const dept of costs.departments) {
    const accrued = costs.dept(dept.id, periodEnd)
    const file = costs.fileTotals.get(dept.id)!
    for (const line of ['revenue', 'cos', 'labor', 'other', 'profit'] as const) {
      const delta = accrued[line] - file[line]
      lines.push({ dept: dept.id, line, atPeriodEnd: accrued[line], fileSum: file[line], delta })
      worst = Math.max(worst, Math.abs(delta))
    }
  }
  const gopDelta = costs.hotel(periodEnd).gop - costs.fileGop
  return { lines, worst, gopDelta }
}
