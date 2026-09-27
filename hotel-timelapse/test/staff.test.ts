/**
 * SPEND_SPEC §13 / §16 step 12: the staff channel.
 *
 * The scene draws whatever `buildStaff` compiles, so what is checked here is
 * the compilation: that every punch on the payroll becomes one capsule that
 * arrives at the staff entrance and leaves by it, that the room turns §13
 * describes actually happen and land on the rooms that emptied, and that
 * nobody reaches a guest-room door except along the corridor outside it.
 *
 * It is checked against the real month file rather than a fixture, because the
 * question that matters is whether one hotel's payroll and one hotel's
 * check-outs fit together — a two-shift fixture would always say yes.
 */

import { readFileSync } from 'node:fs'
import { expandRooms } from '../src/sim/rooms'
import { buildMovement, type Departure } from '../src/sim/segments'
import { forEachActive, type Segments } from '../src/sim/legs'
import {
  behaviorOf, buildStaff, staffIntentMask, STAFF_BANQUET, STAFF_COMMUTE, STAFF_DESK,
  STAFF_ENG, STAFF_HSKP, STAFF_KITCHEN, STAFF_OUTLET, STAFF_STATION, type Turns,
} from '../src/sim/staff'
import { seatSpot } from '../src/sim/segments'
import { buildNetwork, corridorOutside, roomDoor } from '../src/sim/paths'
import type { Layout, MonthData } from '../src/types'

let failures = 0
function check(name: string, ok: boolean, detail = '') {
  if (!ok) failures++
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? ': ' + detail : ''}`)
}

const layout = JSON.parse(readFileSync('public/layout.json', 'utf8')) as Layout
const month = JSON.parse(readFileSync('public/data/2026-08.json', 'utf8')) as MonthData
const rooms = expandRooms(layout)
const net = buildNetwork(layout)
const periodStart = Date.parse(month.meta.period_start)
const periodEnd = Date.parse(month.meta.period_end)

const { departures } = buildMovement(layout, rooms, month)
const built = buildStaff(layout, rooms, { ...month, periodEnd }, departures)
const staff: Segments = built.segments
const turns: Turns = built.turns
const shifts = month.shifts ?? []
const staffDoor = layout.staff_entrance!

/** Every leg, as plain objects, so the checks below read as English. */
const legs = Array.from({ length: staff.count }, (_, i) => ({
  t0: staff.t0[i], t1: staff.t1[i], intent: staff.intent[i],
  a: { x: staff.ax[i], y: staff.ay[i], z: staff.az[i] },
  b: { x: staff.bx[i], y: staff.by[i], z: staff.bz[i] },
}))
const at = (p: { x: number; y: number; z: number }, q: { x: number; y: number; z: number }) =>
  Math.hypot(p.x - q.x, p.y - q.y, p.z - q.z) < 0.01

console.log('--- everyone clocks in and out ---')

const commutes = legs.filter((l) => l.intent === STAFF_COMMUTE)
const inbound = commutes.filter((l) => at(l.a, staffDoor))
const outbound = commutes.filter((l) => at(l.b, staffDoor))
check('one walk from the staff entrance per shift', inbound.length === shifts.length,
      `${inbound.length} of ${shifts.length}`)
check('and one back to it', outbound.length === shifts.length,
      `${outbound.length} of ${shifts.length}`)
check('every commute leg touches the staff entrance at one end or the other',
      commutes.every((l) => at(l.a, staffDoor) || at(l.b, staffDoor)))
check('the walk in starts when the punch says so',
      inbound.map((l) => l.t0).sort((a, b) => a - b)
        .every((t, i) => Math.abs(t - shifts.map((s) => Date.parse(s.in)).sort((a, b) => a - b)[i]) < 1))

console.log('\n--- nobody is on the floor off the clock ---')

const earliestIn = Math.min(...shifts.map((s) => Date.parse(s.in)))
const latestOut = Math.max(...shifts.map((s) => Date.parse(s.out)))
check('no leg starts before the first punch of the month',
      legs.every((l) => l.t0 >= earliestIn - 1))
// The walk home is on the clock's far side of the last punch-out, by a walk.
check('and none runs more than a walk past the last punch-out',
      legs.every((l) => l.t1 <= latestOut + 10 * 60_000))

const live = (t: number) => {
  let n = 0
  forEachActive(staff, t, () => { n++ })
  return n
}
const punched = (t: number) =>
  shifts.filter((s) => Date.parse(s.in) <= t && t < Date.parse(s.out)).length
for (const iso of ['2026-08-10T03:00:00-07:00', '2026-08-10T10:30:00-07:00',
                   '2026-08-10T19:00:00-07:00']) {
  const t = Date.parse(iso)
  // One capsule per punch. A handful may be walking home a minute late, and a
  // dwell that ends exactly on the sample instant hands over to its next
  // chunk, so a small overhang is expected; a shortfall is not.
  check(`capsules match the payroll at ${iso.slice(11, 16)}`,
        live(t) >= punched(t) && live(t) <= punched(t) + 6,
        `${live(t)} drawn, ${punched(t)} on the clock`)
}

console.log('\n--- §13 room turns ---')

const inPeriod = departures.filter((d: Departure) => d.at < periodEnd)
check('every check-out inside the period is turned', turns.unattended === 0,
      `${inPeriod.length} check-outs, ${turns.unattended} unturned`)

let dirtyAtCheckout = 0
let cleanedLater = 0
let cleanedAfterTheNextGuest = 0
let longest = 0
for (const departure of inPeriod) {
  if (turns.stateAt(departure.room, departure.at + 1000) === 1) dirtyAtCheckout++
  // Walk forward until the tint turns; the dwell is 25 minutes plus the walk.
  let t = departure.at
  const limit = departure.at + 12 * 3600_000
  while (t < limit && turns.stateAt(departure.room, t + 1000) === 1) t += 60_000
  if (turns.stateAt(departure.room, t + 1000) === 2) cleanedLater++
  if (t > departure.nextLit) cleanedAfterTheNextGuest++
  longest = Math.max(longest, t - departure.at)
}
check('the room is dirty from the moment the guest leaves',
      dirtyAtCheckout === inPeriod.length, `${dirtyAtCheckout} of ${inPeriod.length}`)
check('and reads clean once the attendant is done',
      cleanedLater === inPeriod.length, `${cleanedLater} of ${inPeriod.length}`)
check('no room is turned after the next guest is already in it',
      cleanedAfterTheNextGuest === 0, `${cleanedAfterTheNextGuest} would be`)
check('and the slowest turn of the month still happens inside the shift that owns it',
      longest < 4 * 3600_000, `${(longest / 60_000).toFixed(0)} min at worst`)

const sample = inPeriod[Math.floor(inPeriod.length / 2)]
let flash = sample.at
while (turns.stateAt(sample.room, flash) !== 2) flash += 60_000
check('the clean tint is a flash, not a state the room keeps',
      turns.stateAt(sample.room, flash + 25 * 60_000) === 0)
check('a room that never emptied is never tinted',
      rooms.every((_, i) => inPeriod.some((d) => d.room === i)
        || turns.stateAt(i, periodStart + 12 * 3600_000) === 0))

console.log('\n--- attendants reach a door along the corridor ---')

const doors = new Map<string, number>()
rooms.forEach((room, i) => {
  const wing = net.wings.get(room.wing)
  if (!wing) return
  const d = roomDoor(room, wing)
  doors.set(`${d.x.toFixed(2)}|${d.y.toFixed(2)}|${d.z.toFixed(2)}`, i)
})
let toDoors = 0
let throughTheCorridor = 0
for (const leg of legs) {
  if (leg.intent !== STAFF_HSKP) continue
  const key = `${leg.b.x.toFixed(2)}|${leg.b.y.toFixed(2)}|${leg.b.z.toFixed(2)}`
  const room = doors.get(key)
  if (room === undefined || at(leg.a, leg.b)) continue
  toDoors++
  const wing = net.wings.get(rooms[room].wing)!
  if (at(leg.a, corridorOutside(rooms[room], wing))) throughTheCorridor++
}
check('every walk to a guest-room door comes from the corridor outside it',
      toDoors > 0 && toDoors === throughTheCorridor, `${throughTheCorridor} of ${toDoors}`)

console.log('\n--- the rest of §13 ---')

const kinds = new Set(legs.map((l) => l.intent))
for (const [name, intent] of [['front desk', STAFF_DESK], ['housekeeping', STAFF_HSKP],
                              ['kitchen', STAFF_KITCHEN], ['outlet floor', STAFF_OUTLET],
                              ['banquet', STAFF_BANQUET], ['engineering', STAFF_ENG],
                              ['back office', STAFF_STATION]] as const) {
  check(`${name} is on the floor`, kinds.has(intent))
}

const event = month.events[0]
const venueOf = (e: { function_room: string }) =>
  layout.function_rooms.find((f) => f.id === e.function_room)!
const inTheRoom2 = (e: { function_room: string }, t: number) => {
  const venue = venueOf(e)
  let n = 0
  forEachActive(staff, t, (i) => {
    if (staff.intent[i] !== STAFF_BANQUET) return
    const dx = staff.bx[i] - venue.x
    const dz = staff.bz[i] - venue.z
    if (Math.abs(dx) <= venue.w / 2 && Math.abs(dz) <= venue.d / 2) n++
  })
  return n
}
const inTheRoom = (t: number) => inTheRoom2(event, t)
const doors0 = Date.parse(event.start)
const ends = Date.parse(event.end)
check('banquet staff are in the room before the doors',
      inTheRoom(doors0 - 20 * 60_000) > 0, `${inTheRoom(doors0 - 20 * 60_000)} of them`)
check('...and through the service', inTheRoom((doors0 + ends) / 2) > 0,
      `${inTheRoom((doors0 + ends) / 2)} of them`)
// Whenever the payroll has a banquet crew on the clock during an event's
// window, somebody is in that room. Some daytime functions in this month have
// nobody on the clock at all -- the crew comes in at noon for the evening --
// and the scene must not invent people the payroll does not have.
const bqtOnClock = (t: number) => shifts.filter((s) => s.dept === 'BQT'
  && Date.parse(s.in) + 15 * 60_000 <= t && t < Date.parse(s.out)).length
let staffed = 0
let empty = 0
for (const e of month.events) {
  const from = Date.parse(e.start) - 45 * 60_000
  const to = Date.parse(e.end) + 30 * 60_000
  for (let t = from; t <= to; t += 30 * 60_000) {
    if (bqtOnClock(t) === 0) continue
    staffed++
    if (inTheRoom2(e, t) === 0) empty++
  }
}
check('every function with a crew on the clock has it in the room',
      staffed > 0 && empty === 0, `${staffed - empty} of ${staffed} sampled half-hours`)
// The department's own anchor sits inside the ballroom in this layout, so the
// count away from an event is a count of people on station, not of a service
// that never ended. What must be true is that nobody is set up at 4am.
check('...and nobody is set up in the small hours',
      inTheRoom(Date.parse('2026-08-01T04:00:00-07:00')) === 0)

const dock = layout.loading_dock!
const dockWalks = legs.filter((l) => l.intent === STAFF_ENG && at(l.b, dock) && !at(l.a, l.b))
// A month-end accrual is not a delivery, so only the invoices count.
const engInvoices = (month.expenses ?? [])
  .filter((e) => e.dept === 'ENG' && e.timing === 'invoice').length
check('an invoice on the roving department sends someone to the loading dock',
      dockWalks.length === engInvoices, `${dockWalks.length} walks, ${engInvoices} invoices`)

console.log('\n--- servers go to the table the diners are at ---')

const tables = new Set<string>()
const outletIds = new Set(layout.outlets.map((o) => o.id))
for (const check of month.checks) {
  if (!outletIds.has(check.outlet)) continue
  const outlet = layout.outlets.find((o) => o.id === check.outlet)!
  const seat = seatSpot(outlet, check.id)
  tables.add(`${seat.x.toFixed(2)}|${seat.z.toFixed(2)}`)
}
let toTables = 0
let elsewhere = 0
for (const leg of legs) {
  if (leg.intent !== STAFF_OUTLET || at(leg.a, leg.b)) continue
  const key = `${leg.b.x.toFixed(2)}|${leg.b.z.toFixed(2)}`
  if (tables.has(key)) toTables++
  else elsewhere++
}
check('most checks get a server at the open and again at the close',
      toTables / month.checks.length > 1.7,
      `${(toTables / month.checks.length).toFixed(2)} visits per check`)
check('...and between tables a server works the floor rather than standing still',
      elsewhere > 0, `${elsewhere} walks between covers`)

console.log('\n--- engineering roves two at a time ---')

const engBox = (layout.boh ?? []).find((b) => b.dept === 'ENG')!
let mostOut = 0
for (let t = periodStart; t < periodEnd; t += 7 * 3600_000) {
  let out = 0
  forEachActive(staff, t, (i) => {
    if (staff.intent[i] !== STAFF_ENG) return
    if (Math.hypot(staff.bx[i] - engBox.x, staff.bz[i] - engBox.z) > 12) out++
  })
  mostOut = Math.max(mostOut, out)
}
// Two rovers, plus at most one more away at the loading dock on an invoice.
check('never more than the two rovers away from the shop, plus a delivery',
      mostOut <= 3, `${mostOut} at the most`)

console.log('\n--- behaviour is configuration, not code ---')

const configured = (layout.departments ?? []).filter((d) => d.staff_behavior)
check('the layout says what every department does', configured.length ===
      (layout.departments ?? []).length, `${configured.length} of ${layout.departments?.length}`)
check('and the config is what is used',
      configured.every((d) => behaviorOf(d) === d.staff_behavior))
check('a department with no behaviour named falls back to what it sells',
      behaviorOf({ id: 'X', name: 'X', type: 'operated', sources: { outlets: ['A'] },
                   anchor: { x: 0, y: 0, z: 0 }, camera: { x: 0, y: 0, z: 0 } }) === 'outlet'
      && behaviorOf({ id: 'Y', name: 'Y', type: 'operated', sources: { rooms: true },
                      anchor: { x: 0, y: 0, z: 0 }, camera: { x: 0, y: 0, z: 0 } }) === 'front_desk'
      && behaviorOf({ id: 'Z', name: 'Z', type: 'undistributed',
                      anchor: { x: 0, y: 0, z: 0 }, camera: { x: 0, y: 0, z: 0 } }) === 'station')

console.log('\n--- the channel is its own ---')

check('no filter draws every kind of staff', staffIntentMask(null) === 0xff)
const roomsMask = staffIntentMask('rooms')
check('the rooms line draws the desk and housekeeping',
      (roomsMask & (1 << STAFF_DESK)) !== 0 && (roomsMask & (1 << STAFF_HSKP)) !== 0)
check('...and not the kitchen', (roomsMask & (1 << STAFF_KITCHEN)) === 0)
check('a revenue line never draws the back office',
      (['rooms', 'food', 'bev', 'banquet'] as const)
        .every((line) => (staffIntentMask(line) & (1 << STAFF_STATION)) === 0))

console.log('\n--- the frame loop can afford it ---')

check('no leg outlasts the dwell chunk, so a frame scans half an hour of them',
      staff.maxDuration <= 30 * 60_000 + 1, `${(staff.maxDuration / 60_000).toFixed(1)} min`)
const again = buildStaff(layout, rooms, { ...month, periodEnd }, departures)
check('building twice gives the same scene', again.segments.count === staff.count
      && again.segments.t0.every((v, i) => v === staff.t0[i])
      && again.segments.bx.every((v, i) => v === staff.bx[i]))

console.log(failures === 0 ? '\nall staff checks passed' : `\n${failures} FAILED`)
process.exit(failures === 0 ? 0 : 1)
