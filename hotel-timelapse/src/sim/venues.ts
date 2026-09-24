/**
 * How full each outlet and function room is at time `t` -- BUILD_SPEC §6.4 and
 * §6.5, which tint a venue's floor by its current load.
 *
 * Built on the same keyframe curve the revenue tallies use: a seated cover is
 * a step up when the party sits and a step down when it leaves, so the load at
 * any instant is a binary search, not a scan of six thousand checks.
 */

import { Curve, type Ramp } from './accrue'
import type { BanquetEvent, Layout } from '../types'

export interface ActiveEvent {
  id: string
  name: string
  attendees: number
  start: number
  end: number
}

export class Venues {
  private constructor(
    private readonly seated: Map<string, Curve>,
    private readonly seats: Map<string, number>,
    private readonly present: Map<string, Curve>,
    private readonly capacity: Map<string, number>,
    private readonly events: Map<string, ActiveEvent[]>,
  ) {}

  static build(
    layout: Layout,
    outletCovers: Map<string, Ramp[]>,
    roomPresence: Map<string, Ramp[]>,
    events: BanquetEvent[],
  ): Venues {
    const seated = new Map<string, Curve>()
    const seats = new Map<string, number>()
    for (const outlet of layout.outlets) {
      seated.set(outlet.id, Curve.build(outletCovers.get(outlet.id) ?? []))
      seats.set(outlet.id, outlet.seats)
    }

    const present = new Map<string, Curve>()
    const capacity = new Map<string, number>()
    for (const room of layout.function_rooms) {
      present.set(room.id, Curve.build(roomPresence.get(room.id) ?? []))
      capacity.set(room.id, room.capacity)
    }

    const byRoom = new Map<string, ActiveEvent[]>()
    for (const event of events) {
      const list = byRoom.get(event.function_room) ?? []
      list.push({
        id: event.id, name: event.name, attendees: event.attendees,
        start: Date.parse(event.start), end: Date.parse(event.end),
      })
      byRoom.set(event.function_room, list)
    }
    for (const list of byRoom.values()) list.sort((a, b) => a.start - b.start)

    return new Venues(seated, seats, present, capacity, byRoom)
  }

  /** Covers seated in an outlet right now. */
  covers(outletId: string, t: number): number {
    return Math.max(0, Math.round(this.seated.get(outletId)?.at(t) ?? 0))
  }

  /** §6.4: seated covers ÷ seats, clamped for the rare over-capacity minute. */
  outletLoad(outletId: string, t: number): number {
    const total = this.seats.get(outletId) ?? 0
    if (total <= 0) return 0
    return Math.min(this.covers(outletId, t) / total, 1)
  }

  /** Attendees inside a function room now -- the full count, not the drawn cap. */
  attendees(roomId: string, t: number): number {
    return Math.max(0, Math.round(this.present.get(roomId)?.at(t) ?? 0))
  }

  /** §6.5: attendees present ÷ capacity. */
  eventLoad(roomId: string, t: number): number {
    const total = this.capacity.get(roomId) ?? 0
    if (total <= 0) return 0
    return Math.min(this.attendees(roomId, t) / total, 1)
  }

  /** The event running in a room, for the §6.5 ceiling glow and §7 hover. */
  activeEvent(roomId: string, t: number): ActiveEvent | null {
    const list = this.events.get(roomId)
    if (!list) return null
    for (const event of list) {
      if (t >= event.start && t <= event.end) return event
    }
    return null
  }
}
