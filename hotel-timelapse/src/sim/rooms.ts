/**
 * Expands `layout.wings` into individual rooms (BUILD_SPEC §2).
 *
 * Nothing about any particular hotel lives here: wing count, floors, pitch and
 * room numbering all come out of layout.json.
 */

import type { Layout, Wing } from '../types'

export interface Room {
  /** Room number as the data files refer to it. */
  number: string
  wing: string
  /** 1-based, matching the `floor_N` keys in layout.json. */
  floor: number
  index: number
  x: number
  y: number
  z: number
  /** Unit vector pointing out of the window face, away from the lobby. */
  nx: number
  nz: number
}

function firstNumber(range: string): number {
  return Number(range.split('-')[0])
}

function wingRooms(wing: Wing, lobby: { x: number; z: number }): Room[] {
  const out: Room[] = []
  const { dir, origin, room_pitch, floor_height, rooms_per_floor } = wing

  // Perpendicular to the wing's run; sign chosen so the window looks away from
  // the lobby rather than into the courtyard.
  let nx = dir.z
  let nz = -dir.x
  const midX = origin.x + dir.x * ((rooms_per_floor - 1) * room_pitch) / 2
  const midZ = origin.z + dir.z * ((rooms_per_floor - 1) * room_pitch) / 2
  if ((midX - lobby.x) * nx + (midZ - lobby.z) * nz < 0) {
    nx = -nx
    nz = -nz
  }

  for (let floor = 1; floor <= wing.floors; floor++) {
    const range = wing.room_numbers[`floor_${floor}`]
    if (!range) throw new Error(`layout.json: wing ${wing.id} has no room_numbers.floor_${floor}`)
    const start = firstNumber(range)
    for (let i = 0; i < rooms_per_floor; i++) {
      out.push({
        number: String(start + i),
        wing: wing.id,
        floor,
        index: i,
        x: origin.x + dir.x * i * room_pitch,
        y: floor * floor_height,
        z: origin.z + dir.z * i * room_pitch,
        nx,
        nz,
      })
    }
  }
  return out
}

export function expandRooms(layout: Layout): Room[] {
  const lobby = layout.lobby_hub
  return layout.wings.flatMap((w) => wingRooms(w, lobby))
}

export interface CorridorRun {
  wing: string
  floor: number
  x: number; y: number; z: number
  length: number
  along: { x: number; z: number }
}

/** One corridor per floor per wing (§2), running the length of the wing. */
export function corridors(layout: Layout): CorridorRun[] {
  const runs: CorridorRun[] = []
  for (const w of layout.wings) {
    const length = (w.rooms_per_floor - 1) * w.room_pitch
    for (let floor = 1; floor <= w.floors; floor++) {
      runs.push({
        wing: w.id,
        floor,
        x: w.origin.x + (w.dir.x * length) / 2,
        y: floor * w.floor_height,
        z: w.origin.z + (w.dir.z * length) / 2,
        length,
        along: w.dir,
      })
    }
  }
  return runs
}
