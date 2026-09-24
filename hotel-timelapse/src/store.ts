/**
 * Sim clock, playback and loaded files (BUILD_SPEC §1, §5).
 *
 * `t` is the single source of truth for everything on screen. Nothing here
 * mutates scene state on tick — components read `t` and derive.
 */

import { create } from 'zustand'
import type { Layout, MonthData, Property } from './types'
import type { Accrual } from './sim/accrue'
import type { CostAccrual } from './sim/costs'
import type { Room } from './sim/rooms'
import type { Lighting, Segments, TallyLine } from './sim/segments'
import type { Venues } from './sim/venues'

export const SPEED_PRESETS = [1, 10, 60, 600, 3600] as const
export const SPEED_MIN = 1
export const SPEED_MAX = 10000

export type CameraPreset = 'aerial' | 'lobby' | 'wing'

/**
 * Which face the side panel is showing. §14 makes the live P&L waterfall the
 * global default; §7's revenue tally, and the department filter that lives on
 * its lines, is the other face of the same panel.
 */
export type PanelView = 'pnl' | 'revenue'

/**
 * §6.6 readability bands. Below `BOB_MAX` a capsule bobs as it walks; above
 * `FLOW_MIN` capsules give way to particles flowing along the corridor edges,
 * because at that speed a capsule crosses the property inside one frame.
 */
export const BOB_MAX_SPEED = 10
export const FLOW_MIN_SPEED = 1000

/** What the pointer is over, in client pixels, for the §7 tooltip. */
export type Hover =
  | { kind: 'room'; index: number; x: number; y: number }
  | { kind: 'outlet'; id: string; x: number; y: number }
  | { kind: 'function_room'; id: string; x: number; y: number }

export interface LoadFailure { what: string; source: string; hint?: string }

interface State {
  status: 'loading' | 'ready' | 'failed'
  failure: LoadFailure | null

  property: Property | null
  layout: Layout | null
  rooms: Room[]
  data: MonthData | null
  accrual: Accrual | null
  costs: CostAccrual | null
  lighting: Lighting | null
  segments: Segments | null
  venues: Venues | null
  /** Upper bound on capsules drawn at once; see the note in App.tsx. */
  guestCapacity: number

  t: number
  playing: boolean
  speed: number
  preset: CameraPreset
  /** §7 department filter: the tally line clicked, or null for everything. */
  filter: TallyLine | null
  panel: PanelView
  /** §10: fixed charges are a single toggle-able line below GOP. */
  showFixed: boolean
  hover: Hover | null

  ready(p: Property, l: Layout, rooms: Room[], d: MonthData, a: Accrual, costs: CostAccrual,
        lighting: Lighting, segments: Segments, venues: Venues, guestCapacity: number): void
  fail(f: LoadFailure): void
  setT(t: number): void
  advance(realSeconds: number): void
  play(): void
  pause(): void
  toggle(): void
  setSpeed(s: number): void
  step(ms: number): void
  setPreset(p: CameraPreset): void
  setPanel(v: PanelView): void
  toggleFixed(): void
  toggleFilter(line: TallyLine): void
  setHover(h: Hover | null): void
}

export const useSim = create<State>((set, get) => ({
  status: 'loading',
  failure: null,
  property: null,
  layout: null,
  rooms: [],
  data: null,
  accrual: null,
  costs: null,
  lighting: null,
  segments: null,
  venues: null,
  guestCapacity: 0,
  t: 0,
  playing: false,
  speed: 600,
  preset: 'aerial',
  filter: null,
  panel: 'pnl',
  showFixed: false,
  hover: null,

  ready: (property, layout, rooms, data, accrual, costs, lighting, segments, venues, guestCapacity) =>
    set({ status: 'ready', property, layout, rooms, data, accrual, costs, lighting, segments,
          venues, guestCapacity, t: accrual.periodStart }),

  fail: (failure) => set({ status: 'failed', failure }),

  setT: (t) => {
    const a = get().accrual
    if (!a) return
    set({ t: Math.min(Math.max(t, a.periodStart), a.periodEnd) })
  },

  advance: (realSeconds) => {
    const { accrual, t, speed, playing } = get()
    if (!accrual || !playing) return
    // Capped so a backgrounded tab doesn't jump the month on return. The cap is
    // generous enough that an ordinary slow frame still advances in full.
    const next = t + realSeconds * 1000 * speed
    if (next >= accrual.periodEnd) set({ t: accrual.periodEnd, playing: false })
    else set({ t: next })
  },

  play: () => {
    const { accrual, t } = get()
    if (!accrual) return
    // Play from the top once the month has run out (§14 close animation loops).
    set({ playing: true, t: t >= accrual.periodEnd ? accrual.periodStart : t })
  },
  pause: () => set({ playing: false }),
  toggle: () => (get().playing ? get().pause() : get().play()),

  setSpeed: (s) => set({ speed: Math.min(Math.max(s, SPEED_MIN), SPEED_MAX) }),
  step: (ms) => { get().pause(); get().setT(get().t + ms) },
  setPreset: (preset) => set({ preset }),
  setPanel: (panel) => set({ panel }),
  toggleFixed: () => set({ showFixed: !get().showFixed }),
  toggleFilter: (line) => set({ filter: get().filter === line ? null : line }),
  setHover: (hover) => set({ hover }),
}))
