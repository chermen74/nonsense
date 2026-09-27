/**
 * SPEND_SPEC §12 / §16 step 13: the delivery animation and the month-end
 * cascade.
 *
 * §12's accrual table is locked, so the first thing checked here is that
 * nothing in step 13 moved a number: every expense still lands whole at its
 * own `date`, and the file still ties. The rest is the picture — a van and a
 * box per invoice and none for an accrual, the box ending on the department
 * that pays for it, and the close lighting the rows in order down the panel.
 */

import { readFileSync } from 'node:fs'
import { buildCosts } from '../src/sim/costs'
import { expandRooms } from '../src/sim/rooms'
import {
  buildDeliveries, Cascade, DELIVERY_PARCEL, DELIVERY_VAN,
  LANDING_GLOW, LANDING_STAGGER,
} from '../src/sim/deliveries'
import { forEachActive } from '../src/sim/legs'
import type { Layout, MonthData } from '../src/types'

let failures = 0
function check(name: string, ok: boolean, detail = '') {
  if (!ok) failures++
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? ': ' + detail : ''}`)
}
const money = (v: number) => `$${v.toLocaleString(undefined, { maximumFractionDigits: 0 })}`

const layout = JSON.parse(readFileSync('public/layout.json', 'utf8')) as Layout
const month = JSON.parse(readFileSync('public/data/2026-08.json', 'utf8')) as MonthData
const rooms = expandRooms(layout)
const costs = buildCosts(month, layout)
const order = costs.departments.map((d) => d.id)
const expenses = month.expenses ?? []
const invoices = expenses.filter((e) => e.timing === 'invoice')
const accruals = expenses.filter((e) => e.timing === 'accrual')
const periodEnd = Date.parse(month.meta.period_end)
const dock = layout.loading_dock!

const { segments, cascade } = buildDeliveries(layout, expenses, order, periodEnd)
const legs = Array.from({ length: segments.count }, (_, i) => ({
  t0: segments.t0[i], t1: segments.t1[i], intent: segments.intent[i],
  a: { x: segments.ax[i], z: segments.az[i] },
  b: { x: segments.bx[i], z: segments.bz[i] },
}))
const near = (p: { x: number; z: number }, q: { x: number; z: number }, tol = 5) =>
  Math.hypot(p.x - q.x, p.z - q.z) < tol
void rooms

console.log('--- step 13 moved no numbers ---')

// The whole point of the locked §12 table: the picture is a picture.
let offBy = 0
for (const dept of costs.departments) {
  const file = costs.fileTotals.get(dept.id)!
  const atEnd = costs.dept(dept.id, periodEnd)
  offBy = Math.max(offBy, Math.abs(atEnd.other - file.other))
}
check('every department\'s other expense still ties to the file at period_end',
      offBy < 0.005, `worst ${offBy.toExponential(2)}`)

for (const expense of [invoices[0], accruals[0]]) {
  const at = Date.parse(expense.date)
  // A department can book more than one thing at the same instant -- both of
  // this month's utility accruals land at 23:59 -- so the jump is their sum.
  const together = expenses
    .filter((e) => e.dept === expense.dept && Date.parse(e.date) === at)
    .reduce((sum, e) => sum + e.amount, 0)
  const before = costs.dept(expense.dept, at - 1000).other
  const after = costs.dept(expense.dept, at + 1000).other
  check(`a ${expense.timing} still lands whole at its own date`,
        Math.abs(after - before - together) < 0.005,
        `${money(after - before)} of ${money(together)}`)
}

console.log('\n--- a van and a box per invoice ---')

const arrivals = legs.filter((l) => l.intent === DELIVERY_VAN && near(l.b, dock, 6)
                                 && !near(l.a, l.b, 1))
const parcels = legs.filter((l) => l.intent === DELIVERY_PARCEL)
check('one van pulls in per invoice', arrivals.length === invoices.length,
      `${arrivals.length} of ${invoices.length}`)
check('one box comes off per invoice', parcels.length === invoices.length,
      `${parcels.length} of ${invoices.length}`)
check('an accrual sends no van', accruals.length > 0
      && legs.length === invoices.length * 4, `${accruals.length} accruals, no legs for them`)
check('every box starts at the dock', parcels.every((l) => near(l.a, dock, 6)))

const anchors = new Map((layout.departments ?? []).map((d) => [d.id, d.anchor]))
let toItsOwn = 0
for (const invoice of invoices) {
  const anchor = anchors.get(invoice.dept)
  if (!anchor) continue
  const at = Date.parse(invoice.date)
  if (parcels.some((l) => l.t0 >= at && l.t0 <= at + 5 * 60_000 && near(l.b, anchor, 0.5))) {
    toItsOwn++
  }
}
check('and ends at the department that pays for it', toItsOwn === invoices.length,
      `${toItsOwn} of ${invoices.length}`)

const first = invoices.map((e) => Date.parse(e.date)).sort((a, b) => a - b)[0]
check('the van is already on the road before the invoice lands',
      arrivals.every((l) => l.t0 < l.t1) && Math.min(...arrivals.map((l) => l.t0)) < first,
      'it arrives at the dock at the invoice date, not after it')
check('and drives in from off the property',
      arrivals.every((l) => Math.hypot(l.a.x - dock.x, l.a.z - dock.z) > 40))

let mostAtOnce = 0
for (const invoice of invoices) {
  // A minute in, the arrival leg has handed over to the unload, so each van
  // on the apron counts once.
  let n = 0
  forEachActive(segments, Date.parse(invoice.date) + 60_000, (i) => {
    if (segments.intent[i] === DELIVERY_VAN) n++
  })
  mostAtOnce = Math.max(mostAtOnce, n)
}
const busiestDay = Math.max(...Object.values(invoices.reduce((by, e) => {
  by[e.date] = (by[e.date] ?? 0) + 1
  return by
}, {} as Record<string, number>)))
check('every van on the busiest morning is on the apron at once',
      mostAtOnce === busiestDay, `${mostAtOnce} vans, ${busiestDay} invoices that morning`)

console.log('\n--- the close cascades down the panel ---')

check('the accruals all land at the close',
      cascade.accrualFrom >= periodEnd - 24 * 3600_000 && cascade.accrualFrom < periodEnd,
      new Date(cascade.accrualFrom).toISOString())
check('and they are the whole of what was accrued',
      Math.abs(cascade.accrualTotal - accruals.reduce((s, e) => s + e.amount, 0)) < 0.005,
      money(cascade.accrualTotal))

const accrualDept = accruals[0].dept
const landsAt = Date.parse(accruals[0].date)

// The close is a minute before the clock stops, so the whole sweep has to
// fit in that minute; the ceiling in the module is not what is used here.
const litAt = (dept: string) => {
  for (let t = landsAt; t <= periodEnd; t += 250) if (cascade.glow(dept, t) > 0) return t
  return Infinity
}
const sweepEnds = Math.max(...accruals.map((e) => litAt(e.dept)))
check('the whole sweep fits inside the month', sweepEnds < periodEnd,
      `last row lights ${((periodEnd - sweepEnds) / 1000).toFixed(0)}s before the clock stops`)
check('and it is a sweep, not one flash', sweepEnds > landsAt + 1000,
      `${((sweepEnds - landsAt) / 1000).toFixed(0)}s from first row to last`)
check('a row is dark until its own cost lands',
      cascade.glow(accrualDept, litAt(accrualDept) - 1000) === 0
      && cascade.glow(accrualDept, litAt(accrualDept) + 250) > 0.9)
check('every row that takes an accrual is still lit when the clock stops',
      accruals.every((e) => cascade.glow(e.dept, periodEnd) > 0),
      accruals.map((e) => `${e.dept} ${cascade.glow(e.dept, periodEnd).toFixed(2)}`).join(' · '))
check('a department with nothing landing stays dark at the close',
      costs.departments.filter((d) => !expenses.some((e) => e.dept === d.id))
        .every((d) => cascade.glow(d.id, periodEnd) === 0))

// §12: "cascade down the waterfall" -- the rows light in panel order.
const litOrder = [...new Set(accruals.map((e) => e.dept))]
  .sort((a, b) => litAt(a) - litAt(b))
  .map((dept) => order.indexOf(dept))
check('the sweep runs down the panel, not in file order',
      litOrder.every((row, i) => i === 0 || row >= litOrder[i - 1]),
      litOrder.join(' → '))

// An invoice mid-month has room for the full glow and does fade.
const midInvoice = invoices[Math.floor(invoices.length / 2)]
const midLit = litAt(midInvoice.dept)
void midLit
const invoiceAt = Date.parse(midInvoice.date) + order.indexOf(midInvoice.dept) * LANDING_STAGGER
check('an invoice with room to fade does fade',
      cascade.glow(midInvoice.dept, invoiceAt + LANDING_GLOW + 1000) === 0
      && cascade.glow(midInvoice.dept, invoiceAt + 1000) > 0.5)

check('the close is flagged while it is happening', cascade.closing(landsAt + 30_000))
check('...and right to the last instant of the month', cascade.closing(periodEnd))
check('...and not before it', !cascade.closing(landsAt - 60_000))
check('...and not at the start of the month',
      !cascade.closing(Date.parse(month.meta.period_start) + 3600_000))

console.log('\n--- an empty month is still a month ---')

const nothing = buildDeliveries(layout, [], order, periodEnd)
check('no expenses means no vans and no close', nothing.segments.count === 0
      && !nothing.cascade.closing(periodEnd) && nothing.cascade.accrualTotal === 0)
const onlyInvoices = new Cascade(
  invoices.map((e) => ({ dept: e.dept, at: Date.parse(e.date), amount: e.amount,
                         category: e.category, vendor: e.vendor, accrual: false })),
  order, periodEnd)
check('a month with no accruals never flags a close',
      !onlyInvoices.closing(periodEnd) && onlyInvoices.accrualTotal === 0)
check('...but its invoices still light their rows',
      onlyInvoices.glow(invoices[0].dept,
                        Date.parse(invoices[0].date)
                        + order.indexOf(invoices[0].dept) * LANDING_STAGGER + 1000) > 0.9)

console.log(failures === 0 ? '\nall delivery checks passed' : `\n${failures} FAILED`)
process.exit(failures === 0 ? 0 : 1)
