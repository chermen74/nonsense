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
 * The leg primitives -- the chain builder, the packing into flat typed arrays
 * and the search for what is live at `t` -- live in `legs.ts`, because §13's
 * staff are the same machinery on different paths. This file is what the
 * guests in the month file do.
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
  roomDoor, type Network, type Point, type WingPath,
} from './paths'
import {
  bell, Chain, hash, packSegments, pushDwell, pushWalk, spotIn,
  SPREAD_DESK, SPREAD_LIFT, type Leg, type Segments,
} from './legs'

export { forEachActive, peakCapsules } from './legs'
export type { Segments } from './legs'

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

/**
 * Where a check's party sits. Exported because §13's servers walk to the table
 * the diners are actually at, and both sides must agree on which table.
 */
export function seatSpot(outlet: Outlet, checkId: string): Point {
  return spotIn(outlet, checkId, 1.2)
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

/**
 * A room falling vacant: SPEND_SPEC §13's trigger for a housekeeping turn.
 * `nextLit` is when the room is occupied again — the deadline the turn has to
 * beat, which the staff tests hold the scheduler to.
 */
export interface Departure { room: number; at: number; nextLit: number }

export function buildMovement(
  layout: Layout, rooms: Room[], input: MovementInput,
): { segments: Segments; lighting: Lighting; venues: Venues; departures: Departure[] } {
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

  const segments = packSegments(legs)

  return {
    segments,
    lighting: buildLighting(litByRoom),
    venues: Venues.build(layout, outletCovers, roomPresence, input.events ?? []),
    departures: departuresFrom(litByRoom),
  }
}

/** Every check-out in the month, in order, with the next arrival behind it. */
function departuresFrom(
  litByRoom: Array<Array<{ lit: number; dark: number; stay: number }>>,
): Departure[] {
  const out: Departure[] = []
  litByRoom.forEach((spans, room) => {
    const sorted = [...spans].sort((a, b) => a.lit - b.lit)
    sorted.forEach((span, i) => {
      out.push({ room, at: span.dark, nextLit: sorted[i + 1]?.lit ?? Infinity })
    })
  })
  return out.sort((a, b) => a.at - b.at)
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
    const seat = seatSpot(outlet, check.id)

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
