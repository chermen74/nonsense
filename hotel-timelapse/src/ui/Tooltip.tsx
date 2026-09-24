/**
 * §7 hover: "hover a room -> tooltip with room number, current guest count,
 * nightly rate. Hover an outlet/function room -> covers and event name."
 *
 * Everything shown is read at the current `t`, so the figures keep moving
 * while the pointer rests on a room and the clock runs.
 */

import { useSim } from '../store'
import { wallClock } from '../sim/tz'
import type { Stay } from '../types'

/**
 * The rate on the folio right now: the night whose accrual has begun most
 * recently, by §4's own 15:00 boundary. A guest hovered at 2am is still on
 * last night's rate, which is what the front desk would tell you.
 */
function currentRate(stay: Stay, t: number, tz: string): number | null {
  if (stay.nights.length === 0) return null
  let rate = stay.nights[0].rate
  for (const night of stay.nights) {
    if (wallClock(night.date, 15, 0, tz) <= t) rate = night.rate
  }
  return rate
}

export function Tooltip() {
  const { hover, t, rooms, data, lighting, venues, layout, property } = useSim((s) => ({
    hover: s.hover, t: s.t, rooms: s.rooms, data: s.data,
    lighting: s.lighting, venues: s.venues, layout: s.layout, property: s.property,
  }))

  if (!hover || !data || !lighting || !venues || !layout || !property) return null

  const money = new Intl.NumberFormat(undefined, {
    style: 'currency', currency: property.currency,
    minimumFractionDigits: 0, maximumFractionDigits: 0,
  })

  let title = ''
  let detail = ''

  if (hover.kind === 'room') {
    const room = rooms[hover.index]
    if (!room) return null
    title = `Room ${room.number}`
    const stayIndex = lighting.stayAt(hover.index, t)
    if (stayIndex < 0) {
      detail = 'Vacant'
    } else {
      const stay = data.stays[stayIndex]
      const rate = currentRate(stay, t, data.meta.tz)
      const guests = `${stay.guests} guest${stay.guests === 1 ? '' : 's'}`
      detail = rate === null ? guests : `${guests} · ${money.format(rate)} tonight`
    }
  } else if (hover.kind === 'outlet') {
    const outlet = layout.outlets.find((o) => o.id === hover.id)
    if (!outlet) return null
    title = outlet.name
    detail = `${venues.covers(outlet.id, t)} of ${outlet.seats} seats`
  } else {
    const room = layout.function_rooms.find((f) => f.id === hover.id)
    if (!room) return null
    title = room.name
    const event = venues.activeEvent(room.id, t)
    detail = event === null
      ? 'No event'
      : `${event.name} · ${venues.attendees(room.id, t)} of ${room.capacity}`
  }

  // Kept clear of the pointer, and inside the window near the right edge.
  const flipX = hover.x > window.innerWidth - 240
  const flipY = hover.y > window.innerHeight - 90

  return (
    <div
      className="tooltip"
      role="status"
      style={{
        left: hover.x + (flipX ? -14 : 14),
        top: hover.y + (flipY ? -14 : 14),
        transform: `translate(${flipX ? '-100%' : '0'}, ${flipY ? '-100%' : '0'})`,
      }}
    >
      <strong>{title}</strong>
      <span>{detail}</span>
    </div>
  )
}
