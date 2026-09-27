/**
 * Deliveries and the month-end cascade — SPEND_SPEC §12, build order §16
 * step 13.
 *
 * §12 gives every "other expense" record a picture. An invoice is a van at the
 * loading dock and a box that travels from there to the department that will
 * carry the cost. An accrual is not a delivery at all — nothing arrived — so
 * it gets the other picture §12 asks for: at the close, the remaining accruals
 * land as a visible cascade down the waterfall.
 *
 * Neither changes a number. §12's accrual table is locked: an expense lands
 * whole at its `date`, invoice and accrual alike, and `costs.ts` already does
 * that. What is here is only the picture, and like the rest of the scene it is
 * a pure function of `t` — the van's position and the row's glow are both
 * looked up, never accumulated, so scrubbing backwards replays the close.
 */

import type { Expense, Layout } from '../types'
import { travelTime, type Point } from './paths'
import { hash, packSegments, type Leg, type Segments } from './legs'

/** Two things are drawn from one set of legs. */
export const DELIVERY_VAN = 0
export const DELIVERY_PARCEL = 1

/** A van moves at a service-road crawl, not at walking pace. */
const VAN_SPEED = 6
/** A box is carried, so it travels at §6's walking speed. */
const PARCEL_SPEED = 1.3
/** How long the van stands at the dock with its doors open. */
const UNLOAD = 8 * 60_000
/** The box comes off the tail a couple of minutes into the unload. */
const OFF_THE_TAIL = 2 * 60_000
/** Where the van comes in from, measured back from the dock. */
const APPROACH = 60

/**
 * How long a department's row stays lit after an expense lands on it, and how
 * far apart the rows light. §12 wants a cascade *down* the waterfall, so the
 * stagger is by the row's place in the panel.
 *
 * Both are ceilings, not promises. The month's accruals land at 23:59 on the
 * last night and the clock stops dead at `period_end` sixty seconds later, so
 * a four-minute stagger would run the whole sweep into time the scene never
 * reaches — the cascade §12 says must not be hidden would never be seen at
 * all. `Cascade` compresses the stagger to fit what is left of the month. The
 * glow itself needs no clamp: half an hour outlasts that last minute, so
 * however fast you arrive at the close, the panel is lit when the clock stops.
 */
export const LANDING_GLOW = 30 * 60_000
export const LANDING_STAGGER = 4 * 60_000
/** At most this share of the time left in the month is spent sweeping. */
const SWEEP_SHARE = 0.45

/** One expense arriving on a department's line. */
export interface Landing {
  dept: string
  at: number
  amount: number
  category: string
  vendor: string
  accrual: boolean
}

/**
 * Which rows are lit, and what landed on them. Rows are asked once per frame,
 * so the lookup is a scan of the landings in the glow window rather than a
 * search per department — there are tens of these in a month, not thousands.
 */
export class Cascade {
  /** Landings in time order. */
  private readonly landings: Landing[]
  /** Row order, for §12's stagger down the panel. */
  private readonly row = new Map<string, number>()
  private readonly stagger: number
  private readonly periodEnd: number
  /** Each landing's place in its own sweep, top of the panel first. */
  private readonly rank = new Map<Landing, number>()
  /** Everything booked as an accrual, which all lands at the close. */
  readonly accrualTotal: number
  readonly accrualFrom: number

  constructor(landings: Landing[], order: string[], periodEnd = Infinity) {
    this.landings = [...landings].sort((a, b) => a.at - b.at)
    order.forEach((id, i) => this.row.set(id, i))
    this.periodEnd = periodEnd

    const accruals = this.landings.filter((l) => l.accrual)
    this.accrualTotal = accruals.reduce((sum, l) => sum + l.amount, 0)
    this.accrualFrom = accruals.length ? accruals[0].at : Infinity

    // Everything landing at one instant sweeps together, ranked by where its
    // row sits in the panel. Ranking rather than using the row index outright
    // is what makes the first affected row light at the landing itself: at the
    // close only the bottom four rows take anything, and a sweep that spent
    // its first six steps on rows where nothing happened would waste most of
    // the minute the month has left.
    let deepest = 1
    for (const group of groupByTime(this.landings)) {
      const rows = [...new Set(group.map((l) => this.row.get(l.dept) ?? 0))]
        .sort((a, b) => a - b)
      for (const landing of group) {
        this.rank.set(landing, rows.indexOf(this.row.get(landing.dept) ?? 0))
      }
      deepest = Math.max(deepest, rows.length - 1, 1)
    }

    const last = accruals.length ? accruals[accruals.length - 1].at : -Infinity
    const left = Number.isFinite(periodEnd) && Number.isFinite(last)
      ? Math.max(periodEnd - last, 0) : Infinity
    this.stagger = Math.min(LANDING_STAGGER, (left * SWEEP_SHARE) / deepest)
  }

  /** When this department's row lights for a landing at `at`. */
  private lightsAt(landing: Landing): number {
    return landing.at + (this.rank.get(landing) ?? 0) * this.stagger
  }

  /**
   * 0 when nothing has just landed on this department, otherwise 1 fading to 0
   * across the glow. The caller turns that into an opacity, not a number.
   */
  glow(dept: string, t: number): number {
    let strongest = 0
    for (const landing of this.landings) {
      if (landing.dept !== dept) continue
      const from = this.lightsAt(landing)
      if (from > t) break                      // landings are in time order
      const age = t - from
      if (age >= LANDING_GLOW) continue
      strongest = Math.max(strongest, 1 - age / LANDING_GLOW)
    }
    return strongest
  }

  /** §12: the close is "deliberately not hidden". True while it is happening. */
  closing(t: number): boolean {
    return t >= this.accrualFrom && t <= Math.max(this.periodEnd, this.accrualFrom)
  }
}

/** Landings sharing an instant sweep together. Input is already in time order. */
function groupByTime(landings: Landing[]): Landing[][] {
  const groups: Landing[][] = []
  for (const landing of landings) {
    const last = groups[groups.length - 1]
    if (last && last[0].at === landing.at) last.push(landing)
    else groups.push([landing])
  }
  return groups
}

/**
 * The vans and boxes for every invoice in the month, and the cascade index for
 * every expense of either kind.
 *
 * An accrual sends no van: §12's picture for it is the month-end wave, and a
 * truck rolling in at 23:59 on the last night for a utility estimate would be
 * a lie about how the close works.
 */
export function buildDeliveries(
  layout: Layout, expenses: Expense[], deptOrder: string[], periodEnd?: number,
): { segments: Segments; cascade: Cascade } {
  const dock: Point = layout.loading_dock
    ? { ...layout.loading_dock }
    : { ...(layout.staff_entrance ?? layout.entrance) }
  const lobby = layout.lobby_hub
  // The service road runs away from the guest side of the building.
  const dx = dock.x - lobby.x
  const dz = dock.z - lobby.z
  const span = Math.hypot(dx, dz) || 1
  const approach: Point = {
    x: dock.x + (dx / span) * APPROACH, y: 0, z: dock.z + (dz / span) * APPROACH,
  }

  const anchors = new Map((layout.departments ?? []).map((d) => [d.id, d.anchor]))
  const legs: Leg[] = []
  const landings: Landing[] = []

  for (const expense of expenses) {
    const at = Date.parse(expense.date)
    if (!Number.isFinite(at)) continue
    landings.push({
      dept: expense.dept, at, amount: expense.amount,
      category: expense.category, vendor: expense.vendor,
      accrual: expense.timing === 'accrual',
    })
    if (expense.timing === 'accrual') continue

    const jitter = hash(expense.id)
    // Vans queue across the dock apron rather than parking on each other.
    const bay: Point = { x: dock.x, y: 0, z: dock.z + (jitter - 0.5) * 8 }
    const inbound = travelTime(approach, bay, VAN_SPEED) * 1000
    legs.push({ t0: at - inbound, t1: at, a: approach, b: bay,
                party: 1, intent: DELIVERY_VAN, jitter, spread: 0 })
    legs.push({ t0: at, t1: at + UNLOAD, a: bay, b: bay,
                party: 1, intent: DELIVERY_VAN, jitter, spread: 0 })
    legs.push({ t0: at + UNLOAD, t1: at + UNLOAD + inbound, a: bay, b: approach,
                party: 1, intent: DELIVERY_VAN, jitter, spread: 0 })

    // §12: "box travels to the dept anchor". It is carried, so it goes at
    // walking pace; the department that pays for it is where it ends up.
    const anchor = anchors.get(expense.dept)
    if (!anchor) continue                      // an expense on a department the layout lacks
    const carried = travelTime(bay, anchor, PARCEL_SPEED) * 1000
    legs.push({ t0: at + OFF_THE_TAIL, t1: at + OFF_THE_TAIL + carried,
                a: bay, b: { x: anchor.x, y: 0, z: anchor.z },
                party: 1, intent: DELIVERY_PARCEL, jitter, spread: 0 })
  }

  return {
    segments: packSegments(legs),
    cascade: new Cascade(landings, deptOrder, periodEnd),
  }
}
