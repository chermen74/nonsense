/**
 * The primitives every walker is built from — BUILD_SPEC §6, SPEND_SPEC §13.
 *
 * A journey is a list of straight legs `{a, b, t0, t1}`; position at time `t`
 * is a lerp along the leg, so nothing mutates on tick and scrubbing backwards
 * is free. A dwell is a leg whose two ends are the same point.
 *
 * Guests (§6) and staff (§13) are "the same instanced-capsule system, different
 * colour and different paths", so the packing, the search and the peak sizing
 * live here and both builders use them. What differs — who walks where, and
 * why — stays in `segments.ts` and `staff.ts`.
 *
 * Legs are stored in flat typed arrays sorted by `t0`. Because no leg outlasts
 * `maxDuration`, the ones live at `t` are found with a binary search for
 * `t - maxDuration` and a short forward scan — never a walk of all 80k.
 */

import { walkTime, type Point } from './paths'

/** Walking parties keep to a lane, so they pass without overlapping. */
export const SPREAD_WALK = 1.6
/** The desk has a frontage; arrivals queue across it. */
export const SPREAD_DESK = 9
/** A lift lobby holds a small crowd. */
export const SPREAD_LIFT = 3.5

/**
 * The longest a single dwell leg may run before it is cut into pieces.
 *
 * `firstCandidate` scans back `maxDuration`, so one ten-hour shift standing at
 * the front desk would make every frame scan ten hours of legs. Cutting a long
 * dwell into half-hour pieces keeps that window short; the capsule does not
 * move across the joins, so nothing is visible.
 */
export const DWELL_CHUNK = 30 * 60_000

export interface Leg {
  t0: number; t1: number; a: Point; b: Point
  party: number; intent: number; jitter: number; spread: number
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
  /** Stable per-record jitter so a party keeps its shape leg to leg. */
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

export function pushWalk(out: Leg[], t: number, a: Point, b: Point,
                         party: number, intent: number, jitter: number): number {
  const dt = walkTime(a, b) * 1000
  out.push({ t0: t, t1: t + dt, a, b, party, intent, jitter, spread: SPREAD_WALK })
  return t + dt
}

export function pushDwell(out: Leg[], t: number, at: Point, ms: number,
                          party: number, intent: number, jitter: number, spread: number): number {
  const end = t + ms
  for (let from = t; from < end; from += DWELL_CHUNK) {
    const to = Math.min(from + DWELL_CHUNK, end)
    out.push({ t0: from, t1: to, a: at, b: at, party, intent, jitter, spread })
  }
  if (ms <= 0) out.push({ t0: t, t1: end, a: at, b: at, party, intent, jitter, spread })
  return end
}

/**
 * Deterministic per-record jitter in [0, 1); the same record always spreads the
 * same way. `salt` draws independent values from one id — a seat's x and its
 * z, say — so nothing in the scene needs a random number generator and a
 * reload puts everyone back exactly where they were.
 */
export function hash(id: string, salt = 0): number {
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
export function bell(id: string): number {
  return (hash(id, 11) + hash(id, 22) + hash(id, 33)) / 3
}

/** A deterministic spot inside a footprint, held clear of its walls. */
export function spotIn(box: { x: number; z: number; w: number; d: number },
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
export class Chain {
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

/** Sorts the legs by start and packs them into the flat arrays. */
export function packSegments(legs: Leg[]): Segments {
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

  return {
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
