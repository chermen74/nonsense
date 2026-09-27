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

function wingMid(wing: Wing): { x: number; z: number } {
  const span = (wing.rooms_per_floor - 1) * wing.room_pitch
  return {
    x: wing.origin.x + (wing.dir.x * span) / 2,
    z: wing.origin.z + (wing.dir.z * span) / 2,
  }
}

/**
 * Which way each wing's windows face (§2: "a window face toward the exterior").
 *
 * Outward means away from the middle of the building, not away from the lobby:
 * in a multi-wing property the lobby sits at one end, so a lobby-relative rule
 * turns one wing's windows to face the back of another. The corridor then runs
 * on the opposite side, which is what puts it inside the building.
 */
export function wingNormals(layout: Layout): Map<string, { nx: number; nz: number }> {
  const mids = layout.wings.map(wingMid)
  const centre = {
    x: mids.reduce((a, m) => a + m.x, 0) / mids.length,
    z: mids.reduce((a, m) => a + m.z, 0) / mids.length,
  }

  const out = new Map<string, { nx: number; nz: number }>()
  layout.wings.forEach((wing, i) => {
    let nx = wing.dir.z
    let nz = -wing.dir.x
    if ((mids[i].x - centre.x) * nx + (mids[i].z - centre.z) * nz < 0) {
      nx = -nx
      nz = -nz
    }
    out.set(wing.id, { nx, nz })
  })
  return out
}

function wingRooms(wing: Wing, normal: { nx: number; nz: number }): Room[] {
  const out: Room[] = []
  const { dir, origin, room_pitch, floor_height, rooms_per_floor } = wing
  const { nx, nz } = normal

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
  const normals = wingNormals(layout)
  return layout.wings.flatMap((w) => wingRooms(w, normals.get(w.id)!))
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
