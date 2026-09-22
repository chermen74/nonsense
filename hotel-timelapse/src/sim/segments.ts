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
 */

import type { Layout } from '../types'
import type { Stay } from '../types'
import type { Room } from './rooms'
import {
  buildNetwork, corridorEntry, corridorOutside, nearestElevator,
  roomDoor, walkTime, type Network, type Point,
} from './paths'

const SPAWN_BEFORE_ARRIVAL = 4 * 60_000
const DESK_DWELL_ARRIVAL = 3 * 60_000
const ELEVATOR_DWELL = 40_000
const DEPART_LEAD = 6 * 60_000
const DESK_DWELL_DEPARTURE = 90_000

/** §6 colour by intent. Dining and banquet arrive with step 6. */
export const INTENT_ARRIVING = 0
export const INTENT_DEPARTING = 1

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
 * When each room is occupied, and whether the hour makes it a dim night
 * window. Occupancies of one room never overlap, so a lookup is one binary
 * search over that room's slice.
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

  stateAt(roomIndex: number, t: number): RoomState {
    const from = this.start[roomIndex]
    const count = this.length[roomIndex]
    if (count === 0) return 0
    let lo = from
    let hi = from + count - 1
    let found = -1
    while (lo <= hi) {
      const mid = (lo + hi) >> 1
      if (this.lit[mid] <= t) { found = mid; lo = mid + 1 } else { hi = mid - 1 }
    }
    if (found < 0 || t >= this.dark[found]) return 0
    return this.isNight(t) ? 1 : 2
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

/** Deterministic per-stay jitter; the same stay always spreads the same way. */
function hash(id: string): number {
  let h = 2166136261
  for (let i = 0; i < id.length; i++) {
    h ^= id.charCodeAt(i)
    h = Math.imul(h, 16777619)
  }
  return ((h >>> 0) % 1000) / 1000
}

export function buildMovement(
  layout: Layout, rooms: Room[], stays: Stay[],
): { segments: Segments; lighting: Lighting } {
  const net: Network = buildNetwork(layout)
  const byNumber = new Map<string, number>()
  rooms.forEach((r, i) => byNumber.set(r.number, i))

  const legs: Leg[] = []
  const litByRoom: Array<Array<{ lit: number; dark: number }>> = rooms.map(() => [])

  for (const stay of stays) {
    const roomIndex = byNumber.get(stay.room)
    if (roomIndex === undefined) continue          // data references a room the layout lacks
    const room = rooms[roomIndex]
    const wing = net.wings.get(room.wing)
    if (!wing) continue

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

    if (darkAt > litAt) litByRoom[roomIndex].push({ lit: litAt, dark: darkAt })
  }

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

  return { segments, lighting: buildLighting(litByRoom) }
}

/** §6.3: lit from check-in to check-out, dimmed 00:00-06:30 local. */
function buildLighting(litByRoom: Array<Array<{ lit: number; dark: number }>>): Lighting {
  const total = litByRoom.reduce((sum, list) => sum + list.length, 0)
  const lit = new Float64Array(total)
  const dark = new Float64Array(total)
  const start = new Uint32Array(litByRoom.length)
  const length = new Uint16Array(litByRoom.length)

  let cursor = 0
  litByRoom.forEach((list, roomIndex) => {
    list.sort((a, b) => a.lit - b.lit)
    start[roomIndex] = cursor
    length[roomIndex] = list.length
    for (const span of list) {
      lit[cursor] = span.lit
      dark[cursor] = span.dark
      cursor++
    }
  })

  return new Lighting(litByRoom.length, lit, dark, start, length)
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
