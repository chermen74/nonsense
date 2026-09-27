/**
 * SPEND_SPEC §14 / §16 step 14: what the department zoom reads.
 *
 * The zoom puts three new kinds of number on screen — a day's net, a day's
 * hours, and the names behind a line — and §16's definition of done is that
 * the numbers on screen match `accruedThrough(t)`. So the checks here are
 * mostly one question asked several ways: does each new readout add up to the
 * line it came from, and does that line still tie to the file?
 */

import { readFileSync } from 'node:fs'
import { buildCosts } from '../src/sim/costs'
import { expandRooms } from '../src/sim/rooms'
import { buildProfiles, type CostLine } from '../src/sim/profile'
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
const profiles = buildProfiles(month, layout, costs)
const periodStart = Date.parse(month.meta.period_start)
const periodEnd = Date.parse(month.meta.period_end)
const LINES: CostLine[] = ['revenue', 'cos', 'labor', 'other']

console.log('--- the daily strip is the month, split up ---')

check('every department has a profile', profiles.size === costs.departments.length,
      `${profiles.size} of ${costs.departments.length}`)

let worstDay = 0
for (const dept of costs.departments) {
  const p = profiles.get(dept.id)!
  const file = costs.fileTotals.get(dept.id)!
  const summed = p.days.reduce((s, d) => s + d.lines.profit, 0)
  worstDay = Math.max(worstDay, Math.abs(summed - file.profit))
}
check('the daily nets sum to the department\'s month', worstDay < 0.005,
      `worst ${worstDay.toExponential(2)}`)

const grill = profiles.get('GRILL')!
check('the days tile the period with no gap',
      grill.days.every((d, i) => i === 0 || d.from === grill.days[i - 1].to))
check('...starting at period_start and ending at period_end',
      grill.days[0].from === periodStart && grill.days[grill.days.length - 1].to === periodEnd)
check('...one per local day of the month', grill.days.length === 31,
      `${grill.days.length} days`)
check('a day knows which day it is',
      grill.dayAt(Date.parse('2026-08-14T18:42:00-07:00'))?.date === '2026-08-14')
check('...and the clock stopping at period_end still lands on the last day',
      grill.dayAt(periodEnd)?.date === '2026-08-31')

console.log('\n--- hours and drivers come off the file ---')

for (const id of ['GRILL', 'HSKP', 'ROOMS']) {
  const p = profiles.get(id)!
  const punched = (month.shifts ?? [])
    .filter((s) => s.dept === id)
    .reduce((sum, s) => sum + (Date.parse(s.out) - Date.parse(s.in)) / 3_600_000, 0)
  const drawn = p.days.reduce((sum, d) => sum + d.hours, 0)
  check(`${id}'s daily hours are the punched hours`, Math.abs(drawn - punched) < 0.01,
        `${drawn.toFixed(1)} h`)
}

const roomNights = new Map<string, number>()
for (const stay of month.stays) {
  for (const night of stay.nights) roomNights.set(night.date, (roomNights.get(night.date) ?? 0) + 1)
}
check('the rooms department counts occupied rooms',
      profiles.get('ROOMS')!.days.every((d) => d.driver === (roomNights.get(d.date) ?? 0)))
check('...and so does a support department that sells nothing',
      profiles.get('HSKP')!.days.every((d, i) => d.driver === profiles.get('ROOMS')!.days[i].driver))
check('an outlet counts covers', grill.days.reduce((s, d) => s + d.driver, 0)
      === month.checks.filter((c) => c.outlet === 'GRILL').reduce((s, c) => s + c.covers, 0))
check('banquets count attendees',
      profiles.get('BQT')!.days.reduce((s, d) => s + d.driver, 0)
      === month.events.reduce((s, e) => s + e.attendees, 0))

check('and each says what it is counting',
      grill.driverLabel === 'covers' && profiles.get('BQT')!.driverLabel === 'attendees'
      && profiles.get('ROOMS')!.driverLabel === 'occupied rooms'
      && profiles.get('ENG')!.driverLabel === 'occupied rooms')

console.log('\n--- the names behind a line add up to it ---')

let worstSum = 0
let checked = 0
for (const dept of costs.departments) {
  const p = profiles.get(dept.id)!
  const file = costs.fileTotals.get(dept.id)!
  for (const line of LINES) {
    if (file[line] === 0) continue
    // Everything, not the top five, because the question is whether the
    // breakdown is the line -- the panel only shows the head of it.
    const all = p.contributors(line, periodEnd, 1000).reduce((s, c) => s + c.amount, 0)
    worstSum = Math.max(worstSum, Math.abs(all - file[line]))
    checked++
  }
}
check('every line\'s contributors sum to the line at period_end', worstSum < 0.01,
      `${checked} lines, worst ${worstSum.toExponential(2)}`)

check('and nothing has been attributed before the month opens',
      costs.departments.every((d) => LINES.every((l) =>
        profiles.get(d.id)!.contributors(l, periodStart, 1000).length === 0)))

const mid = Date.parse('2026-08-14T18:42:00-07:00')
const midLabor = grill.contributors('labor', mid, 1000).reduce((s, c) => s + c.amount, 0)
check('a breakdown mid-month is that line mid-month',
      Math.abs(midLabor - costs.dept('GRILL', mid).labor) < 0.01, money(midLabor))

const markets = new Set(month.stays.map((s) => s.market))
const roomsRevenue = profiles.get('ROOMS')!.contributors('revenue', periodEnd, 1000)
check('rooms revenue breaks down by market segment',
      roomsRevenue.length === markets.size
      && roomsRevenue.every((c) => markets.has(c.name)),
      roomsRevenue.map((c) => c.name).join(', '))

const hskpRoles = new Set((month.shifts ?? []).filter((s) => s.dept === 'HSKP').map((s) => s.role))
const hskpLabor = profiles.get('HSKP')!.contributors('labor', periodEnd, 1000)
check('labor breaks down by role, with salaried named as itself',
      hskpLabor.some((c) => c.name === 'Salaried')
      && [...hskpRoles].every((r) => hskpLabor.some((c) => c.name === r)),
      hskpLabor.map((c) => c.name).join(', '))

const engVendors = new Set((month.expenses ?? []).filter((e) => e.dept === 'ENG').map((e) => e.vendor))
const engOther = profiles.get('ENG')!.contributors('other', periodEnd, 1000)
check('other expense breaks down by vendor',
      engOther.length === engVendors.size && engOther.every((c) => engVendors.has(c.name)))
check('and the panel is handed them biggest first',
      engOther.every((c, i) => i === 0 || c.amount <= engOther[i - 1].amount))
check('...five of them, unless asked otherwise',
      profiles.get('ROOMS')!.contributors('other', periodEnd).length === 5)

console.log('\n--- the zone is the department\'s own ground ---')

check('an outlet\'s zone is its own floor', grill.zone.contains(-18, 28))
check('...and not the other outlet\'s', !grill.zone.contains(22, 34))
check('...nor the ballroom\'s', !grill.zone.contains(0, -20))
check('the rooms department\'s zone covers every guest room',
      rooms.every((r) => profiles.get('ROOMS')!.zone.contains(r.x, r.z)))
check('...and a support department\'s does not',
      rooms.some((r) => !profiles.get('HSKP')!.zone.contains(r.x, r.z)))
const hskpBox = (layout.boh ?? []).find((b) => b.dept === 'HSKP')!
check('a support department\'s zone is its back-of-house box',
      profiles.get('HSKP')!.zone.contains(hskpBox.x, hskpBox.z))
check('every department stands on its own anchor',
      costs.departments.every((d) => profiles.get(d.id)!.zone.contains(d.anchor.x, d.anchor.z)))

console.log('\n--- the shapes the panel scales against ---')

for (const id of ['GRILL', 'BQT']) {
  const p = profiles.get(id)!
  check(`${id}'s peaks are the biggest day of each`,
        p.peakHours === Math.max(...p.days.map((d) => d.hours))
        && p.peakDriver === Math.max(...p.days.map((d) => d.driver))
        && Math.abs(p.peakNet - Math.max(...p.days.map((d) => Math.abs(d.lines.profit)))) < 1e-9)
}
const bqt = profiles.get('BQT')!
check('a department that only works event days has quiet days to show',
      bqt.days.some((d) => d.hours === 0) && bqt.days.some((d) => d.hours > 100))
check('and the strip has red days as well as green',
      bqt.days.some((d) => d.lines.profit < 0) && bqt.days.some((d) => d.lines.profit > 0),
      `${bqt.days.filter((d) => d.lines.profit < 0).length} red of ${bqt.days.length}`)

console.log(failures === 0 ? '\nall profile checks passed' : `\n${failures} FAILED`)
process.exit(failures === 0 ? 0 : 1)
