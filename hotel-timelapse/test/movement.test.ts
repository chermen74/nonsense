/**
 * §9 steps 4 and 5: room lighting from stays, and arrival/departure movement.
 */

import { readFileSync } from 'node:fs'
import { expandRooms } from '../src/sim/rooms'
import { buildMovement, attachNights, forEachActive, INTENT_ARRIVING, INTENT_DEPARTING } from '../src/sim/segments'
import { buildNetwork, WALK_SPEED } from '../src/sim/paths'
import { wallClock, dayKey } from '../src/sim/tz'
import type { Layout, Stay } from '../src/types'

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

const { segments, lighting } = buildMovement(layout, rooms, [stay])
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

console.log('\n--- the real August month ---')
const monthRaw = readFileSync('public/data/2026-08.json', 'utf8')
const month = JSON.parse(monthRaw) as { stays: Stay[]; meta: { period_start: string; period_end: string } }
const t1 = Date.now()
const real = buildMovement(layout, rooms, month.stays)
const buildMs = Date.now() - t1
attachNights(real.lighting, Date.parse(month.meta.period_start), Date.parse(month.meta.period_end),
             (d, h, m) => wallClock(d, h, m, TZ), (t) => dayKey(t, TZ))
check('every stay produced legs', real.segments.count > month.stays.length,
      `${real.segments.count.toLocaleString()} legs from ${month.stays.length.toLocaleString()} stays in ${buildMs} ms`)

const noon = Date.parse('2026-08-15T12:00:00-07:00')
let live = 0
let capsules = 0
forEachActive(real.segments, noon, (i) => { live++; capsules += real.segments.party[i] })
check('a plausible number of people are moving at midday', live > 0 && capsules < 400,
      `${live} legs · ${capsules} capsules`)

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

const t2 = Date.now()
for (let k = 0; k < 200; k++) {
  const when = Date.parse(month.meta.period_start) + k * 3_600_000
  forEachActive(real.segments, when, () => {})
  for (let r = 0; r < rooms.length; r++) real.lighting.stateAt(r, when)
}
check('200 frames of lookups stay well inside a frame budget', Date.now() - t2 < 400,
      `${Date.now() - t2} ms for 200 frames`)

console.log(failures === 0 ? '\nALL CHECKS PASS' : `\n${failures} CHECK(S) FAILED`)
process.exit(failures === 0 ? 0 : 1)
