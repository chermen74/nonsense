/**
 * Movement segments and room lighting -- BUILD_SPEC §9 steps 4 and 5.
 *
 * Every stay is turned once, at load, into a list of straight legs
 * `{path: [a, b], t0, t1}` per §6. Position at time `t` is a lerp along the
 * leg, so nothing mutates on tick and scrubbing backwards stays free.
 *
 * One leg per straight line rather than one per journey: §6 interpolates a
 * segment uniformly, so a multi-corner polyline would make a guest speed up on
 * the short legs. A dwell is a leg whose two ends are the same point.
 *
 * Legs are stored in flat typed arrays sorted by `t0`. Because no leg outlasts
 * `maxDuration`, the ones live at `t` are found with a binary search for
 * `t - maxDuration` and a short forward scan -- never a walk of all 80k.
 *
 * Two readings of §6 worth naming, both so the PMS timestamps keep meaning:
 *
 *  - §6.1 spawns a guest at `arrive - 4 min` and gives the desk a 3-minute
 *    dwell. On a compact property the walk from the door takes seconds, so the
 *    dwell is held until `arrive + 3 min`: `arrive` is when the key is cut.
 *  - §6.2 starts a departure at `depart - 6 min` with a 90-second desk dwell.
 *    The dwell is likewise held until `depart`, the moment the folio settles.
 *  - §6.4 times a diner's walk from `opened - 8 min` (or `- 4 min` for a
 *    walk-in) and seats them until `closed`. §6.5 instead times a banquet
 *    attendee from the far end: the bell curve gives the moment they *reach*
 *    the function room, so the walk is laid back from there and someone coming
 *    down from a guest room leaves earlier than someone off the street.
 */

import type { BanquetEvent, Check, FunctionRoom, Layout, Outlet, Stay } from '../types'
import type { Ramp } from './accrue'
import type { Room } from './rooms'
import { Venues } from './venues'
import {
  buildNetwork, corridorEntry, corridorOutside, nearestElevator,
  roomDoor, walkTime, type Network, type Point, type WingPath,
} from './paths'

const SPAWN_BEFORE_ARRIVAL = 4 * 60_000
const DESK_DWELL_ARRIVAL = 3 * 60_000
const ELEVATOR_DWELL = 40_000
const DEPART_LEAD = 6 * 60_000
const DESK_DWELL_DEPARTURE = 90_000

/** §6.4: a room guest leaves 8 minutes before the check opens; a walk-in, 4. */
const DINING_ROOM_LEAD = 8 * 60_000
const DINING_WALKIN_LEAD = 4 * 60_000
/** A check that closes before its party can sit still gets a visible sitting. */
const MIN_SEATED = 60_000

/** §6.5: in over the 25 minutes before the doors, out over the 15 after. */
const BANQUET_IN_WINDOW = 25 * 60_000
const BANQUET_PEAK_BEFORE = 10 * 60_000
const BANQUET_OUT_WINDOW = 15 * 60_000
/** §6.5: 30% come down from rooms, the rest off the street. */
const BANQUET_FROM_ROOMS = 0.3
/** §6.5: never draw more than this many per event; the tint still counts all. */
const BANQUET_RENDER_CAP = 250

/** §6 colour by intent. */
export const INTENT_ARRIVING = 0
export const INTENT_DEPARTING = 1
export const INTENT_DINING = 2
export const INTENT_BANQUET = 3

/**
 * §7: "click a department line -> filter the scene to only that department's
 * movement". The panel's lines are revenue lines and phase 1 draws four kinds
 * of movement, so the mapping is the one below.
 *
 * Food and Beverage select the same people on purpose: they are two lines on
 * one outlet check, and the party that ordered both walked in once.
 */
export type TallyLine = 'rooms' | 'food' | 'bev' | 'banquet'

const FILTER_INTENTS: Record<TallyLine, number[]> = {
  rooms: [INTENT_ARRIVING, INTENT_DEPARTING],
  food: [INTENT_DINING],
  bev: [INTENT_DINING],
  banquet: [INTENT_BANQUET],
}

/** Bit set of the intents a filter draws; all bits when nothing is filtered. */
export function intentMask(filter: TallyLine | null): number {
  if (filter === null) return 0xff
  return FILTER_INTENTS[filter].reduce((mask, intent) => mask | (1 << intent), 0)
}

export function showsIntent(mask: number, intent: number): boolean {
  return (mask & (1 << intent)) !== 0
}

/** Venues the filter keeps lit: the ones whose own movement is still drawn. */
export function showsOutlets(filter: TallyLine | null): boolean {
  return filter === null || filter === 'food' || filter === 'bev'
}

export function showsFunctionRooms(filter: TallyLine | null): boolean {
  return filter === null || filter === 'banquet'
}

export interface Segments {
  readonly count: number
  readonly t0: Float64Array
  readonly t1: Float64Array
  readonly ax: Float32Array; readonly ay: Float32Array; readonly az: Float32Array
  readonly bx: Float32Array; readonly by: Float32Array; readonly bz: Float32Array
  /** Capsules walking this leg together (§6.1: the stay's guest count). */
  readonly party: Uint8Array
  readonly intent: Uint8Array
  /** Stable per-stay jitter so a party keeps its shape leg to leg. */
  readonly jitter: Float32Array
  /**
   * How wide, in metres, parties spread across this leg. A queue at the desk
   * needs the frontage of a desk; a corridor needs a lane. Without it every
   * party waiting at the same moment occupies one point and reads as a blob.
   */
  readonly spread: Float32Array
  readonly maxDuration: number
  /** Index of the first leg that could still be live at `t`. */
  firstCandidate(t: number): number
}

/** 0 dark · 1 dim (§6.3 sleep state) · 2 lit */
export type RoomState = 0 | 1 | 2

/**
 * When each room is occupied, by whom, and whether the hour makes it a dim
 * night window. Occupancies of one room never overlap, so a lookup is one
 * binary search over that room's slice -- which is also what the §7 hover
 * tooltip needs, so the same index answers both.
 */
export class Lighting {
  private nightFrom: Float64Array = new Float64Array(0)
  private nightTo: Float64Array = new Float64Array(0)

  constructor(
    readonly roomCount: number,
    private readonly lit: Float64Array,
    private readonly dark: Float64Array,
    private readonly start: Uint32Array,
    private readonly length: Uint16Array,
    /** Index into the month's `stays` for each occupancy span. */
    private readonly stay: Int32Array,
  ) {}

  /**
   * §6.3: the 00:00-06:30 local windows, precomputed for the period so the
   * per-frame check is a binary search rather than a timezone conversion.
   */
  setNights(from: ArrayLike<number>, to: ArrayLike<number>): void {
    this.nightFrom = Float64Array.from(from)
    this.nightTo = Float64Array.from(to)
  }

  private isNight(t: number): boolean {
    const { nightFrom, nightTo } = this
    let lo = 0
    let hi = nightFrom.length - 1
    let found = -1
    while (lo <= hi) {
      const mid = (lo + hi) >> 1
      if (nightFrom[mid] <= t) { found = mid; lo = mid + 1 } else { hi = mid - 1 }
    }
    return found >= 0 && t < nightTo[found]
  }

  /** The occupancy span covering `t` for this room, or -1. */
  private spanAt(roomIndex: number, t: number): number {
    const from = this.start[roomIndex]
    const count = this.length[roomIndex]
    if (count === 0) return -1
    let lo = from
    let hi = from + count - 1
    let found = -1
    while (lo <= hi) {
      const mid = (lo + hi) >> 1
      if (this.lit[mid] <= t) { found = mid; lo = mid + 1 } else { hi = mid - 1 }
    }
    return found >= 0 && t < this.dark[found] ? found : -1
  }

  stateAt(roomIndex: number, t: number): RoomState {
    if (this.spanAt(roomIndex, t) < 0) return 0
    return this.isNight(t) ? 1 : 2
  }

  /** §7 hover: which stay is in this room now, as an index into `stays`. */
  stayAt(roomIndex: number, t: number): number {
    const span = this.spanAt(roomIndex, t)
    return span < 0 ? -1 : this.stay[span]
  }
}

interface Leg {
  t0: number; t1: number; a: Point; b: Point
  party: number; intent: number; jitter: number; spread: number
}

/** Walking parties keep to a lane, so they pass without overlapping. */
const SPREAD_WALK = 1.6
/** The desk has a frontage; arrivals queue across it. */
const SPREAD_DESK = 9
/** A lift lobby holds a small crowd. */
const SPREAD_LIFT = 3.5

function pushWalk(out: Leg[], t: number, a: Point, b: Point,
                  party: number, intent: number, jitter: number): number {
  const dt = walkTime(a, b) * 1000
  out.push({ t0: t, t1: t + dt, a, b, party, intent, jitter, spread: SPREAD_WALK })
  return t + dt
}

function pushDwell(out: Leg[], t: number, at: Point, ms: number,
                   party: number, intent: number, jitter: number, spread: number): number {
  out.push({ t0: t, t1: t + ms, a: at, b: at, party, intent, jitter, spread })
  return t + ms
}

/**
 * Deterministic per-record jitter in [0, 1); the same record always spreads the
 * same way. `salt` draws independent values from one id -- a seat's x and its
 * z, say -- so nothing in the scene needs a random number generator and a
 * reload puts everyone back exactly where they were.
 */
function hash(id: string, salt = 0): number {
  let h = 2166136261 ^ salt
  for (let i = 0; i < id.length; i++) {
    h ^= id.charCodeAt(i)
    h = Math.imul(h, 16777619)
  }
  return ((h >>> 0) % 1000) / 1000
}

/**
 * A bell-shaped draw in [0, 1): three independent hashes averaged. §6.5 wants
 * arrivals to bunch around a peak rather than dribble in evenly, and the mean
 * of three uniforms is the cheapest thing that does that.
 */
function bell(id: string): number {
  return (hash(id, 11) + hash(id, 22) + hash(id, 33)) / 3
}

/** A deterministic spot inside a venue footprint, held clear of its walls. */
function spotIn(box: { x: number; z: number; w: number; d: number },
                id: string, inset: number): Point {
  const hw = Math.max(box.w / 2 - inset, 0.5)
  const hd = Math.max(box.d / 2 - inset, 0.5)
  return {
    x: box.x + (hash(id, 5) * 2 - 1) * hw,
    y: 0,
    z: box.z + (hash(id, 7) * 2 - 1) * hd,
  }
}

/**
 * A journey being assembled leg by leg.
 *
 * It knows its own duration before it is placed in time, which is what §6.5
 * needs: the bell curve fixes when an attendee walks *into* the function room,
 * so the chain is laid backwards from that moment.
 */
class Chain {
  private readonly legs: Array<{ a: Point; b: Point; ms: number; spread: number }> = []
  duration = 0

  walk(a: Point, b: Point): this {
    const ms = walkTime(a, b) * 1000
    this.legs.push({ a, b, ms, spread: SPREAD_WALK })
    this.duration += ms
    return this
  }

  hold(at: Point, ms: number, spread: number): this {
    this.legs.push({ a: at, b: at, ms, spread })
    this.duration += ms
    return this
  }

  /** Writes the chain out starting at `startAt`; returns when it finishes. */
  emit(out: Leg[], startAt: number, party: number, intent: number, jitter: number): number {
    let t = startAt
    for (const leg of this.legs) {
      out.push({ t0: t, t1: t + leg.ms, a: leg.a, b: leg.b, party, intent, jitter, spread: leg.spread })
      t += leg.ms
    }
    return t
  }
}

/** Room door down to the lobby: the §6.2 route, reused by §6.4 and §6.5. */
function descend(chain: Chain, net: Network, room: Room, wing: WingPath): Chain {
  const lift = nearestElevator(net, room)
  const liftPoint: Point = { x: lift.x, y: 0, z: lift.z }
  chain.walk(roomDoor(room, wing), corridorOutside(room, wing))
  chain.walk(corridorOutside(room, wing), corridorEntry(wing, room.floor))
  chain.hold(liftPoint, ELEVATOR_DWELL, SPREAD_LIFT)
  return chain.walk(liftPoint, net.lobbyHub)
}

/** The same route back up. */
function ascend(chain: Chain, net: Network, room: Room, wing: WingPath): Chain {
  const lift = nearestElevator(net, room)
  const liftPoint: Point = { x: lift.x, y: 0, z: lift.z }
  chain.walk(net.lobbyHub, liftPoint)
  chain.hold(liftPoint, ELEVATOR_DWELL, SPREAD_LIFT)
  chain.walk(corridorEntry(wing, room.floor), corridorOutside(room, wing))
  return chain.walk(corridorOutside(room, wing), roomDoor(room, wing))
}

/**
 * What the builder reads out of the month file. Typed structurally rather than
 * as `MonthData` so a test can hand it one stay and nothing else.
 */
export interface MovementInput {
  stays: Stay[]
  checks?: Check[]
  events?: BanquetEvent[]
}

export function buildMovement(
  layout: Layout, rooms: Room[], input: MovementInput,
): { segments: Segments; lighting: Lighting; venues: Venues } {
  const net: Network = buildNetwork(layout)
  const byNumber = new Map<string, number>()
  rooms.forEach((r, i) => byNumber.set(r.number, i))

  const legs: Leg[] = []
  const litByRoom: Array<Array<{ lit: number; dark: number; stay: number }>> = rooms.map(() => [])

  input.stays.forEach((stay, stayIndex) => {
    const roomIndex = byNumber.get(stay.room)
    if (roomIndex === undefined) return            // data references a room the layout lacks
    const room = rooms[roomIndex]
    const wing = net.wings.get(room.wing)
    if (!wing) return

    const party = Math.min(Math.max(stay.guests, 1), 255)
    const jitter = hash(stay.id)
    const lift = nearestElevator(net, room)
    const liftPoint: Point = { x: lift.x, y: 0, z: lift.z }
    const entry = corridorEntry(wing, room.floor)
    const outside = corridorOutside(room, wing)
    const door = roomDoor(room, wing)

    const arrive = Date.parse(stay.arrive)
    const depart = Date.parse(stay.depart)

    // ---- §6.1 arrival -------------------------------------------------
    let t = arrive - SPAWN_BEFORE_ARRIVAL
    t = pushWalk(legs, t, net.entrance, net.frontDesk, party, INTENT_ARRIVING, jitter)
    t = pushDwell(legs, t, net.frontDesk,
                  Math.max(DESK_DWELL_ARRIVAL, arrive + DESK_DWELL_ARRIVAL - t),
                  party, INTENT_ARRIVING, jitter, SPREAD_DESK)
    t = pushWalk(legs, t, net.frontDesk, net.lobbyHub, party, INTENT_ARRIVING, jitter)
    t = pushWalk(legs, t, net.lobbyHub, liftPoint, party, INTENT_ARRIVING, jitter)
    t = pushDwell(legs, t, liftPoint, ELEVATOR_DWELL, party, INTENT_ARRIVING, jitter, SPREAD_LIFT)
    // Riding up is not drawn: §6.1 says they appear at the corridor.
    t = pushWalk(legs, t, entry, outside, party, INTENT_ARRIVING, jitter)
    t = pushWalk(legs, t, outside, door, party, INTENT_ARRIVING, jitter)
    const litAt = t                                 // §6.1 step 4: the room lights

    // ---- §6.2 departure -----------------------------------------------
    const darkAt = depart - DEPART_LEAD              // they leave the door
    let d = darkAt
    d = pushWalk(legs, d, door, outside, party, INTENT_DEPARTING, jitter)
    d = pushWalk(legs, d, outside, entry, party, INTENT_DEPARTING, jitter)
    d = pushDwell(legs, d, liftPoint, ELEVATOR_DWELL, party, INTENT_DEPARTING, jitter, SPREAD_LIFT)
    d = pushWalk(legs, d, liftPoint, net.lobbyHub, party, INTENT_DEPARTING, jitter)
    d = pushWalk(legs, d, net.lobbyHub, net.frontDesk, party, INTENT_DEPARTING, jitter)
    d = pushDwell(legs, d, net.frontDesk,
                  Math.max(DESK_DWELL_DEPARTURE, depart - d),
                  party, INTENT_DEPARTING, jitter, SPREAD_DESK)
    pushWalk(legs, d, net.frontDesk, net.entrance, party, INTENT_DEPARTING, jitter)

    if (darkAt > litAt) litByRoom[roomIndex].push({ lit: litAt, dark: darkAt, stay: stayIndex })
  })

  // ---- §6.4 dining, §6.5 banquets -------------------------------------
  const outletCovers = diningLegs(legs, net, rooms, byNumber, layout.outlets, input.checks ?? [])
  const roomPresence = banquetLegs(legs, net, rooms, litByRoom,
                                   layout.function_rooms, input.events ?? [])

  legs.sort((p, q) => p.t0 - q.t0)

  const n = legs.length
  const t0 = new Float64Array(n)
  const t1 = new Float64Array(n)
  const ax = new Float32Array(n), ay = new Float32Array(n), az = new Float32Array(n)
  const bx = new Float32Array(n), by = new Float32Array(n), bz = new Float32Array(n)
  const party = new Uint8Array(n)
  const intent = new Uint8Array(n)
  const jitter = new Float32Array(n)
  const spread = new Float32Array(n)
  let maxDuration = 0

  legs.forEach((leg, i) => {
    t0[i] = leg.t0; t1[i] = leg.t1
    ax[i] = leg.a.x; ay[i] = leg.a.y; az[i] = leg.a.z
    bx[i] = leg.b.x; by[i] = leg.b.y; bz[i] = leg.b.z
    party[i] = leg.party; intent[i] = leg.intent
    jitter[i] = leg.jitter; spread[i] = leg.spread
    const span = leg.t1 - leg.t0
    if (span > maxDuration) maxDuration = span
  })

  const segments: Segments = {
    count: n, t0, t1, ax, ay, az, bx, by, bz, party, intent, jitter, spread, maxDuration,
    firstCandidate(t: number): number {
      const floor = t - maxDuration
      let lo = 0
      let hi = n
      while (lo < hi) {
        const mid = (lo + hi) >> 1
        if (t0[mid] < floor) lo = mid + 1
        else hi = mid
      }
      return lo
    },
  }

  return {
    segments,
    lighting: buildLighting(litByRoom),
    venues: Venues.build(layout, outletCovers, roomPresence, input.events ?? []),
  }
}

/**
 * §6.4. A check with a room sends that party down from the room eight minutes
 * before it opens; a walk-in appears at the entrance four minutes before. Both
 * sit at their own spot inside the outlet until the check closes, then leave
 * the way they came.
 *
 * Returns the seated-covers keyframes per outlet, which is what tints the floor.
 */
function diningLegs(
  out: Leg[], net: Network, rooms: Room[], byNumber: Map<string, number>,
  outlets: Outlet[], checks: Check[],
): Map<string, Ramp[]> {
  const byId = new Map(outlets.map((o) => [o.id, o]))
  const covers = new Map<string, Ramp[]>(outlets.map((o) => [o.id, [] as Ramp[]]))

  for (const check of checks) {
    const outlet = byId.get(check.outlet)
    if (!outlet) continue                      // data names an outlet the layout lacks
    const opened = Date.parse(check.opened)
    const closed = Date.parse(check.closed)
    if (!Number.isFinite(opened) || !Number.isFinite(closed)) continue

    const party = Math.min(Math.max(check.covers, 1), 255)
    const jitter = hash(check.id)
    const seat = spotIn(outlet, check.id, 1.2)

    // A room charge whose room is not in the layout still ate here, so it walks
    // in off the street rather than vanishing from the floor.
    const roomIndex = check.room === null ? undefined : byNumber.get(check.room)
    const room = roomIndex === undefined ? null : rooms[roomIndex]
    const wing = room ? net.wings.get(room.wing) ?? null : null

    const inbound = new Chain()
    const outbound = new Chain()
    let startAt: number
    if (room && wing) {
      descend(inbound, net, room, wing).walk(net.lobbyHub, seat)
      ascend(outbound.walk(seat, net.lobbyHub), net, room, wing)
      startAt = opened - DINING_ROOM_LEAD
    } else {
      inbound.walk(net.entrance, net.lobbyHub).walk(net.lobbyHub, seat)
      outbound.walk(seat, net.lobbyHub).walk(net.lobbyHub, net.entrance)
      startAt = opened - DINING_WALKIN_LEAD
    }

    const satAt = inbound.emit(out, startAt, party, INTENT_DINING, jitter)
    const roseAt = Math.max(closed, satAt + MIN_SEATED)
    // Seated: no spread, so a party stays at one table rather than fanning out.
    out.push({ t0: satAt, t1: roseAt, a: seat, b: seat, party, intent: INTENT_DINING, jitter, spread: 0 })
    outbound.emit(out, roseAt, party, INTENT_DINING, jitter)

    const ramps = covers.get(outlet.id)!
    ramps.push({ t0: satAt, t1: satAt, amount: party })
    ramps.push({ t0: roseAt, t1: roseAt, amount: -party })
  }

  return covers
}

/**
 * §6.5. Attendees stream into the function room over the 25 minutes before the
 * doors, bunched 10 minutes out, 30% of them down from occupied rooms and the
 * rest off the street; they leave over the 15 minutes after the end the same
 * way. At most `BANQUET_RENDER_CAP` capsules are drawn per event, and each one
 * carries the weight of the people it stands in for so the floor tint still
 * reads the full house.
 */
function banquetLegs(
  out: Leg[], net: Network, rooms: Room[],
  litByRoom: Array<Array<{ lit: number; dark: number; stay: number }>>,
  functionRooms: FunctionRoom[], events: BanquetEvent[],
): Map<string, Ramp[]> {
  const byId = new Map(functionRooms.map((f) => [f.id, f]))
  const presence = new Map<string, Ramp[]>(functionRooms.map((f) => [f.id, [] as Ramp[]]))
  /** Where in the window the arrivals peak: 10 minutes before, of 25. */
  const peak = 1 - BANQUET_PEAK_BEFORE / BANQUET_IN_WINDOW

  for (const event of events) {
    const venue = byId.get(event.function_room)
    if (!venue) continue
    const start = Date.parse(event.start)
    const end = Date.parse(event.end)
    if (!Number.isFinite(start) || !Number.isFinite(end) || end <= start) continue

    const attendees = Math.max(Math.round(event.attendees), 0)
    if (attendees === 0) continue
    const drawn = Math.min(attendees, BANQUET_RENDER_CAP)
    const weight = attendees / drawn
    const fromRooms = Math.round(drawn * BANQUET_FROM_ROOMS)
    const occupied = occupiedAt(litByRoom, start)
    const ramps = presence.get(venue.id)!

    for (let k = 0; k < drawn; k++) {
      const id = `${event.id}#${k}`
      const jitter = hash(id)
      const spot = spotIn(venue, id, 1.5)

      const f = Math.min(Math.max(peak + (bell(id) - 0.5) * 1.5, 0.02), 0.98)
      const arriveAt = start - BANQUET_IN_WINDOW + f * BANQUET_IN_WINDOW
      const leaveAt = end + hash(id, 44) * BANQUET_OUT_WINDOW

      // Deterministic, and coprime with any plausible room count, so the
      // house guests attending come from all over the building.
      const source = k < fromRooms && occupied.length > 0
        ? rooms[occupied[(k * 7919) % occupied.length]]
        : null
      const wing = source ? net.wings.get(source.wing) ?? null : null

      const inbound = new Chain()
      const outbound = new Chain()
      if (source && wing) {
        descend(inbound, net, source, wing).walk(net.lobbyHub, spot)
        ascend(outbound.walk(spot, net.lobbyHub), net, source, wing)
      } else {
        inbound.walk(net.entrance, net.lobbyHub).walk(net.lobbyHub, spot)
        outbound.walk(spot, net.lobbyHub).walk(net.lobbyHub, net.entrance)
      }

      // Laid back from the bell curve: the draw says when they walk in, so a
      // guest coming down four floors sets off before one off the street.
      inbound.emit(out, arriveAt - inbound.duration, 1, INTENT_BANQUET, jitter)
      out.push({ t0: arriveAt, t1: leaveAt, a: spot, b: spot, party: 1, intent: INTENT_BANQUET, jitter, spread: 0 })
      outbound.emit(out, leaveAt, 1, INTENT_BANQUET, jitter)

      ramps.push({ t0: arriveAt, t1: arriveAt, amount: weight })
      ramps.push({ t0: leaveAt, t1: leaveAt, amount: -weight })
    }
  }

  return presence
}

/** Which rooms have somebody in them at `t`, for §6.5's 30% from rooms. */
function occupiedAt(
  litByRoom: Array<Array<{ lit: number; dark: number; stay: number }>>, t: number,
): number[] {
  const out: number[] = []
  litByRoom.forEach((spans, roomIndex) => {
    for (const span of spans) {
      if (span.lit <= t && t < span.dark) { out.push(roomIndex); return }
    }
  })
  return out
}

/** §6.3: lit from check-in to check-out, dimmed 00:00-06:30 local. */
function buildLighting(
  litByRoom: Array<Array<{ lit: number; dark: number; stay: number }>>,
): Lighting {
  const total = litByRoom.reduce((sum, list) => sum + list.length, 0)
  const lit = new Float64Array(total)
  const dark = new Float64Array(total)
  const start = new Uint32Array(litByRoom.length)
  const length = new Uint16Array(litByRoom.length)
  const stay = new Int32Array(total)

  let cursor = 0
  litByRoom.forEach((list, roomIndex) => {
    list.sort((a, b) => a.lit - b.lit)
    start[roomIndex] = cursor
    length[roomIndex] = list.length
    for (const span of list) {
      lit[cursor] = span.lit
      dark[cursor] = span.dark
      stay[cursor] = span.stay
      cursor++
    }
  })

  return new Lighting(litByRoom.length, lit, dark, start, length, stay)
}

/**
 * Fill in the period's local nights. Kept out of `buildLighting` so the
 * timezone helpers stay in one place and the builder stays pure.
 */
export function attachNights(
  lighting: Lighting,
  periodStart: number,
  periodEnd: number,
  wallClock: (dateISO: string, hh: number, mm: number) => number,
  dayKey: (t: number) => string,
): void {
  const from: number[] = []
  const to: number[] = []
  for (let t = periodStart - 86_400_000; t <= periodEnd + 86_400_000; t += 86_400_000) {
    const day = dayKey(t)
    from.push(wallClock(day, 0, 0))
    to.push(wallClock(day, 6, 30))
  }
  lighting.setNights(from, to)
}

/**
 * Visit every leg live at `t`. Shared by the renderer and the tests so both
 * agree on what "live" means.
 *
 * `u` is the 0..1 position along the leg; the caller lerps `a`->`b` by it.
 */
export function forEachActive(
  s: Segments,
  t: number,
  visit: (index: number, u: number) => void,
): void {
  for (let i = s.firstCandidate(t); i < s.count; i++) {
    if (s.t0[i] > t) break
    const span = s.t1[i] - s.t0[i]
    if (t > s.t1[i]) continue
    visit(i, span > 0 ? (t - s.t0[i]) / span : 0)
  }
}

/**
 * The most capsules on screen at once, anywhere in the period.
 *
 * A sweep over leg starts and ends rather than sampling: sampling can step over
 * a spike, and an InstancedMesh sized below the true peak silently drops
 * people. Cheap enough to run at load (one sort of 2n events).
 */
export function peakCapsules(s: Segments): number {
  const n = s.count
  const time = new Float64Array(n * 2)
  const delta = new Int32Array(n * 2)
  for (let i = 0; i < n; i++) {
    time[i * 2] = s.t0[i]; delta[i * 2] = s.party[i]
    time[i * 2 + 1] = s.t1[i]; delta[i * 2 + 1] = -s.party[i]
  }
  const order = Array.from({ length: n * 2 }, (_, i) => i)
    // Ends before starts at the same instant, so a handover is not double-counted.
    .sort((a, b) => time[a] - time[b] || delta[a] - delta[b])

  let live = 0
  let peak = 0
  for (const i of order) {
    live += delta[i]
    if (live > peak) peak = live
  }
  return peak
}
