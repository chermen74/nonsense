/**
 * SPEND_SPEC §16 step 11: the global waterfall's geometry.
 *
 * The panel paints what `waterfallRows` returns and does no arithmetic of its
 * own, so checking the fractions here checks the bars. What matters is that
 * the scale is shared and fixed — a per-frame maximum would renormalise and no
 * bar would ever appear to grow — and that a bar is the department's own line
 * over that scale and nothing else.
 */

import { readFileSync } from 'node:fs'
import { buildCosts } from '../src/sim/costs'
import { waterfallRows, waterfallScale, type WaterfallRow } from '../src/sim/waterfall'
import type { Layout, MonthData } from '../src/types'

let failures = 0
function check(name: string, ok: boolean, detail = '') {
  if (!ok) failures++
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? ': ' + detail : ''}`)
}
function near(a: number, b: number, tol: number) { return Math.abs(a - b) <= tol }
const money = (v: number) => `$${v.toLocaleString(undefined, { maximumFractionDigits: 0 })}`

const layout = JSON.parse(readFileSync('public/layout.json', 'utf8')) as Layout
const month = JSON.parse(readFileSync('public/data/2026-08.json', 'utf8')) as MonthData
const periodStart = Date.parse(month.meta.period_start)
const periodEnd = Date.parse(month.meta.period_end)

const costs = buildCosts(month, layout)
const scale = waterfallScale(costs)
const rowById = (rows: WaterfallRow[], id: string) => rows.find((r) => r.id === id)!

console.log('--- the shared scale ---')

let biggest = 0
let biggestWhat = ''
for (const dept of costs.departments) {
  const file = costs.fileTotals.get(dept.id)!
  if (file.revenue > biggest) { biggest = file.revenue; biggestWhat = `${dept.id} revenue` }
  const cost = file.cos + file.labor + file.other
  if (cost > biggest) { biggest = cost; biggestWhat = `${dept.id} cost` }
}
check('the scale is the largest bar the month will draw', near(scale, biggest, 1e-6),
      `${money(scale)} (${biggestWhat})`)

const atEnd = waterfallRows(costs, periodEnd, scale)
let longest = 0
for (const row of atEnd) {
  longest = Math.max(longest, row.revenueFrac, row.cosFrac + row.laborFrac + row.otherFrac)
}
check('so exactly one bar is full at period_end', near(longest, 1, 1e-9), `longest ${longest}`)

let overflow = 0
for (const row of atEnd) {
  overflow = Math.max(overflow, row.revenueFrac, row.cosFrac + row.laborFrac + row.otherFrac)
}
check('and no bar overflows its track', overflow <= 1 + 1e-9)

console.log('\n--- a bar is its own line over that scale ---')

const grill = rowById(atEnd, 'GRILL')
check('revenue', near(grill.revenueFrac, grill.lines.revenue / scale, 1e-12))
check('cost of sales', near(grill.cosFrac, grill.lines.cos / scale, 1e-12))
check('labor', near(grill.laborFrac, grill.lines.labor / scale, 1e-12))
check('other expense', near(grill.otherFrac, grill.lines.other / scale, 1e-12))
check('the profit figure is the residual, which is the overhang of one track past the other',
      near(grill.lines.profit,
           (grill.revenueFrac - grill.cosFrac - grill.laborFrac - grill.otherFrac) * scale, 0.005),
      money(grill.lines.profit))

const util = rowById(atEnd, 'UTIL')
check('an undistributed department draws no revenue bar', util.revenueFrac === 0)
check('...and still draws its cost', util.otherFrac > 0, money(util.lines.other))

console.log('\n--- bars extend as the month plays ---')

const mid = Date.parse('2026-08-15T12:00:00-07:00')
const before = waterfallRows(costs, periodStart, scale)
const middle = waterfallRows(costs, mid, scale)
check('nothing is drawn before the month opens',
      before.every((r) => r.revenueFrac === 0 && r.cosFrac === 0 && r.laborFrac === 0 && r.otherFrac === 0))
check('every department that ends with a bar has a shorter one at mid-month',
      costs.departments.every((d) => {
        const m = rowById(middle, d.id)
        const e = rowById(atEnd, d.id)
        return m.revenueFrac <= e.revenueFrac + 1e-12 &&
               m.cosFrac + m.laborFrac + m.otherFrac <= e.cosFrac + e.laborFrac + e.otherFrac + 1e-12
      }))
check('and the rooms bar is roughly half-drawn halfway through',
      rowById(middle, 'ROOMS').revenueFrac > 0.35 && rowById(middle, 'ROOMS').revenueFrac < 0.6,
      rowById(middle, 'ROOMS').revenueFrac.toFixed(3))

console.log('\n--- the frame loop reuses its rows ---')

const buf: WaterfallRow[] = []
const first = waterfallRows(costs, mid, scale, buf)
const firstRow = first[0]
const second = waterfallRows(costs, periodEnd, scale, buf)
check('the same array comes back', first === buf && second === buf)
check('...holding the same row objects, so a frame allocates nothing', second[0] === firstRow)
check('...with the later values in them',
      near(second[0].revenueFrac, atEnd[0].revenueFrac, 1e-12))

console.log('\n--- the readout under the bars is §10\'s arithmetic ---')

const hotel = costs.hotel(periodEnd)
let operatedProfit = 0
let overheadCost = 0
for (const dept of costs.departments) {
  const lines = costs.fileTotals.get(dept.id)!
  if (dept.type === 'operated') operatedProfit += lines.profit
  else overheadCost -= lines.profit
}
check('department profit is the operated departments\'', near(hotel.deptProfitTotal, operatedProfit, 0.005),
      money(hotel.deptProfitTotal))
check('support and undistributed is what they cost', near(hotel.undistributedTotal, overheadCost, 0.005),
      money(hotel.undistributedTotal))
check('GOP is the one less the other', near(hotel.gop, operatedProfit - overheadCost, 0.005), money(hotel.gop))
check('the margin is GOP over the hotel\'s revenue',
      near(hotel.gopMargin, hotel.gop / atEnd.reduce((s, r) => s + r.lines.revenue, 0), 1e-9),
      `${(hotel.gopMargin * 100).toFixed(1)}%`)
check('fixed charges accrue linearly and are whole only at period_end',
      near(costs.hotel(mid).fixedCharges, hotel.fixedCharges * ((mid - periodStart) / (periodEnd - periodStart)),
           0.005) && hotel.fixedCharges > 0,
      money(hotel.fixedCharges))

console.log(failures === 0 ? '\nall waterfall checks passed' : `\n${failures} FAILED`)
process.exit(failures === 0 ? 0 : 1)
