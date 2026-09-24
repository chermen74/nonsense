/**
 * §9 steps 4-7: room lighting from stays, arrival/departure movement, the
 * dining and banquet movement that tints the venue floors, and the §7 hover
 * and department-filter lookups the renderer reads.
 */

import { readFileSync } from 'node:fs'
import { expandRooms } from '../src/sim/rooms'
import {
  buildMovement, attachNights, forEachActive,
  INTENT_ARRIVING, INTENT_DEPARTING, INTENT_DINING, INTENT_BANQUET,
  intentMask, showsIntent, showsOutlets, showsFunctionRooms,
} from '../src/sim/segments'
import { buildNetwork, WALK_SPEED } from '../src/sim/paths'
import { wallClock, dayKey } from '../src/sim/tz'
import type { BanquetEvent, Check, Layout, MonthData, Stay } from '../src/types'

let failures = 0
function check(name: string, ok: boolean, detail = '') {
  if (!ok) failures++
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? ': ' + detail : ''}`)
}
function near(a: number, b: number, tol: number) { return Math.abs(a - b) <= tol }

const TZ = 'America/Los_Angeles'
const layout = JSON.parse(readFileSync('public/layout.json', 'utf8')) as Layout
const rooms = expandRooms(layout)
const net = buildNetwork(layout)

const first = rooms[0]
const stay: Stay = {
  id: 'S-TEST', room: first.number,
  arrive: '2026-08-14T16:00:00-07:00',
  depart: '2026-08-16T10:00:00-07:00',
  guests: 2, market: 'TRANSIENT',
  nights: [{ date: '2026-08-14', rate: 400 }, { date: '2026-08-15', rate: 450 }],
}
const arrive = Date.parse(stay.arrive)
const depart = Date.parse(stay.depart)

const { segments, lighting } = buildMovement(layout, rooms, { stays: [stay] })
attachNights(lighting, Date.parse('2026-08-01T00:00:00-07:00'),
             Date.parse('2026-09-01T00:00:00-07:00'),
             (d, h, m) => wallClock(d, h, m, TZ), (t) => dayKey(t, TZ))

console.log('--- §6 leg construction ---')
check('every leg has positive span',
      Array.from(segments.t1).every((t1, i) => t1 > segments.t0[i]))
check('legs are sorted by start time',
      Array.from(segments.t0).every((t, i) => i === 0 || t >= segments.t0[i - 1]))
check('the first leg spawns 4 minutes before arrival',
      near(segments.t0[0], arrive - 4 * 60_000, 1),
      new Date(segments.t0[0]).toISOString())
check('...at the entrance',
      near(segments.ax[0], layout.entrance.x, 0.01) && near(segments.az[0], layout.entrance.z, 0.01))
check('the party size rides on every leg',
      Array.from(segments.party).every((p) => p === 2))

const arrivalLegs = [...Array(segments.count).keys()].filter((i) => segments.intent[i] === INTENT_ARRIVING)
const departureLegs = [...Array(segments.count).keys()].filter((i) => segments.intent[i] === INTENT_DEPARTING)
check('arrival and departure are both built', arrivalLegs.length > 0 && departureLegs.length > 0,
      `${arrivalLegs.length} arriving, ${departureLegs.length} departing`)

console.log('\n--- §6 walk speed is 1.3 m/s ---')
let worstSpeed = 0
for (let i = 0; i < segments.count; i++) {
  const d = Math.hypot(segments.bx[i] - segments.ax[i], segments.by[i] - segments.ay[i],
                       segments.bz[i] - segments.az[i])
  if (d < 0.01) continue                                   // a dwell
  const speed = d / ((segments.t1[i] - segments.t0[i]) / 1000)
  worstSpeed = Math.max(worstSpeed, Math.abs(speed - WALK_SPEED))
}
check('every walking leg runs at 1.3 m/s', worstSpeed < 1e-6, `worst deviation ${worstSpeed.toExponential(2)}`)

console.log('\n--- §6.2 departure starts 6 minutes before checkout ---')
check('first departure leg starts at depart - 6 min',
      near(Math.min(...departureLegs.map((i) => segments.t0[i])), depart - 6 * 60_000, 1))
check('departure leaves from the room door',
      near(segments.ay[departureLegs[0]], rooms[0].floor * 3.2 - 1.4, 0.01))

console.log('\n--- §6.3 room lighting ---')
const litAt = Math.max(...arrivalLegs.map((i) => segments.t1[i]))
const darkAt = depart - 6 * 60_000
check('dark before anyone arrives', lighting.stateAt(0, arrive - 60 * 60_000) === 0)
check('still dark while the guest is walking up', lighting.stateAt(0, arrive) === 0)
check('lit once the guest reaches the door', lighting.stateAt(0, litAt + 1) === 2)
check('...and the room lights only after the whole arrival walk',
      litAt > arrive, `${Math.round((litAt - arrive) / 1000)}s after the PMS stamp`)
check('dim during the 00:00-06:30 sleep window',
      lighting.stateAt(0, Date.parse('2026-08-15T03:00:00-07:00')) === 1)
check('lit again after 06:30',
      lighting.stateAt(0, Date.parse('2026-08-15T07:00:00-07:00')) === 2)
check('dark once the guest leaves the door', lighting.stateAt(0, darkAt + 1) === 0)
check('a room nobody stayed in is never lit', lighting.stateAt(1, litAt + 1) === 0)

console.log('\n--- §6 position is a pure function of t ---')
function sample(t: number): Array<[number, number, number]> {
  const out: Array<[number, number, number]> = []
  forEachActive(segments, t, (i, u) => {
    out.push([segments.ax[i] + (segments.bx[i] - segments.ax[i]) * u,
              segments.ay[i] + (segments.by[i] - segments.ay[i]) * u,
              segments.az[i] + (segments.bz[i] - segments.az[i]) * u])
  })
  return out
}
const probe = arrive - 3 * 60_000
const a = JSON.stringify(sample(probe))
sample(depart)                                            // scrub forward
const b = JSON.stringify(sample(probe))                   // and back
check('scrubbing back to the same t gives the same position', a === b)
check('nobody is drawn long before the stay', sample(arrive - 60 * 60_000).length === 0)
check('nobody is drawn long after the stay', sample(depart + 60 * 60_000).length === 0)
check('exactly one leg is live mid-walk', sample(probe).length === 1)

// ---------------------------------------------------------------------------
// §6.4 dining and §6.5 banquets, on a scenario small enough to reason about.
// ---------------------------------------------------------------------------
console.log('\n--- §6.4 dining ---')
const outlet = layout.outlets[0]
const roomCheck: Check = {
  id: 'C-ROOM', outlet: outlet.id,
  opened: '2026-08-15T19:00:00-07:00', closed: '2026-08-15T20:30:00-07:00',
  covers: 3, food: 180, bev: 60, room: first.number,
}
const walkIn: Check = {
  id: 'C-WALKIN', outlet: outlet.id,
  opened: '2026-08-15T19:10:00-07:00', closed: '2026-08-15T20:00:00-07:00',
  covers: 2, food: 90, bev: 40, room: null,
}
const opened = Date.parse(roomCheck.opened)
const closed = Date.parse(roomCheck.closed)

const dining = buildMovement(layout, rooms, { stays: [stay], checks: [roomCheck, walkIn] })
const diningLegs = [...Array(dining.segments.count).keys()]
  .filter((i) => dining.segments.intent[i] === INTENT_DINING)
check('dining produces its own intent', diningLegs.length > 0, `${diningLegs.length} legs`)

const roomDiner = diningLegs.filter((i) => dining.segments.party[i] === 3)
const walkInDiner = diningLegs.filter((i) => dining.segments.party[i] === 2)
const earliest = (legs: number[]) =>
  legs.reduce((a, b) => (dining.segments.t0[a] <= dining.segments.t0[b] ? a : b))
const roomFirst = earliest(roomDiner)
check('a room charge leaves the room 8 minutes before the check opens',
      near(dining.segments.t0[roomFirst], opened - 8 * 60_000, 1))
check('...from the room door, not the street',
      near(dining.segments.ay[roomFirst], rooms[0].floor * 3.2 - 1.4, 0.01),
      `y = ${dining.segments.ay[roomFirst].toFixed(2)}`)
const walkInFirst = earliest(walkInDiner)
check('a walk-in spawns at the entrance 4 minutes before',
      near(dining.segments.t0[walkInFirst], Date.parse(walkIn.opened) - 4 * 60_000, 1))
check('...at the entrance itself',
      near(dining.segments.ax[walkInFirst], layout.entrance.x, 0.01) &&
      near(dining.segments.az[walkInFirst], layout.entrance.z, 0.01))

const seated = diningLegs.filter((i) => dining.segments.spread[i] === 0
                                     && dining.segments.party[i] === 3)
check('the party is seated as one dwell', seated.length === 1)
check('...and stays seated until the check closes',
      near(dining.segments.t1[seated[0]], closed, 1))
check('...at a spot inside the outlet footprint',
      Math.abs(dining.segments.ax[seated[0]] - outlet.x) <= outlet.w / 2 &&
      Math.abs(dining.segments.az[seated[0]] - outlet.z) <= outlet.d / 2)

const midMeal = opened + 45 * 60_000
check('§6.4 tint: covers on the floor are the checks seated now',
      dining.venues.covers(outlet.id, midMeal) === 5,
      `${dining.venues.covers(outlet.id, midMeal)} covers`)
check('...which is covers over seats',
      near(dining.venues.outletLoad(outlet.id, midMeal), 5 / outlet.seats, 1e-9))
check('the outlet is empty before anyone sits',
      dining.venues.covers(outlet.id, opened - 60 * 60_000) === 0)
check('...and empty again after the last check closes',
      dining.venues.covers(outlet.id, closed + 60 * 60_000) === 0)

console.log('\n--- §6.5 banquets ---')
const hall = layout.function_rooms[0]
const gala: BanquetEvent = {
  id: 'E-GALA', function_room: hall.id, name: 'Test Gala',
  start: '2026-08-15T18:00:00-07:00', end: '2026-08-15T22:00:00-07:00',
  attendees: 100, food: 9000, bev: 3000, room_rental: 2500, av: 800,
}
const huge: BanquetEvent = {
  id: 'E-HUGE', function_room: hall.id, name: 'Test Convention',
  start: '2026-08-20T09:00:00-07:00', end: '2026-08-20T17:00:00-07:00',
  attendees: 700, food: 42000, bev: 9000, room_rental: 6000, av: 2400,
}
const start = Date.parse(gala.start)
const end = Date.parse(gala.end)

const banquet = buildMovement(layout, rooms, { stays: [stay], events: [gala, huge] })
const banquetLegs = [...Array(banquet.segments.count).keys()]
  .filter((i) => banquet.segments.intent[i] === INTENT_BANQUET)
check('banquets produce their own intent', banquetLegs.length > 0, `${banquetLegs.length} legs`)

const midEvent = start + 2 * 3600_000
check('§6.5 tint: everyone invited is counted, not just those drawn',
      banquet.venues.attendees(hall.id, midEvent) === 100)
check('...which is attendees over capacity',
      near(banquet.venues.eventLoad(hall.id, midEvent), 100 / hall.capacity, 1e-9))
check('the room is empty more than 25 minutes before the doors',
      banquet.venues.attendees(hall.id, start - 26 * 60_000) === 0)
check('somebody is already in 10 minutes before the doors',
      banquet.venues.attendees(hall.id, start - 10 * 60_000) > 0)
check('the room empties within 15 minutes of the end',
      banquet.venues.attendees(hall.id, end + 15 * 60_000 + 1000) === 0)
check('an event is active between its start and end',
      banquet.venues.activeEvent(hall.id, midEvent)?.id === gala.id)
check('...and not an hour afterwards', banquet.venues.activeEvent(hall.id, end + 3600_000) === null)

const seatedAtHall = banquetLegs.filter((i) => banquet.segments.spread[i] === 0)
const galaSeats = seatedAtHall.filter((i) => banquet.segments.t0[i] <= midEvent
                                          && banquet.segments.t1[i] >= midEvent)
check('every arrival lands inside the 25-minute window',
      galaSeats.every((i) => banquet.segments.t0[i] >= start - 25 * 60_000 - 1
                          && banquet.segments.t0[i] <= start + 1))
check('every departure lands inside the 15-minute window',
      galaSeats.every((i) => banquet.segments.t1[i] >= end - 1
                          && banquet.segments.t1[i] <= end + 15 * 60_000 + 1))
const fromRooms = banquetLegs.filter((i) => banquet.segments.ay[i] > 1
                                         && banquet.segments.t0[i] < start)
check('some attendees come down from occupied rooms (§6.5: 30%)', fromRooms.length > 0,
      `${fromRooms.length} legs start above the ground floor`)

const midHuge = Date.parse(huge.start) + 3600_000
let drawnAtHuge = 0
forEachActive(banquet.segments, midHuge, (i) => {
  if (banquet.segments.intent[i] === INTENT_BANQUET) drawnAtHuge += banquet.segments.party[i]
})
check('a 700-head event draws at most 250 capsules', drawnAtHuge <= 250, `${drawnAtHuge} drawn`)
check('...while the tint still counts all 700',
      banquet.venues.attendees(hall.id, midHuge) === 700)

// ---------------------------------------------------------------------------
// §7 hover and department filter.
// ---------------------------------------------------------------------------
console.log('\n--- §7 hover: which stay is in a room ---')
check('a room names its occupant once the guest is in',
      lighting.stayAt(0, litAt + 1) === 0)
check('...and nobody before they arrive', lighting.stayAt(0, arrive - 3600_000) === -1)
check('...and nobody after they leave', lighting.stayAt(0, depart + 1) === -1)
check('a room nobody stayed in never names one', lighting.stayAt(1, litAt + 1) === -1)
check('the occupant is reported for the whole stay, night included',
      lighting.stayAt(0, Date.parse('2026-08-15T03:00:00-07:00')) === 0)

console.log('\n--- §7 department filter ---')
const all = intentMask(null)
check('no filter shows every intent',
      [INTENT_ARRIVING, INTENT_DEPARTING, INTENT_DINING, INTENT_BANQUET]
        .every((i) => showsIntent(all, i)))
const roomsOnly = intentMask('rooms')
check('Rooms shows arrivals and departures only',
      showsIntent(roomsOnly, INTENT_ARRIVING) && showsIntent(roomsOnly, INTENT_DEPARTING) &&
      !showsIntent(roomsOnly, INTENT_DINING) && !showsIntent(roomsOnly, INTENT_BANQUET))
check('Food and Beverage both show the one dining stream',
      intentMask('food') === intentMask('bev') &&
      showsIntent(intentMask('food'), INTENT_DINING) &&
      !showsIntent(intentMask('food'), INTENT_ARRIVING))
const banquetOnly = intentMask('banquet')
check('Banquet shows attendees only',
      showsIntent(banquetOnly, INTENT_BANQUET) && !showsIntent(banquetOnly, INTENT_DINING))
check('venues follow the same filter',
      showsOutlets(null) && showsFunctionRooms(null) &&
      showsOutlets('food') && !showsFunctionRooms('food') &&
      showsFunctionRooms('banquet') && !showsOutlets('banquet') &&
      !showsOutlets('rooms') && !showsFunctionRooms('rooms'))

console.log('\n--- the real August month ---')
const monthRaw = readFileSync('public/data/2026-08.json', 'utf8')
const month = JSON.parse(monthRaw) as MonthData
const t1 = Date.now()
const real = buildMovement(layout, rooms, month)
const buildMs = Date.now() - t1
attachNights(real.lighting, Date.parse(month.meta.period_start), Date.parse(month.meta.period_end),
             (d, h, m) => wallClock(d, h, m, TZ), (t) => dayKey(t, TZ))
check('every stay produced legs', real.segments.count > month.stays.length,
      `${real.segments.count.toLocaleString()} legs from ${month.stays.length.toLocaleString()} stays in ${buildMs} ms`)

const noon = Date.parse('2026-08-15T12:00:00-07:00')
let live = 0
let capsules = 0
forEachActive(real.segments, noon, (i) => { live++; capsules += real.segments.party[i] })
check('a plausible number of people are moving at midday', live > 0 && capsules < 900,
      `${live} legs · ${capsules} capsules`)

const dinner = Date.parse('2026-08-15T19:30:00-07:00')
const busiest = layout.outlets
  .map((o) => ({ id: o.id, seats: o.seats, covers: real.venues.covers(o.id, dinner) }))
  .sort((a, b) => b.covers - a.covers)[0]
check('outlets fill at dinner time', busiest.covers > 0,
      `${busiest.id}: ${busiest.covers} of ${busiest.seats} seats`)
check('...without overflowing the room',
      layout.outlets.every((o) => real.venues.outletLoad(o.id, dinner) <= 1))
check('nobody is dining at 4am',
      layout.outlets.every((o) => real.venues.covers(o.id, Date.parse('2026-08-15T04:00:00-07:00')) === 0))

const anyEvent = month.events[0]
const duringEvent = (Date.parse(anyEvent.start) + Date.parse(anyEvent.end)) / 2
check('the month\u2019s first event fills its function room',
      real.venues.attendees(anyEvent.function_room, duringEvent) === anyEvent.attendees,
      `${real.venues.attendees(anyEvent.function_room, duringEvent)} of ${anyEvent.attendees}`)
check('...and is reported active while it runs',
      real.venues.activeEvent(anyEvent.function_room, duringEvent)?.id === anyEvent.id)

const night = Date.parse('2026-08-15T03:00:00-07:00')
let dim = 0, lit = 0, dark = 0
for (let r = 0; r < rooms.length; r++) {
  const state = real.lighting.stateAt(r, night)
  if (state === 1) dim++; else if (state === 2) lit++; else dark++
}
check('at 3am occupied rooms are dim, not lit', dim > 200 && lit === 0,
      `${dim} dim · ${lit} lit · ${dark} dark`)

const evening = Date.parse('2026-08-15T21:00:00-07:00')
let litEvening = 0
for (let r = 0; r < rooms.length; r++) if (real.lighting.stateAt(r, evening) === 2) litEvening++
check('at 9pm they are lit', litEvening > 200, `${litEvening} of ${rooms.length} lit`)

let hoverMismatch = 0
for (const probe of ['2026-08-15T12:00:00-07:00', '2026-08-15T03:00:00-07:00',
                     '2026-08-20T21:00:00-07:00']) {
  const when = Date.parse(probe)
  for (let r = 0; r < rooms.length; r++) {
    const occupied = real.lighting.stateAt(r, when) !== 0
    const named = real.lighting.stayAt(r, when) >= 0
    if (occupied !== named) hoverMismatch++
  }
}
check('every lit or dim room names its occupant, and no dark one does',
      hoverMismatch === 0, `${hoverMismatch} mismatches over 1,200 room-probes`)

// Checked over every occupied room rather than one: picking a single index
// passes for free on the night it happens to be vacant.
const midday = Date.parse('2026-08-15T12:00:00-07:00')
let named = 0
let wrongRoom = 0
for (let r = 0; r < rooms.length; r++) {
  const idx = real.lighting.stayAt(r, midday)
  if (idx < 0) continue
  named++
  if (month.stays[idx].room !== rooms[r].number) wrongRoom++
}
check('...and every stay it names really is in the room it names',
      named > 150 && wrongRoom === 0, `${named} rooms named an occupant at midday, ${wrongRoom} wrong`)

const t2 = Date.now()
for (let k = 0; k < 200; k++) {
  const when = Date.parse(month.meta.period_start) + k * 3_600_000
  forEachActive(real.segments, when, () => {})
  for (let r = 0; r < rooms.length; r++) real.lighting.stateAt(r, when)
  for (const o of layout.outlets) real.venues.outletLoad(o.id, when)
  for (const f of layout.function_rooms) {
    real.venues.eventLoad(f.id, when)
    real.venues.activeEvent(f.id, when)
  }
}
check('200 frames of lookups stay well inside a frame budget', Date.now() - t2 < 400,
      `${Date.now() - t2} ms for 200 frames`)

console.log(failures === 0 ? '\nALL CHECKS PASS' : `\n${failures} CHECK(S) FAILED`)
process.exit(failures === 0 ? 0 : 1)
