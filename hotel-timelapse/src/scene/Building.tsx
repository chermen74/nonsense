/**
 * The static scene (BUILD_SPEC §9 step 2): wings, floors, rooms, corridors,
 * outlets, function rooms, back of house. Flat-shaded boxes, no textures (§2).
 *
 * Rooms are one InstancedMesh for the bodies and one for the window faces, so
 * 400 rooms cost two draw calls. Never a component per room (CLAUDE.md).
 */

import { useFrame } from '@react-three/fiber'
import { useLayoutEffect, useMemo, useRef } from 'react'
import * as THREE from 'three'
import type { FunctionRoom, Layout, Outlet, BohBox } from '../types'
import { type Room } from '../sim/rooms'
import { buildNetwork } from '../sim/paths'
import type { Lighting } from '../sim/segments'
import { useSim } from '../store'

const ROOM_W = 3.5
const ROOM_H = 2.8
const ROOM_D = 3.5
const WINDOW_W = 2.6
const WINDOW_H = 1.6

const COLOR_ROOM_DARK = new THREE.Color('#5b6474')
const COLOR_CORRIDOR = new THREE.Color('#3a404b')

/** §6.3 window states: dark, the 00:00-06:30 sleep dim, and lit. */
const WINDOW_STATE = [
  new THREE.Color('#2b303a'),   // 0 dark
  new THREE.Color('#6a5636'),   // 1 dim -- the lit colour at about a quarter
  new THREE.Color('#ffca7a'),   // 2 lit -- emissive warm
]

function Rooms({ rooms, lighting }: { rooms: Room[]; lighting: Lighting }) {
  const bodies = useRef<THREE.InstancedMesh>(null!)
  const windows = useRef<THREE.InstancedMesh>(null!)
  /** Last state written per room, so a still frame uploads nothing. */
  const shown = useMemo(() => new Uint8Array(rooms.length).fill(255), [rooms.length])

  useLayoutEffect(() => {
    const m = new THREE.Matrix4()
    const q = new THREE.Quaternion()
    const up = new THREE.Vector3(0, 1, 0)
    const one = new THREE.Vector3(1, 1, 1)
    const pos = new THREE.Vector3()

    rooms.forEach((r, i) => {
      // Body: square in plan, so it needs no yaw.
      m.compose(pos.set(r.x, r.y, r.z), new THREE.Quaternion(), one)
      bodies.current.setMatrixAt(i, m)
      bodies.current.setColorAt(i, COLOR_ROOM_DARK)

      // Window sits just proud of the exterior face, looking along the normal.
      const yaw = Math.atan2(r.nx, r.nz)
      q.setFromAxisAngle(up, yaw)
      pos.set(r.x + r.nx * (ROOM_D / 2 + 0.02), r.y, r.z + r.nz * (ROOM_D / 2 + 0.02))
      m.compose(pos, q, one)
      windows.current.setMatrixAt(i, m)
      windows.current.setColorAt(i, WINDOW_STATE[0])
    })

    bodies.current.instanceMatrix.needsUpdate = true
    windows.current.instanceMatrix.needsUpdate = true
    if (bodies.current.instanceColor) bodies.current.instanceColor.needsUpdate = true
    if (windows.current.instanceColor) windows.current.instanceColor.needsUpdate = true
    shown.fill(255)
  }, [rooms, shown])

  // §9 step 4: the window face follows the stay, as a pure function of t.
  useFrame(() => {
    const t = useSim.getState().t
    const mesh = windows.current
    if (!mesh) return
    let changed = false
    for (let i = 0; i < rooms.length; i++) {
      const state = lighting.stateAt(i, t)
      if (shown[i] === state) continue
      shown[i] = state
      mesh.setColorAt(i, WINDOW_STATE[state])
      changed = true
    }
    if (changed && mesh.instanceColor) mesh.instanceColor.needsUpdate = true
  })

  return (
    <>
      <instancedMesh ref={bodies} args={[undefined, undefined, rooms.length]} castShadow receiveShadow>
        <boxGeometry args={[ROOM_W, ROOM_H, ROOM_D]} />
        <meshLambertMaterial flatShading />
      </instancedMesh>
      <instancedMesh ref={windows} args={[undefined, undefined, rooms.length]}>
        <planeGeometry args={[WINDOW_W, WINDOW_H]} />
        <meshBasicMaterial toneMapped={false} side={THREE.DoubleSide} />
      </instancedMesh>
    </>
  )
}

function Corridors({ layout }: { layout: Layout }) {
  // Drawn on the walk network's own centreline, so guests walk the corridor
  // rather than through the rooms (§2).
  const runs = useMemo(() => {
    const net = buildNetwork(layout)
    const out: Array<{ key: string; x: number; y: number; z: number; w: number; d: number }> = []
    for (const wing of layout.wings) {
      const path = net.wings.get(wing.id)
      if (!path) continue
      const alongX = Math.abs(wing.dir.x) > Math.abs(wing.dir.z)
      const length = Math.hypot(path.far.x - path.near.x, path.far.z - path.near.z)
      for (let floor = 1; floor <= wing.floors; floor++) {
        out.push({
          key: `${wing.id}-${floor}`,
          x: (path.near.x + path.far.x) / 2,
          y: floor * wing.floor_height - ROOM_H / 2 - 0.05,
          z: (path.near.z + path.far.z) / 2,
          w: alongX ? length + ROOM_W : 2.6,
          d: alongX ? 2.6 : length + ROOM_D,
        })
      }
    }
    return out
  }, [layout])

  return (
    <>
      {runs.map((c) => (
        <mesh key={c.key} position={[c.x, c.y, c.z]}>
          <boxGeometry args={[c.w, 0.1, c.d]} />
          <meshLambertMaterial color={COLOR_CORRIDOR} flatShading />
        </mesh>
      ))}
    </>
  )
}

function Zone({
  x, z, w, d, color, height, opacity,
}: { x: number; z: number; w: number; d: number; color: string; height: number; opacity: number }) {
  return (
    <mesh position={[x, height / 2, z]}>
      <boxGeometry args={[w, height, d]} />
      <meshLambertMaterial color={color} transparent opacity={opacity} flatShading />
    </mesh>
  )
}

function Node({ p, color, r = 1.2 }: { p: { x: number; y: number; z: number }; color: string; r?: number }) {
  return (
    <mesh position={[p.x, 0.3, p.z]}>
      <cylinderGeometry args={[r, r, 0.6, 12]} />
      <meshLambertMaterial color={color} flatShading />
    </mesh>
  )
}

export function Building({ layout, rooms, lighting }:
  { layout: Layout; rooms: Room[]; lighting: Lighting }) {
  return (
    <group>
      {/* Ground */}
      <mesh rotation={[-Math.PI / 2, 0, 0]} position={[10, -0.05, 0]} receiveShadow>
        <planeGeometry args={[400, 400]} />
        <meshLambertMaterial color="#212530" />
      </mesh>

      <Rooms rooms={rooms} lighting={lighting} />
      <Corridors layout={layout} />

      {layout.outlets.map((o: Outlet) => (
        <Zone key={o.id} x={o.x} z={o.z} w={o.w} d={o.d} color="#e0674a" height={3.2} opacity={0.55} />
      ))}
      {layout.function_rooms.map((f: FunctionRoom) => (
        <Zone key={f.id} x={f.x} z={f.z} w={f.w} d={f.d} color="#7b5bd6" height={4.2} opacity={0.5} />
      ))}
      {(layout.boh ?? []).map((b: BohBox) => (
        <Zone key={b.id} x={b.x} z={b.z} w={b.w} d={b.d} color="#4a5160" height={2.6} opacity={0.45} />
      ))}

      <Node p={layout.entrance} color="#2fb3a0" r={1.6} />
      <Node p={layout.front_desk} color="#d8dde6" r={1.8} />
      <Node p={layout.lobby_hub} color="#8b94a6" r={2.2} />
      {layout.staff_entrance && <Node p={layout.staff_entrance} color="#4d7fd6" />}
      {layout.loading_dock && <Node p={layout.loading_dock} color="#6b7383" />}
      {layout.elevators.map((e) => (
        <mesh key={e.id} position={[e.x, 2.5, e.z]}>
          <boxGeometry args={[3, 5, 3]} />
          <meshLambertMaterial color="#5d6472" flatShading />
        </mesh>
      ))}
    </group>
  )
}
