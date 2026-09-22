/**
 * The walk network (BUILD_SPEC §2).
 *
 *   entrance -> front_desk -> lobby_hub -> elevator -> corridor(floor) -> room
 *
 * Everything is derived from layout.json; no distance or corner is hard-coded.
 * Guests walk the corridor, never through a wall, so the corridor centreline
 * sits off the rooms on the side away from the windows.
 */

import type { Layout } from '../types'
import { wingNormals, type Room } from './rooms'

/** §6: real-world walk speed. */
export const WALK_SPEED = 1.3

const ROOM_DEPTH = 3.5
const ROOM_HEIGHT = 2.8
/** Clear of the room boxes, on the corridor side. */
const CORRIDOR_SETBACK = ROOM_DEPTH / 2 + 1.2

export interface Point { x: number; y: number; z: number }

export interface WingPath {
  id: string
  /** Outward window normal, shared by every room in the wing. */
  nx: number
  nz: number
  /** Corridor centreline, from the end nearest the lobby to the far end. */
  near: { x: number; z: number }
  far: { x: number; z: number }
  floorHeight: number
}

export interface Network {
  entrance: Point
  frontDesk: Point
  lobbyHub: Point
  elevators: Array<{ id: string } & Point>
  wings: Map<string, WingPath>
}

export function distance(a: Point, b: Point): number {
  return Math.hypot(a.x - b.x, a.y - b.y, a.z - b.z)
}

/** Seconds to walk a leg at §6's 1.3 m/s; never zero, so a segment has span. */
export function walkTime(a: Point, b: Point): number {
  return Math.max(distance(a, b) / WALK_SPEED, 0.5)
}

/** Floor slab a guest stands on. Floor 1 is the first guest floor, not the lobby. */
export function walkLevel(floor: number, floorHeight: number): number {
  return floor * floorHeight - ROOM_HEIGHT / 2
}

export function buildNetwork(layout: Layout): Network {
  const lobby = layout.lobby_hub
  const normals = wingNormals(layout)
  const wings = new Map<string, WingPath>()

  for (const wing of layout.wings) {
    const { nx, nz } = normals.get(wing.id)!
    const span = (wing.rooms_per_floor - 1) * wing.room_pitch
    // Corridor runs parallel to the rooms, set back on the non-window side.
    const offX = -nx * CORRIDOR_SETBACK
    const offZ = -nz * CORRIDOR_SETBACK
    const a = { x: wing.origin.x + offX, z: wing.origin.z + offZ }
    const b = { x: a.x + wing.dir.x * span, z: a.z + wing.dir.z * span }

    const distA = Math.hypot(a.x - lobby.x, a.z - lobby.z)
    const distB = Math.hypot(b.x - lobby.x, b.z - lobby.z)
    wings.set(wing.id, {
      id: wing.id,
      nx, nz,
      near: distA <= distB ? a : b,
      far: distA <= distB ? b : a,
      floorHeight: wing.floor_height,
    })
  }

  return {
    entrance: { ...layout.entrance },
    frontDesk: { ...layout.front_desk },
    lobbyHub: { ...layout.lobby_hub },
    elevators: layout.elevators.map((e) => ({ id: e.id, x: e.x, y: 0, z: e.z })),
    wings,
  }
}

/** The elevator bank a guest for this room would actually use. */
export function nearestElevator(net: Network, room: Room): { id: string } & Point {
  let best = net.elevators[0]
  let bestDistance = Infinity
  for (const lift of net.elevators) {
    const d = Math.hypot(lift.x - room.x, lift.z - room.z)
    if (d < bestDistance) {
      bestDistance = d
      best = lift
    }
  }
  return best
}

/** The room's door: the face opposite its window, at floor level. */
export function roomDoor(room: Room, wing: WingPath): Point {
  return {
    x: room.x - room.nx * (ROOM_DEPTH / 2),
    y: walkLevel(room.floor, wing.floorHeight),
    z: room.z - room.nz * (ROOM_DEPTH / 2),
  }
}

/** The point on the corridor centreline directly outside the room's door. */
export function corridorOutside(room: Room, wing: WingPath): Point {
  return {
    x: room.x - room.nx * CORRIDOR_SETBACK,
    y: walkLevel(room.floor, wing.floorHeight),
    z: room.z - room.nz * CORRIDOR_SETBACK,
  }
}

/** Where the lift doors open onto that floor: corridor end nearest the lobby. */
export function corridorEntry(wing: WingPath, floor: number): Point {
  return { x: wing.near.x, y: walkLevel(floor, wing.floorHeight), z: wing.near.z }
}
