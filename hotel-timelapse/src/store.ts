/**
 * Sim clock, playback and loaded files (BUILD_SPEC §1, §5).
 *
 * `t` is the single source of truth for everything on screen. Nothing here
 * mutates scene state on tick — components read `t` and derive.
 */

import { create } from 'zustand'
import type { Layout, MonthData, Property } from './types'
import type { Accrual } from './sim/accrue'
import type { Room } from './sim/rooms'

export const SPEED_PRESETS = [1, 10, 60, 600, 3600] as const
export const SPEED_MIN = 1
export const SPEED_MAX = 10000

export type CameraPreset = 'aerial' | 'lobby' | 'wing'

export interface LoadFailure { what: string; source: string; hint?: string }

interface State {
  status: 'loading' | 'ready' | 'failed'
  failure: LoadFailure | null

  property: Property | null
  layout: Layout | null
  rooms: Room[]
  data: MonthData | null
  accrual: Accrual | null

  t: number
  playing: boolean
  speed: number
  preset: CameraPreset

  ready(p: Property, l: Layout, rooms: Room[], d: MonthData, a: Accrual): void
  fail(f: LoadFailure): void
  setT(t: number): void
  advance(realSeconds: number): void
  play(): void
  pause(): void
  toggle(): void
  setSpeed(s: number): void
  step(ms: number): void
  setPreset(p: CameraPreset): void
}

export const useSim = create<State>((set, get) => ({
  status: 'loading',
  failure: null,
  property: null,
  layout: null,
  rooms: [],
  data: null,
  accrual: null,
  t: 0,
  playing: false,
  speed: 600,
  preset: 'aerial',

  ready: (property, layout, rooms, data, accrual) =>
    set({ status: 'ready', property, layout, rooms, data, accrual, t: accrual.periodStart }),

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
}))
