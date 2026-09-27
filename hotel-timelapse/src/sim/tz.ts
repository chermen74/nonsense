/**
 * Wall-clock helpers in the property's own timezone.
 *
 * BUILD_SPEC §4 accrues each room night "from 15:00 to 23:00 on that night's
 * date" — a wall-clock rule, so it has to be resolved in the hotel's zone, not
 * the viewer's. A month can straddle a DST change, so the offset is looked up
 * per date rather than taken once from period_start.
 */

const PARTS = new Map<string, Intl.DateTimeFormat>()

function formatter(tz: string): Intl.DateTimeFormat {
  let f = PARTS.get(tz)
  if (!f) {
    f = new Intl.DateTimeFormat('en-US', {
      timeZone: tz, hour12: false,
      year: 'numeric', month: '2-digit', day: '2-digit',
      hour: '2-digit', minute: '2-digit', second: '2-digit',
    })
    PARTS.set(tz, f)
  }
  return f
}

/** Milliseconds to add to a UTC instant to get that zone's wall clock. */
function zoneOffset(utcMs: number, tz: string): number {
  const p = formatter(tz).formatToParts(new Date(utcMs))
  const get = (type: string) => Number(p.find((x) => x.type === type)!.value)
  const asIfUTC = Date.UTC(get('year'), get('month') - 1, get('day'), get('hour') % 24, get('minute'), get('second'))
  return asIfUTC - utcMs
}

/** Epoch ms for `YYYY-MM-DD` at `hh:mm` wall-clock in `tz`. */
export function wallClock(dateISO: string, hh: number, mm: number, tz: string): number {
  const [y, m, d] = dateISO.split('-').map(Number)
  const naive = Date.UTC(y, m - 1, d, hh, mm)
  // One correction pass, then a second in case the first landed the other side
  // of a transition.
  let t = naive - zoneOffset(naive, tz)
  t = naive - zoneOffset(t, tz)
  return t
}

/** `Fri Aug 14, 2026 · 6:42 PM` — BUILD_SPEC §5 clock readout. */
export function clockLabel(t: number, tz: string): string {
  const date = new Intl.DateTimeFormat(undefined, {
    timeZone: tz, weekday: 'short', month: 'short', day: 'numeric', year: 'numeric',
  }).format(t)
  const time = new Intl.DateTimeFormat(undefined, {
    timeZone: tz, hour: 'numeric', minute: '2-digit',
  }).format(t)
  return `${date} · ${time}`
}

export function dayKey(t: number, tz: string): string {
  const p = formatter(tz).formatToParts(new Date(t))
  const get = (type: string) => p.find((x) => x.type === type)!.value
  return `${get('year')}-${get('month')}-${get('day')}`
}
