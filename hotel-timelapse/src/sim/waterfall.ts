/**
 * §14's global waterfall, as geometry — SPEND_SPEC §16 step 11.
 *
 * The panel itself does no arithmetic: it asks for one row per department and
 * paints four bar segments from the fractions here. Keeping the maths in a
 * pure module means the bars can be checked without a browser, and means the
 * frame loop can reuse one array instead of allocating ten objects a frame.
 *
 * One shared scale across every department, fixed at the file totals rather
 * than recomputed from the running maximum. Two reasons. A scale that tracked
 * the current maximum would renormalise every frame, so no bar would ever
 * appear to grow — §14 wants bars that "extend as the month plays". And a
 * shared scale is what makes ten bars in one panel comparable: housekeeping's
 * cost is read against rooms revenue because that is the comparison the
 * waterfall exists to make.
 */

import type { CostAccrual, DeptLines } from './costs'

export interface WaterfallRow {
  id: string
  name: string
  /** §10: operated · support · undistributed. */
  type: string
  lines: DeptLines
  /** Fractions of the shared scale, 0..1, ready to become a bar width. */
  revenueFrac: number
  cosFrac: number
  laborFrac: number
  otherFrac: number
}

const EMPTY_LINES: DeptLines = { revenue: 0, cos: 0, labor: 0, other: 0, profit: 0 }

/**
 * The largest bar the month will ever draw: the biggest single department's
 * revenue, or its whole cost stack, whichever is longer.
 */
export function waterfallScale(costs: CostAccrual): number {
  let max = 0
  for (const dept of costs.departments) {
    const file = costs.fileTotals.get(dept.id)
    if (!file) continue
    max = Math.max(max, file.revenue, file.cos + file.labor + file.other)
  }
  return max
}

function frac(value: number, scale: number): number {
  if (!(scale > 0)) return 0
  return Math.min(Math.max(value / scale, 0), 1)
}

/**
 * Every department's bars at time `t`. Pass `into` to reuse the rows across
 * frames; the array is resized and its rows overwritten in place.
 */
export function waterfallRows(
  costs: CostAccrual, t: number, scale: number, into: WaterfallRow[] = [],
): WaterfallRow[] {
  into.length = costs.departments.length
  for (let i = 0; i < costs.departments.length; i++) {
    const dept = costs.departments[i]
    const lines = costs.dept(dept.id, t)
    const row = into[i] ?? (into[i] = {
      id: dept.id, name: dept.name, type: dept.type, lines: { ...EMPTY_LINES },
      revenueFrac: 0, cosFrac: 0, laborFrac: 0, otherFrac: 0,
    })
    row.id = dept.id
    row.name = dept.name
    row.type = dept.type
    row.lines = lines
    row.revenueFrac = frac(lines.revenue, scale)
    row.cosFrac = frac(lines.cos, scale)
    row.laborFrac = frac(lines.labor, scale)
    row.otherFrac = frac(lines.other, scale)
  }
  return into
}
