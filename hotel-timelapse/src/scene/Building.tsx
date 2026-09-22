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
import type { Venues } from '../sim/venues'
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

/** An empty venue reads as part of the building, not as a lit room. */
const COLOR_VENUE_EMPTY = new THREE.Color('#333a47')

/**
 * §6.4 and §6.5: a venue floor tinted by how full it is right now.
 *
 * `load` is a pure function of `t`, so the colour is too -- nothing is carried
 * between frames except the last value written, which is only there to spare
 * the GPU an upload on a still frame.
 */
function LoadZone({
  x, z, w, d, height, hue, load,
}: {
  x: number; z: number; w: number; d: number; height: number
  hue: string; load: (t: number) => number
}) {
  const material = useRef<THREE.MeshLambertMaterial>(null!)
  const full = useMemo(() => new THREE.Color(hue), [hue])
  const shown = useRef(-1)

  useFrame(() => {
    const m = material.current
    if (!m) return
    const v = load(useSim.getState().t)
    if (Math.abs(v - shown.current) < 0.004) return
    shown.current = v
    m.color.copy(COLOR_VENUE_EMPTY).lerp(full, v)
    m.opacity = 0.3 + 0.45 * v
  })

  return (
    <mesh position={[x, height / 2, z]}>
      <boxGeometry args={[w, height, d]} />
      <meshLambertMaterial ref={material} color={COLOR_VENUE_EMPTY} transparent opacity={0.3} flatShading />
    </mesh>
  )
}

/** §6.5: "a coloured ceiling glow appears while the event is active". */
function EventGlow({
  room, venues, height, hue,
}: { room: FunctionRoom; venues: Venues; height: number; hue: string }) {
  const panel = useRef<THREE.Mesh>(null!)
  const material = useRef<THREE.MeshBasicMaterial>(null!)
  const lamp = useRef<THREE.PointLight>(null!)

  useFrame(() => {
    const t = useSim.getState().t
    const active = venues.activeEvent(room.id, t) !== null
    if (panel.current) panel.current.visible = active
    if (lamp.current) lamp.current.visible = active
    if (!active) return
    // A full room glows harder, but an event with nobody in it still shows.
    const v = 0.35 + 0.65 * venues.eventLoad(room.id, t)
    if (material.current) material.current.opacity = 0.22 * v
    if (lamp.current) lamp.current.intensity = 26 * v
  })

  return (
    <group>
      <mesh ref={panel} visible={false} position={[room.x, height + 0.15, room.z]} rotation={[Math.PI / 2, 0, 0]}>
        <planeGeometry args={[room.w, room.d]} />
        <meshBasicMaterial ref={material} color={hue} transparent opacity={0} toneMapped={false} side={THREE.DoubleSide} />
      </mesh>
      <pointLight ref={lamp} visible={false} position={[room.x, height - 0.6, room.z]}
                  color={hue} intensity={0} distance={Math.max(room.w, room.d) * 1.6} decay={2} />
    </group>
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

const OUTLET_HUE = '#e0674a'
const FUNCTION_HUE = '#7b5bd6'
const OUTLET_HEIGHT = 3.2
const FUNCTION_HEIGHT = 4.2

export function Building({ layout, rooms, lighting, venues }:
  { layout: Layout; rooms: Room[]; lighting: Lighting; venues: Venues }) {
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
        <LoadZone key={o.id} x={o.x} z={o.z} w={o.w} d={o.d} height={OUTLET_HEIGHT}
                  hue={OUTLET_HUE} load={(t) => venues.outletLoad(o.id, t)} />
      ))}
      {layout.function_rooms.map((f: FunctionRoom) => (
        <group key={f.id}>
          <LoadZone x={f.x} z={f.z} w={f.w} d={f.d} height={FUNCTION_HEIGHT}
                    hue={FUNCTION_HUE} load={(t) => venues.eventLoad(f.id, t)} />
          <EventGlow room={f} venues={venues} height={FUNCTION_HEIGHT} hue={FUNCTION_HUE} />
        </group>
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
