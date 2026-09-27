import { useEffect } from 'react'
import { Stage } from './scene/Stage'
import { TallyPanel } from './ui/TallyPanel'
import { Waterfall } from './ui/Waterfall'
import { DeptPanel } from './ui/DeptPanel'
import { Tooltip } from './ui/Tooltip'
import { Transport } from './ui/Transport'
import { useSim, type Population } from './store'
import { expandRooms } from './sim/rooms'
import { buildAccrual, reconcile, REVENUE_KEYS } from './sim/accrue'
import { buildCosts, reconcileCosts } from './sim/costs'
import { attachNights, buildMovement } from './sim/segments'
import { peakCapsules } from './sim/legs'
import { buildStaff } from './sim/staff'
import { buildDeliveries } from './sim/deliveries'
import { buildProfiles } from './sim/profile'
import { dayKey, wallClock } from './sim/tz'
import type { Layout, MonthData, Property } from './types'

const DEFAULT_MONTH = '2026-08'

async function getJSON<T>(url: string): Promise<T> {
  const res = await fetch(url)
  if (!res.ok) throw new Error(`${res.status} ${res.statusText}`)
  return (await res.json()) as T
}

/** A deployment drops in its own property.json; the repo ships the demo. */
async function loadProperty(base: string): Promise<Property> {
  try {
    return await getJSON<Property>(`${base}property.json`)
  } catch {
    return await getJSON<Property>(`${base}property.demo.json`)
  }
}

export default function App() {
  // Selected field by field on purpose. Destructuring the whole store would
  // re-render the app -- and with it the Canvas and every scene component --
  // on every clock tick, which is exactly the per-frame React work the scene
  // is built to avoid.
  const status = useSim((s) => s.status)
  const failure = useSim((s) => s.failure)
  const layout = useSim((s) => s.layout)
  const rooms = useSim((s) => s.rooms)
  const lighting = useSim((s) => s.lighting)
  const segments = useSim((s) => s.segments)
  const staff = useSim((s) => s.staff)
  const turns = useSim((s) => s.turns)
  const deliveries = useSim((s) => s.deliveries)
  const venues = useSim((s) => s.venues)
  const guestCapacity = useSim((s) => s.guestCapacity)
  const staffCapacity = useSim((s) => s.staffCapacity)
  const deliveryCapacity = useSim((s) => s.deliveryCapacity)
  const ready = useSim((s) => s.ready)
  const fail = useSim((s) => s.fail)

  useEffect(() => {
    const base = import.meta.env.BASE_URL
    const month = new URLSearchParams(location.search).get('month') ?? DEFAULT_MONTH
    const dataUrl = `${base}data/${month}.json`
    let cancelled = false

    ;(async () => {
      let property: Property
      let layoutFile: Layout
      try {
        ;[property, layoutFile] = await Promise.all([loadProperty(base), getJSON<Layout>(`${base}layout.json`)])
      } catch (err) {
        if (!cancelled) fail({
          what: `The property configuration could not be read: ${(err as Error).message}`,
          source: `${base}property.json / ${base}layout.json`,
        })
        return
      }

      let data: MonthData
      try {
        data = await getJSON<MonthData>(dataUrl)
      } catch (err) {
        if (!cancelled) fail({
          what: `The month file could not be read: ${(err as Error).message}`,
          source: dataUrl,
          hint: `Generate it with:  python3 prep/gen_synthetic_month.py --month ${month} --out public`,
        })
        return
      }

      const expanded = expandRooms(layoutFile)
      if (expanded.length !== property.rooms) {
        if (!cancelled) fail({
          what: `layout.json describes ${expanded.length} rooms but property.json says ${property.rooms}.`,
          source: `${base}layout.json`,
        })
        return
      }

      const accrual = buildAccrual(data, expanded.length)
      // SPEND_SPEC §12: the cost side, on the same keyframe machinery.
      const costs = buildCosts(data, layoutFile)

      // §9 steps 4-6: room lighting, movement and venue load, built once.
      const built = performance.now()
      const { lighting, segments, venues, departures } = buildMovement(layoutFile, expanded, data)
      // §13: the staff channel, on the same machinery and the same clock.
      const { segments: staff, turns } = buildStaff(
        layoutFile, expanded, { ...data, periodEnd: accrual.periodEnd }, departures)
      // §12 step 13: a van and a box per invoice, and the close's cascade.
      const { segments: deliveries, cascade } = buildDeliveries(
        layoutFile, data.expenses ?? [], costs.departments.map((d) => d.id), accrual.periodEnd)
      // §14 step 14: the daily series, contributors and footprint behind the zoom.
      const profiles = buildProfiles(data, layoutFile, costs)
      attachNights(lighting, accrual.periodStart, accrual.periodEnd,
                   (d, h, m) => wallClock(d, h, m, data.meta.tz),
                   (t) => dayKey(t, data.meta.tz))
      // Sized to the true peak so nobody is silently dropped at the busiest
      // minute of the month.
      const guestCapacity = Math.max(peakCapsules(segments) + 16, 64)
      const staffCapacity = Math.max(peakCapsules(staff) + 16, 64)
      const deliveryCapacity = Math.max(peakCapsules(deliveries) + 4, 16)
      const buildMs = Math.round(performance.now() - built)

      // §4: accruedThrough(period_end) must equal the file totals. Log both.
      const recon = reconcile(accrual)
      const rows = recon.lines.map((l) => ({
        line: l.key, 'accrued at period_end': l.atPeriodEnd, 'file sum': l.fileSum, delta: l.delta,
      }))
      console.groupCollapsed(
        `hotel-timelapse — load reconciliation (${data.meta.month}, worst delta ${recon.worst.toExponential(2)})`,
      )
      console.table(rows)
      console.log(`rooms in house: ${expanded.length} · stays ${data.stays.length} · checks ${data.checks.length} · events ${data.events.length}`)
      console.log(`movement: ${segments.count.toLocaleString()} legs, peak ${guestCapacity - 16} capsules, built in ${buildMs} ms`)
      console.log(`staff: ${staff.count.toLocaleString()} legs, peak ${staffCapacity - 16} on the clock, ` +
                  `${departures.length.toLocaleString()} check-outs, ` +
                  `${turns.unattended.toLocaleString()} left unturned`)
      console.log(`deliveries: ${deliveries.count.toLocaleString()} legs, ` +
                  `${deliveryCapacity - 4} vans and boxes moving at once, ` +
                  `${cascade.accrualTotal.toLocaleString(undefined, {
                    style: 'currency', currency: property.currency, maximumFractionDigits: 0,
                  })} of accruals landing at the close`)
      console.groupEnd()
      if (recon.worst > 0.005) {
        console.error(
          `hotel-timelapse: accrual does not tie to the file. Worst line off by ${recon.worst.toFixed(4)}.`,
          recon.lines.filter((l) => Math.abs(l.delta) > 0.005).map((l) => l.key),
        )
      }
      void REVENUE_KEYS

      // §12: the same check, per department, plus GOP.
      const costRecon = reconcileCosts(costs, accrual.periodEnd)
      const hotel = costs.hotel(accrual.periodEnd)
      console.groupCollapsed(
        `hotel-timelapse — cost reconciliation (GOP ${hotel.gop.toLocaleString(undefined, {
          style: 'currency', currency: property.currency, maximumFractionDigits: 0,
        })} · ${(hotel.gopMargin * 100).toFixed(1)}% · worst delta ${costRecon.worst.toExponential(2)})`,
      )
      console.table(costs.departments.map((d) => {
        const l = costs.dept(d.id, accrual.periodEnd)
        return {
          dept: d.id, type: d.type, revenue: l.revenue, 'cost of sales': l.cos,
          labor: l.labor, 'other expense': l.other, profit: l.profit,
        }
      }))
      console.log(`GOP = dept profit ${hotel.deptProfitTotal.toFixed(2)} ` +
                  `− undistributed ${hotel.undistributedTotal.toFixed(2)} ` +
                  `= ${hotel.gop.toFixed(2)} (delta vs file ${costRecon.gopDelta.toExponential(2)})`)
      console.groupEnd()
      if (costRecon.worst > 0.005 || Math.abs(costRecon.gopDelta) > 0.005) {
        console.error(
          'hotel-timelapse: the cost side does not tie to the file.',
          costRecon.lines.filter((l) => Math.abs(l.delta) > 0.005),
        )
      }

      if (!cancelled) ready({ property, layout: layoutFile, rooms: expanded, data, accrual, costs,
                             lighting, segments, staff, turns, deliveries, cascade, profiles,
                             venues, guestCapacity, staffCapacity, deliveryCapacity })
    })()

    return () => { cancelled = true }
  }, [ready, fail])

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const el = e.target as HTMLElement
      if (el.matches('input, select, textarea, button')) return
      const s = useSim.getState()
      if (e.key === ' ') { e.preventDefault(); s.toggle() }
      else if (e.key === 'ArrowLeft') { e.preventDefault(); s.step(-3600_000) }
      else if (e.key === 'ArrowRight') { e.preventDefault(); s.step(3600_000) }
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [])

  if (status === 'failed' && failure) {
    return (
      <div className="failure">
        <h1>This didn’t load</h1>
        <p>{failure.what}</p>
        <p className="mono">source: {failure.source}</p>
        {failure.hint && <p className="mono">{failure.hint}</p>}
      </div>
    )
  }

  if (status !== 'ready' || !layout || !lighting || !segments || !staff || !turns
      || !deliveries || !venues) {
    return <div className="loading"><p>Loading the month…</p></div>
  }

  return (
    <div className="app">
      <Stage layout={layout} rooms={rooms} lighting={lighting} segments={segments}
             staff={staff} turns={turns} deliveries={deliveries} venues={venues}
             guestCapacity={guestCapacity} staffCapacity={staffCapacity}
             deliveryCapacity={deliveryCapacity} />
      <Presets />
      <Panel />
      <Transport />
      <Tooltip />
    </div>
  )
}

/**
 * §14 makes the live P&L waterfall the panel's global-view default; §7's
 * revenue tally, which carries the department filter, is the other tab. Click
 * a department and the panel becomes that department's own waterfall, which
 * is §14's zoom — one panel, three faces, never two at once.
 */
function Panel() {
  const panel = useSim((s) => s.panel)
  const zoom = useSim((s) => s.zoom)
  if (zoom) return <DeptPanel id={zoom} />
  return panel === 'pnl' ? <Waterfall /> : <TallyPanel />
}

/** §13's toggle: "Show staff" / "Show guests" / both. */
const POPULATIONS: { value: Population; label: string }[] = [
  { value: 'both', label: 'Both' },
  { value: 'guests', label: 'Guests' },
  { value: 'staff', label: 'Staff' },
]

function Presets() {
  const preset = useSim((s) => s.preset)
  const setPreset = useSim((s) => s.setPreset)
  const population = useSim((s) => s.population)
  const setPopulation = useSim((s) => s.setPopulation)
  const property = useSim((s) => s.property)
  const layout = useSim((s) => s.layout)
  return (
    <div className="presets">
      <span className="prop">{property?.name}{property?.synthetic ? ' · demo data' : ''}</span>
      <div>
        <button type="button" aria-pressed={preset === 'aerial'} onClick={() => setPreset('aerial')}>Aerial</button>
        <button type="button" aria-pressed={preset === 'lobby'} onClick={() => setPreset('lobby')}>Lobby</button>
        <button type="button" aria-pressed={preset === 'wing'} onClick={() => setPreset('wing')}>
          Wing {layout?.wings[0]?.id ?? 'A'}
        </button>
      </div>
      <div className="who" role="group" aria-label="Who to show">
        {POPULATIONS.map(({ value, label }) => (
          <button key={value} type="button" aria-pressed={population === value}
                  onClick={() => setPopulation(value)}>{label}</button>
        ))}
      </div>
    </div>
  )
}
