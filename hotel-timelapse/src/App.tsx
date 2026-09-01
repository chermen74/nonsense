import { useEffect } from 'react'
import { Stage } from './scene/Stage'
import { TallyPanel } from './ui/TallyPanel'
import { Transport } from './ui/Transport'
import { useSim } from './store'
import { expandRooms } from './sim/rooms'
import { buildAccrual, reconcile, REVENUE_KEYS } from './sim/accrue'
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
  const { status, failure, layout, rooms, ready, fail } = useSim()

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
      console.groupEnd()
      if (recon.worst > 0.005) {
        console.error(
          `hotel-timelapse: accrual does not tie to the file. Worst line off by ${recon.worst.toFixed(4)}.`,
          recon.lines.filter((l) => Math.abs(l.delta) > 0.005).map((l) => l.key),
        )
      }
      void REVENUE_KEYS

      if (!cancelled) ready(property, layoutFile, expanded, data, accrual)
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

  if (status !== 'ready' || !layout) {
    return <div className="loading"><p>Loading the month…</p></div>
  }

  return (
    <div className="app">
      <Stage layout={layout} rooms={rooms} />
      <Presets />
      <TallyPanel />
      <Transport />
    </div>
  )
}

function Presets() {
  const { preset, setPreset, property, layout } = useSim()
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
    </div>
  )
}
