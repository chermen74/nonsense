import { buildAccrual, reconcile, totalRevenue } from '../src/sim/accrue'
import type { MonthData } from '../src/types'

let failures = 0
function check(name: string, actual: number, expected: number, tol = 1e-6) {
  const ok = Math.abs(actual - expected) <= tol
  if (!ok) failures++
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}: got ${actual}, want ${expected}`)
}

const fixture: MonthData = {
  meta: {
    month: '2026-08', tz: 'America/Los_Angeles',
    period_start: '2026-08-01T00:00:00-07:00',
    period_end: '2026-09-01T00:00:00-07:00',
    source: 'fixture',
  },
  stays: [{
    id: 'S1', room: '1214', arrive: '2026-08-14T15:32:00-07:00', depart: '2026-08-16T10:48:00-07:00',
    guests: 2, market: 'TRANSIENT',
    nights: [{ date: '2026-08-14', rate: 400 }, { date: '2026-08-15', rate: 500 }],
  }],
  checks: [{
    id: 'C1', outlet: 'GRILL', opened: '2026-08-14T18:05:00-07:00', closed: '2026-08-14T19:41:00-07:00',
    covers: 3, food: 100, bev: 40, room: '1214',
  }],
  events: [{
    id: 'E1', function_room: 'BALLROOM', name: 'Fixture', start: '2026-08-15T17:00:00-07:00',
    end: '2026-08-15T23:00:00-07:00', attendees: 10, food: 6000, bev: 3000, room_rental: 1200, av: 600,
  }],
}

const a = buildAccrual(fixture, 400)
const T = (iso: string) => Date.parse(iso)

console.log('--- §4 Rooms: linear 15:00 -> 23:00 on the night\'s date ---')
check('14:59 on Aug 14 -> nothing accrued', a.through(T('2026-08-14T14:59:00-07:00')).rooms, 0)
check('15:00 exactly -> nothing yet', a.through(T('2026-08-14T15:00:00-07:00')).rooms, 0)
check('19:00 -> half of 400', a.through(T('2026-08-14T19:00:00-07:00')).rooms, 200)
check('23:00 -> full 400', a.through(T('2026-08-14T23:00:00-07:00')).rooms, 400)
check('02:00 next day -> still 400 (night 2 not started)', a.through(T('2026-08-15T02:00:00-07:00')).rooms, 400)
check('Aug 15 19:00 -> 400 + half of 500', a.through(T('2026-08-15T19:00:00-07:00')).rooms, 650)

console.log('--- §4 Outlets: whole amount lands at close ---')
check('one minute before close -> 0', a.through(T('2026-08-14T19:40:00-07:00')).food, 0)
check('at close -> 100 food', a.through(T('2026-08-14T19:41:00-07:00')).food, 100)
check('at close -> 40 bev', a.through(T('2026-08-14T19:41:00-07:00')).bev, 40)

console.log('--- §4 Banquet: each line linear start -> end ---')
const mid = T('2026-08-15T20:00:00-07:00')   // 3h into a 6h event
check('halfway -> half the food', a.through(mid).bqt_food, 3000)
check('halfway -> half the rental', a.through(mid).bqt_rental, 600)
check('after end -> full av', a.through(T('2026-08-15T23:30:00-07:00')).bqt_av, 600)

console.log('--- §4 load-time reconciliation ---')
const r = reconcile(a)
for (const l of r.lines) console.log(`   ${l.key}: accrued ${l.atPeriodEnd.toFixed(2)} vs file ${l.fileSum.toFixed(2)}  delta ${l.delta.toExponential(2)}`)
check('worst line delta at period_end', r.worst, 0, 1e-9)
check('total revenue at period_end', totalRevenue(a.through(a.periodEnd)), 400 + 500 + 100 + 40 + 6000 + 3000 + 1200 + 600, 1e-9)

console.log('--- occupancy / in-house / ADR ---')
check('before arrival -> 0 in house', a.stats(T('2026-08-14T15:00:00-07:00')).inHouseStays, 0)
check('after arrival -> 1 in house', a.stats(T('2026-08-14T16:00:00-07:00')).inHouseStays, 1)
check('after arrival -> 2 guests', a.stats(T('2026-08-14T16:00:00-07:00')).inHouseGuests, 2)
check('after departure -> 0 in house', a.stats(T('2026-08-16T11:00:00-07:00')).inHouseStays, 0)
check('occupancy with 1 of 400 sold', a.stats(T('2026-08-14T16:00:00-07:00')).occupancy, 1 / 400)
check('ADR once both nights are whole', a.stats(T('2026-08-16T00:00:00-07:00')).adr, 450)

console.log('--- scrubbing backwards is free (pure function of t) ---')
const forward = a.through(mid).bqt_food
const backward = (a.through(a.periodEnd), a.through(mid).bqt_food)
check('same t gives same value after scrubbing to the end', backward, forward)

console.log(failures === 0 ? '\nALL CHECKS PASS' : `\n${failures} CHECK(S) FAILED`)
process.exit(failures === 0 ? 0 : 1)
