/**
 * Staff movement — SPEND_SPEC §13, build order §16 step 12.
 *
 * "Same instanced-capsule system as guests, different colour and different
 * paths." The system is `legs.ts`; this file is the paths. Every shift in the
 * month file becomes one capsule that spawns at the staff entrance when the
 * punch says so, does its department's work, and walks back out at clock-out.
 * Like everything else in the scene it is compiled once at load, so a frame is
 * a binary search and a lerp and scrubbing backwards is free.
 *
 * What a department *does* is configuration, not code. A shift only carries a
 * department id, and ids are property-specific, so the behaviour comes from
 * `layout.departments[].staff_behavior` — the same choice §10's `sources`
 * already makes, for the same reason: a property whose payroll calls
 * housekeeping something else still gets rooms turned. When the field is
 * absent the behaviour is inferred from what the department sells, which is
 * enough to place front desk, outlet and banquet staff without any config.
 *
 * Three readings of §13 worth naming:
 *
 *  - §13 says the nearest attendant walks to a departed room, and separately
 *    that attendants walk the corridors of "the wing they are assigned to".
 *    Both hold here: a turn is offered first to free attendants assigned to
 *    that room's wing, and only falls to the rest of the floor if none can
 *    take it. Assignment is a hash of the shift id mapped through the wings'
 *    share of the room count, so the big wings get proportionally more people
 *    and a reload puts everyone back where they were.
 *  - A turn that nobody can start within `MAX_WAIT` of the check-out goes
 *    unattended rather than being forced onto someone who has gone home. The
 *    room simply stays dirty until the next arrival, which is what the file
 *    says happened: the payroll did not have anyone there.
 *  - §13 triggers the loading-dock walk on an `R&M` invoice. Expense
 *    categories are property vocabulary, so the trigger here is any invoice
 *    booked to the department that roves — engineering, by behaviour. Step 13
 *    puts the vans and the categories on it.
 */

import type { BanquetEvent, Check, Department, Expense, Layout, Outlet, Shift } from '../types'
import type { Room } from './rooms'
import {
  buildNetwork, corridorEntry, walkLevel, walkTime,
  corridorOutside, roomDoor, type Network, type Point, type WingPath,
} from './paths'
import {
  hash, packSegments, pushDwell, pushWalk, spotIn, SPREAD_DESK,
  type Leg, type Segments,
} from './legs'
import { seatSpot, type Departure, type TallyLine } from './segments'

/** §13 colour channel: one intent per kind of work, never mixed with §6's. */
export const STAFF_COMMUTE = 0
export const STAFF_DESK = 1
export const STAFF_HSKP = 2
export const STAFF_KITCHEN = 3
export const STAFF_OUTLET = 4
export const STAFF_BANQUET = 5
export const STAFF_ENG = 6
export const STAFF_STATION = 7

/**
 * What a department's people do on the floor. The vocabulary is fixed; which
 * department has which behaviour is configuration.
 */
export type StaffBehavior =
  | 'front_desk' | 'rooms' | 'kitchen' | 'outlet' | 'banquet' | 'engineering' | 'station'

/** §13: the room turn takes 25 sim-minutes. */
const TURN_DWELL = 25 * 60_000
/** How long a freshly turned room reads as clean white before it is just a room. */
const CLEAN_FLASH = 20 * 60_000
/** A check-out nobody can reach within this goes unturned; see the header. */
const MAX_WAIT = 6 * 3600_000
/**
 * How much longer a turn may wait for an attendant off its own wing before the
 * wing assignment gives way. §13 wants both "the wing they are assigned to"
 * and "the nearest attendant"; on a quiet morning the first decides, and in
 * the middle of the check-out wave the second does, rather than a room sitting
 * dirty for hours while the next wing over stands idle.
 */
const WING_GRACE = 15 * 60_000

/** §13: banquet staff gather 45 minutes before the doors and stay 30 after. */
const BANQUET_BEFORE = 45 * 60_000
const BANQUET_AFTER = 30 * 60_000

/** A cook at the pass, a server at the table, an engineer at the dock. */
const PASS_DWELL = 45_000
const SERVE_DWELL = 45_000
const DOCK_DWELL = 6 * 60_000
/** §13: two engineers rove; the rest work out of the shop. */
const ROVERS = 2
/** A rover stops to look at something for this long before moving on. */
const ROVE_HOLD = 10 * 60_000

/** Corridor pacing between turns: legs per idle gap, so a quiet hour is cheap. */
const PATROL_HOPS = 4
/** Staff stand closer together than a queue of arriving guests. */
const SPREAD_STATION = 3.5
const SPREAD_FLOOR = 2.2

/** §7's department filter, for the staff channel. */
const STAFF_FILTER: Record<TallyLine, number[]> = {
  rooms: [STAFF_DESK, STAFF_HSKP],
  food: [STAFF_KITCHEN, STAFF_OUTLET],
  bev: [STAFF_KITCHEN, STAFF_OUTLET],
  banquet: [STAFF_BANQUET],
}

/**
 * Bit set of the staff intents a filter draws. A filter is a revenue line, so
 * engineering and the back office match nothing: clicking "Food" should not
 * leave the accounting clerk standing in an otherwise empty building.
 */
export function staffIntentMask(filter: TallyLine | null): number {
  if (filter === null) return 0xff
  return STAFF_FILTER[filter].reduce((mask, intent) => mask | (1 << intent), 0)
}

/** 0 nothing · 1 dirty, awaiting or under a turn · 2 just turned */
export type TurnState = 0 | 1 | 2

/**
 * When each room is dirty and when it was last turned clean. One binary search
 * per room per frame, the same shape as `Lighting`, because the renderer asks
 * both the same question.
 */
export class Turns {
  constructor(
    readonly roomCount: number,
    private readonly dirtyFrom: Float64Array,
    private readonly cleanAt: Float64Array,
    private readonly start: Uint32Array,
    private readonly length: Uint16Array,
    /** Turns that no attendant on the payroll could reach. */
    readonly unattended: number,
  ) {}

  stateAt(roomIndex: number, t: number): TurnState {
    const from = this.start[roomIndex]
    const count = this.length[roomIndex]
    if (count === 0) return 0
    let lo = from
    let hi = from + count - 1
    let found = -1
    while (lo <= hi) {
      const mid = (lo + hi) >> 1
      if (this.dirtyFrom[mid] <= t) { found = mid; lo = mid + 1 } else { hi = mid - 1 }
    }
    if (found < 0) return 0
    if (t < this.cleanAt[found]) return 1
    if (t < this.cleanAt[found] + CLEAN_FLASH) return 2
    return 0
  }
}

/** One shift's capsule, as it is being walked through its day. */
interface Duty {
  readonly shift: Shift
  readonly jitter: number
  readonly station: Point
  readonly spread: number
  /** Waypoints this person paces between when they have nothing to do. */
  readonly patrol: Point[]
  /** How long they stop at each waypoint, and how many hops one gap may cost. */
  hold: number
  hops: number
  readonly intent: number
  /** Housekeeping only: the wing whose corridors are theirs. */
  wing: string | null
  readonly arrival: number
  readonly out: number
  /** When the capsule is next free, and where it is standing then. */
  t: number
  at: Point
}

export interface StaffInput {
  shifts?: Shift[]
  checks?: Check[]
  events?: BanquetEvent[]
  expenses?: Expense[]
  /**
   * The end of the replayed period. Guests still in house at `period_end`
   * check out in the following month, which this month's payroll does not
   * cover; those turns are not this month's to staff, and the clock never
   * reaches them anyway.
   */
  periodEnd?: number
}

/** What §10 sells tells us what its people do, when config does not say. */
export function behaviorOf(dept: Department): StaffBehavior {
  if (dept.staff_behavior) return dept.staff_behavior
  if (dept.sources?.outlets?.length) return 'outlet'
  if (dept.sources?.function_rooms?.length) return 'banquet'
  if (dept.sources?.rooms) return 'front_desk'
  return 'station'
}

/** The point on a back-of-house box's edge that faces the guest side. */
function passPoint(box: { x: number; z: number; w: number; d: number }, toward: Point): Point {
  const dx = toward.x - box.x
  const dz = toward.z - box.z
  const scale = Math.max(Math.abs(dx) / (box.w / 2), Math.abs(dz) / (box.d / 2), 1e-6)
  return { x: box.x + dx / scale, y: 0, z: box.z + dz / scale }
}

/** The far end of a wing's corridor on one floor, as a walkable point. */
function corridorFar(wing: WingPath, floor: number): Point {
  return { x: wing.far.x, y: walkLevel(floor, wing.floorHeight), z: wing.far.z }
}

/**
 * Walk or pace until `until`, then stand still for whatever is left.
 *
 * Idle time is where a scene full of staff either reads as a working hotel or
 * as a car park of stationary capsules, so everyone has somewhere to pace:
 * housekeeping the corridor of their wing, engineering the whole property, and
 * the rest their own patch of floor.
 */
function idleUntil(legs: Leg[], duty: Duty, until: number): void {
  const { patrol } = duty
  let hop = 0
  while (patrol.length > 0 && hop < duty.hops && until - duty.t > 1000) {
    const next = patrol[(hop + Math.floor(duty.jitter * patrol.length)) % patrol.length]
    const ms = walkTime(duty.at, next) * 1000
    if (duty.t + ms + duty.hold > until) break
    pushWalk(legs, duty.t, duty.at, next, 1, duty.intent, duty.jitter)
    duty.t += ms
    duty.at = next
    if (duty.hold > 0) duty.t = pushDwell(legs, duty.t, duty.at, duty.hold, 1,
                                          duty.intent, duty.jitter, SPREAD_FLOOR)
    hop++
  }
  if (until > duty.t) {
    pushDwell(legs, duty.t, duty.at, until - duty.t, 1, duty.intent, duty.jitter, duty.spread)
    duty.t = until
  }
}

/** Go somewhere, stand there a while, and be free again. */
function errand(legs: Leg[], duty: Duty, setOff: number, to: Point, dwell: number,
                spread: number): number {
  idleUntil(legs, duty, setOff)
  const ms = walkTime(duty.at, to) * 1000
  pushWalk(legs, duty.t, duty.at, to, 1, duty.intent, duty.jitter)
  duty.t += ms
  duty.at = to
  duty.t = pushDwell(legs, duty.t, duty.at, dwell, 1, duty.intent, duty.jitter, spread)
  return duty.t
}

/** The free duty that could start soonest, preferring the nearest one. */
function soonest(duties: Duty[], at: number, needs: number, near: Point | null): Duty | null {
  let best: Duty | null = null
  let bestStart = Infinity
  let bestDistance = Infinity
  for (const duty of duties) {
    const start = Math.max(at, duty.t)
    if (start + needs > duty.out) continue
    const distance = near
      ? Math.hypot(duty.at.x - near.x, duty.at.y - near.y, duty.at.z - near.z)
      : 0
    if (start < bestStart - 1 || (start < bestStart + 1 && distance < bestDistance)) {
      best = duty
      bestStart = start
      bestDistance = distance
    }
  }
  return best
}

export function buildStaff(
  layout: Layout, rooms: Room[], input: StaffInput, departures: Departure[],
): { segments: Segments; turns: Turns } {
  const net = buildNetwork(layout)
  const staffDoor: Point = layout.staff_entrance
    ? { ...layout.staff_entrance } : { ...layout.entrance }
  const dock: Point = layout.loading_dock ? { ...layout.loading_dock } : staffDoor
  const departments = layout.departments ?? []
  const boxByDept = new Map((layout.boh ?? []).map((b) => [b.dept, b]))
  const outletById = new Map(layout.outlets.map((o) => [o.id, o]))
  const legs: Leg[] = []

  // ---- one duty per shift, placed at its department's station -----------
  const duties = new Map<string, Duty[]>()
  for (const dept of departments) duties.set(dept.id, [])
  const deptById = new Map(departments.map((d) => [d.id, d]))

  for (const shift of input.shifts ?? []) {
    const dept = deptById.get(shift.dept)
    if (!dept) continue                            // payroll names a department the layout lacks
    const behavior = behaviorOf(dept)
    const box = boxByDept.get(dept.id)
    const jitter = hash(shift.id)
    const anchorBox = box ?? { x: dept.anchor.x, z: dept.anchor.z, w: 6, d: 6 }
    const station: Point = behavior === 'front_desk'
      ? { ...layout.front_desk }
      : spotIn(anchorBox, shift.id, 1.0)
    const inAt = Date.parse(shift.in)
    const outAt = Date.parse(shift.out)
    if (!Number.isFinite(inAt) || !Number.isFinite(outAt) || outAt <= inAt) continue

    const duty: Duty = {
      shift, jitter, station,
      spread: behavior === 'front_desk' ? SPREAD_DESK : SPREAD_STATION,
      patrol: [], hold: 0, hops: PATROL_HOPS,
      intent: intentFor(behavior),
      wing: null,
      arrival: inAt + walkTime(staffDoor, station) * 1000,
      out: outAt,
      t: 0, at: station,
    }
    // §13 clock-in: spawn at the staff entrance, walk to the department.
    pushWalk(legs, inAt, staffDoor, station, 1, STAFF_COMMUTE, jitter)
    duty.t = duty.arrival
    duties.get(dept.id)!.push(duty)
  }

  // ---- each department's own day ---------------------------------------
  const turnsByRoom: Array<Array<{ from: number; clean: number }>> = rooms.map(() => [])
  let unattended = 0
  const horizon = input.periodEnd ?? Infinity
  const inPeriod = departures.filter((d) => d.at < horizon)

  for (const dept of departments) {
    const list = duties.get(dept.id)!
    if (list.length === 0) continue
    list.sort((a, b) => a.arrival - b.arrival)
    switch (behaviorOf(dept)) {
      case 'rooms':
        unattended += housekeeping(legs, list, dept, rooms, net, inPeriod, turnsByRoom)
        break
      case 'kitchen': {
        const box = boxByDept.get(dept.id)
        const pass = box ? passPoint(box, net.lobbyHub) : { ...dept.anchor }
        // A kitchen is not a car park: between orders a cook moves between
        // two stations of their own inside the box.
        if (box) for (const duty of list) {
          duty.patrol.push(spotIn(box, `${duty.shift.id}~1`, 1.0),
                           spotIn(box, `${duty.shift.id}~2`, 1.0))
        }
        // §13: a cook steps forward to the pass on each check open.
        for (const check of sortedBy(input.checks ?? [], (c) => Date.parse(c.opened))) {
          const cook = soonest(list, Date.parse(check.opened), PASS_DWELL, null)
          if (cook) errand(legs, cook, Date.parse(check.opened), pass, PASS_DWELL, SPREAD_FLOOR)
        }
        break
      }
      case 'outlet':
        outletFloor(legs, list, dept, outletById, input.checks ?? [])
        break
      case 'banquet':
        banquetFloor(legs, list, dept, layout, input.events ?? [])
        break
      case 'engineering':
        engineering(legs, list, dept, layout, net, dock, input.expenses ?? [])
        break
      default:
        break
    }
    // Whatever is left of the shift is spent on station, then §13's clock-out.
    for (const duty of list) {
      idleUntil(legs, duty, Math.max(duty.out, duty.t))
      pushWalk(legs, duty.t, duty.at, staffDoor, 1, STAFF_COMMUTE, duty.jitter)
    }
  }

  return { segments: packSegments(legs), turns: packTurns(turnsByRoom, unattended) }
}

function intentFor(behavior: StaffBehavior): number {
  switch (behavior) {
    case 'front_desk': return STAFF_DESK
    case 'rooms': return STAFF_HSKP
    case 'kitchen': return STAFF_KITCHEN
    case 'outlet': return STAFF_OUTLET
    case 'banquet': return STAFF_BANQUET
    case 'engineering': return STAFF_ENG
    default: return STAFF_STATION
  }
}

function sortedBy<T>(rows: T[], key: (row: T) => number): T[] {
  return [...rows].sort((a, b) => key(a) - key(b))
}

/**
 * §13 housekeeping. Attendants are spread across the wings in proportion to
 * the rooms in them and pace their wing's corridors; a check-out is offered to
 * the nearest free attendant on that wing, who walks to the door and dwells 25
 * sim-minutes while the room tint turns.
 *
 * Returns the number of turns nobody could take.
 */
function housekeeping(
  legs: Leg[], list: Duty[], dept: Department, rooms: Room[], net: Network,
  departures: Departure[], turnsByRoom: Array<Array<{ from: number; clean: number }>>,
): number {
  const wings = [...net.wings.values()]
  if (wings.length === 0) return departures.length

  // Wings in proportion to the rooms they hold, so the big wing gets the staff.
  const perWing = new Map<string, number>()
  for (const room of rooms) perWing.set(room.wing, (perWing.get(room.wing) ?? 0) + 1)
  const total = rooms.length || 1
  const pickWing = (r: number): WingPath => {
    let acc = 0
    for (const wing of wings) {
      acc += (perWing.get(wing.id) ?? 0) / total
      if (r < acc) return wing
    }
    return wings[wings.length - 1]
  }

  const turners = dept.turn_roles
    ? list.filter((d) => dept.turn_roles!.includes(d.shift.role))
    : list

  for (const duty of list) {
    const wing = pickWing(hash(duty.shift.id, 3))
    const floors = rooms.filter((r) => r.wing === wing.id)
      .reduce((max, r) => Math.max(max, r.floor), 1)
    const floor = 1 + Math.floor(hash(duty.shift.id, 9) * floors)
    duty.wing = wing.id
    duty.patrol.push(corridorEntry(wing, floor), corridorFar(wing, floor))
  }

  let unattended = 0
  for (const departure of departures) {
    const room = rooms[departure.room]
    const wing = net.wings.get(room.wing)
    if (!wing) { unattended++; continue }
    const door = roomDoor(room, wing)
    const onWing = soonest(turners.filter((d) => d.wing === room.wing),
                           departure.at, TURN_DWELL, door)
    const anyone = soonest(turners, departure.at, TURN_DWELL, door)
    const startOf = (duty: Duty | null) => duty ? Math.max(departure.at, duty.t) : Infinity
    const attendant = startOf(onWing) <= startOf(anyone) + WING_GRACE ? onWing : anyone
    if (!attendant || startOf(attendant) > departure.at + MAX_WAIT) {
      unattended++
      continue
    }

    const setOff = Math.max(departure.at, attendant.t)
    idleUntil(legs, attendant, setOff)
    // Up to the floor, along the corridor, then the door: never through a wall.
    const entry = corridorEntry(wing, room.floor)
    const outside = corridorOutside(room, wing)
    let t = attendant.t
    if (Math.hypot(attendant.at.x - entry.x, attendant.at.y - entry.y,
                   attendant.at.z - entry.z) > 0.5) {
      t = pushWalk(legs, t, attendant.at, entry, 1, attendant.intent, attendant.jitter)
    }
    t = pushWalk(legs, t, entry, outside, 1, attendant.intent, attendant.jitter)
    t = pushWalk(legs, t, outside, door, 1, attendant.intent, attendant.jitter)
    const clean = pushDwell(legs, t, door, TURN_DWELL, 1, attendant.intent,
                            attendant.jitter, SPREAD_FLOOR)
    attendant.t = clean
    attendant.at = door
    turnsByRoom[departure.room].push({ from: departure.at, clean })
  }
  return unattended
}

/**
 * §13 outlet floor: a server walks to the table when the check opens and again
 * when it closes, and to the same table the diners are sitting at.
 */
function outletFloor(
  legs: Leg[], list: Duty[], dept: Department,
  outletById: Map<string, Outlet>, checks: Check[],
): void {
  const mine = new Set(dept.sources?.outlets ?? [])
  // Between tables a server works the floor rather than standing where the
  // last check left them.
  for (const outlet of outletById.values()) {
    if (!mine.has(outlet.id)) continue
    for (const duty of list) {
      duty.patrol.push(spotIn(outlet, `${duty.shift.id}~1`, 1.2),
                       spotIn(outlet, `${duty.shift.id}~2`, 1.2))
    }
  }
  const trips: Array<{ at: number; to: Point }> = []
  for (const check of checks) {
    if (!mine.has(check.outlet)) continue
    const outlet = outletById.get(check.outlet)
    if (!outlet) continue
    const seat = seatSpot(outlet, check.id)
    const opened = Date.parse(check.opened)
    const closed = Date.parse(check.closed)
    if (Number.isFinite(opened)) trips.push({ at: opened, to: seat })
    if (Number.isFinite(closed)) trips.push({ at: closed, to: seat })
  }
  trips.sort((a, b) => a.at - b.at)
  for (const trip of trips) {
    const server = soonest(list, trip.at, SERVE_DWELL, trip.to)
    if (server) errand(legs, server, trip.at, trip.to, SERVE_DWELL, SPREAD_FLOOR)
  }
}

/**
 * §13 banquets: in the room 45 minutes before the doors, out 30 after the end.
 *
 * §13 does not say how one banquet crew is split between two functions
 * running at the same time, so the split here is by attendees: an event takes
 * its share of the people free during its window, and the biggest room gets
 * the most bodies. Events that do not overlap each take everyone, because the
 * same crew really does turn the room over and work the next one.
 */
function banquetFloor(
  legs: Leg[], list: Duty[], dept: Department, layout: Layout, events: BanquetEvent[],
): void {
  const mine = dept.sources?.function_rooms
  const rooms = new Map(layout.function_rooms
    .filter((f) => !mine || mine.includes(f.id))
    .map((f) => [f.id, f]))

  const windows = events.map((event) => ({
    event,
    from: Date.parse(event.start) - BANQUET_BEFORE,
    to: Date.parse(event.end) + BANQUET_AFTER,
  })).filter((w) => Number.isFinite(w.from) && Number.isFinite(w.to))
  windows.sort((a, b) => a.from - b.from)

  for (const window of windows) {
    const venue = rooms.get(window.event.function_room)
    if (!venue) continue
    const alongside = windows.filter((w) => w.from < window.to && w.to > window.from)
    const attendees = alongside.reduce((sum, w) => sum + Math.max(w.event.attendees, 0), 0)
    const share = attendees > 0 ? Math.max(window.event.attendees, 0) / attendees : 1

    const free = list.filter((duty) => Math.min(window.to, duty.out)
      - Math.max(window.from, duty.t) >= 60_000)
    const take = Math.max(1, Math.round(free.length * share))

    for (const duty of free.slice(0, take)) {
      const start = Math.max(window.from, duty.t)
      const finish = Math.min(window.to, duty.out)
      const spot = spotIn(venue, `${duty.shift.id}@${window.event.id}`, 1.5)
      idleUntil(legs, duty, start)
      const ms = walkTime(duty.at, spot) * 1000
      pushWalk(legs, duty.t, duty.at, spot, 1, duty.intent, duty.jitter)
      duty.t += ms
      duty.at = spot
      if (finish > duty.t) {
        duty.t = pushDwell(legs, duty.t, spot, finish - duty.t, 1, duty.intent,
                           duty.jitter, SPREAD_FLOOR)
      }
      // Back to the department; the tail of the shift is filled by the caller.
      pushWalk(legs, duty.t, duty.at, duty.station, 1, duty.intent, duty.jitter)
      duty.t += walkTime(duty.at, duty.station) * 1000
      duty.at = duty.station
    }
  }
}

/**
 * §13 engineering: two people rove the property at a time, the rest work out
 * of the shop, and an invoice booked to the department sends someone to the
 * loading dock.
 */
function engineering(
  legs: Leg[], list: Duty[], dept: Department, layout: Layout, net: Network,
  dock: Point, expenses: Expense[],
): void {
  const loop: Point[] = [{ ...net.lobbyHub }]
  for (const wing of net.wings.values()) loop.push(corridorEntry(wing, 1))
  for (const outlet of layout.outlets) loop.push({ x: outlet.x, y: 0, z: outlet.z })
  for (const room of layout.function_rooms) loop.push({ x: room.x, y: 0, z: room.z })

  // Roving is a standing post, not a person: at most two are out at once, and
  // it goes to whoever clocked in while a post was free.
  const busyUntil: number[] = []
  for (const duty of list) {
    const free = busyUntil.filter((end) => end > duty.arrival).length
    if (free >= ROVERS) continue
    busyUntil.push(duty.out)
    duty.patrol.push(...loop)
    duty.hold = ROVE_HOLD
    duty.hops = loop.length * 8
  }

  // An invoice arrived; a month-end accrual did not, and §16 step 13 is where
  // the cascade at the close belongs.
  const invoices = expenses.filter((e) => e.dept === dept.id && e.timing === 'invoice')
  for (const expense of sortedBy(invoices, (e) => Date.parse(e.date))) {
    const at = Date.parse(expense.date)
    if (!Number.isFinite(at)) continue
    const engineer = soonest(list, at, DOCK_DWELL, dock)
    if (engineer) errand(legs, engineer, at, dock, DOCK_DWELL, SPREAD_FLOOR)
  }
}

function packTurns(
  byRoom: Array<Array<{ from: number; clean: number }>>, unattended: number,
): Turns {
  const total = byRoom.reduce((sum, list) => sum + list.length, 0)
  const dirtyFrom = new Float64Array(total)
  const cleanAt = new Float64Array(total)
  const start = new Uint32Array(byRoom.length)
  const length = new Uint16Array(byRoom.length)

  let cursor = 0
  byRoom.forEach((list, room) => {
    list.sort((a, b) => a.from - b.from)
    start[room] = cursor
    length[room] = list.length
    for (const turn of list) {
      dirtyFrom[cursor] = turn.from
      cleanAt[cursor] = turn.clean
      cursor++
    }
  })
  return new Turns(byRoom.length, dirtyFrom, cleanAt, start, length, unattended)
}
