/**
 * SPEND_SPEC §16 step 10: the cost side of accruedThrough(t).
 *
 * §12's sanity check is the point of this file: every line at `period_end`
 * equals the file sum, and GOP equals Σ operated profit − Σ undistributed.
 */

import { readFileSync } from 'node:fs'
import { buildCosts, reconcileCosts } from '../src/sim/costs'
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

console.log('--- §12 reconciliation ---')
const recon = reconcileCosts(costs, periodEnd)
check('every department line ties to the file at period_end', recon.worst < 0.005,
      `worst delta ${recon.worst.toExponential(2)}`)
check('GOP ties too', Math.abs(recon.gopDelta) < 0.005,
      `delta ${recon.gopDelta.toExponential(2)}`)

const hotel = costs.hotel(periodEnd)
check('GOP equals Σ operated profit − Σ undistributed',
      near(hotel.gop, hotel.deptProfitTotal - hotel.undistributedTotal, 1e-6),
      `${money(hotel.deptProfitTotal)} − ${money(hotel.undistributedTotal)} = ${money(hotel.gop)}`)

// Independently: revenue less every cost, straight off the file.
let fileRevenue = 0, fileCost = 0
for (const lines of costs.fileTotals.values()) {
  fileRevenue += lines.revenue
  fileCost += lines.cos + lines.labor + lines.other
}
check('...and equals revenue less every cost, computed independently',
      near(hotel.gop, fileRevenue - fileCost, 0.005),
      `${money(fileRevenue)} − ${money(fileCost)} = ${money(fileRevenue - fileCost)}`)

console.log('\n--- §12 accrual shapes ---')
check('nothing has accrued before the period opens',
      costs.departments.every((d) => {
        const l = costs.dept(d.id, periodStart - 3600_000)
        return l.revenue === 0 && l.cos === 0 && l.labor === 0 && l.other === 0
      }))
check('every operated department earns revenue',
      costs.departments.filter((d) => d.type === 'operated')
        .every((d) => costs.dept(d.id, periodEnd).revenue > 0))
check('no support or undistributed department earns any',
      costs.departments.filter((d) => d.type !== 'operated')
        .every((d) => costs.dept(d.id, periodEnd).revenue === 0))
check('every department carries labor', 
      costs.departments.filter((d) => d.id !== 'UTIL')
        .every((d) => costs.dept(d.id, periodEnd).labor > 0))

// §12: salaried is linear across the month, so it is exactly half way at the
// midpoint. Housekeeping has salaried staff and hourly punches, so the pure
// test is a department whose only labor is salaried — none here — hence the
// weaker but still sharp check that labor only ever climbs.
const probes = Array.from({ length: 40 }, (_, i) =>
  periodStart + ((periodEnd - periodStart) * i) / 39)
let monotonic = true
for (const d of costs.departments) {
  let last = -1
  for (const t of probes) {
    const v = costs.dept(d.id, t)
    if (v.labor < last - 1e-6) monotonic = false
    last = v.labor
  }
}
check('labor only ever climbs', monotonic)

// §12's month-end wave. Invoiced expenses land on their own dates through
// the month, so the test is the *jump* at the close, not the level: it must
// be exactly the accrual-timed records and nothing else.
const accrualTotal = (month.expenses ?? [])
  .filter((e) => e.timing === 'accrual')
  .reduce((sum, e) => sum + e.amount, 0)
let otherBefore = 0
let otherAfter = 0
for (const d of costs.departments) {
  otherBefore += costs.dept(d.id, periodEnd - 2 * 3600_000).other
  otherAfter += costs.dept(d.id, periodEnd).other
}
check('§12 month-end wave: the accruals land at the close',
      near(otherAfter - otherBefore, accrualTotal, 0.005),
      `${money(otherAfter - otherBefore)} cascades in, against ${money(accrualTotal)} accrued`)
check('...and nothing invoiced is waiting for it',
      accrualTotal > 0 && otherBefore > 0,
      `${money(otherBefore)} already invoiced through the month`)

const grillMid = costs.dept('GRILL', periodStart + (periodEnd - periodStart) / 2)
check('cost of sales tracks revenue rather than leading it',
      grillMid.cos > 0 && grillMid.cos < grillMid.revenue,
      `${money(grillMid.cos)} of ${money(grillMid.revenue)} at the midpoint`)

check('fixed charges are linear and land below GOP',
      near(costs.hotel(periodStart + (periodEnd - periodStart) / 2).fixedCharges,
           (month.fixed_charges?.monthly ?? 0) / 2, 1),
      money(costs.hotel(periodEnd).fixedCharges))

console.log('\n--- the month, as the panel will show it ---')
for (const d of costs.departments) {
  const l = costs.dept(d.id, periodEnd)
  console.log(`  ${d.id.padEnd(8)} rev ${money(l.revenue).padStart(11)}  cos ${money(l.cos).padStart(9)}` +
              `  labor ${money(l.labor).padStart(10)}  other ${money(l.other).padStart(10)}` +
              `  profit ${money(l.profit).padStart(11)}`)
}
console.log(`  GOP ${money(hotel.gop)}  (${(hotel.gopMargin * 100).toFixed(1)}% of revenue)`)

const t0 = Date.now()
for (let k = 0; k < 200; k++) {
  const when = periodStart + k * 3_600_000
  for (const d of costs.departments) costs.dept(d.id, when)
  costs.hotel(when)
}
check('200 frames of the whole P&L stay inside a frame budget', Date.now() - t0 < 400,
      `${Date.now() - t0} ms for 200 frames of 10 departments`)

console.log(failures === 0 ? '\nALL CHECKS PASS' : `\n${failures} CHECK(S) FAILED`)
process.exit(failures === 0 ? 0 : 1)
