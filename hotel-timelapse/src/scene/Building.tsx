/**
 * The static scene (BUILD_SPEC §9 step 2): wings, floors, rooms, corridors,
 * outlets, function rooms, back of house. Flat-shaded boxes, no textures (§2).
 *
 * Rooms are one InstancedMesh for the bodies and one for the window faces, so
 * 400 rooms cost two draw calls. Never a component per room (CLAUDE.md).
 */

import { useLayoutEffect, useMemo, useRef } from 'react'
import * as THREE from 'three'
import type { FunctionRoom, Layout, Outlet, BohBox } from '../types'
import { corridors, type Room } from '../sim/rooms'

const ROOM_W = 3.5
const ROOM_H = 2.8
const ROOM_D = 3.5
const WINDOW_W = 2.6
const WINDOW_H = 1.6

const COLOR_ROOM_DARK = new THREE.Color('#5b6474')
const COLOR_WINDOW_DARK = new THREE.Color('#2b303a')
const COLOR_CORRIDOR = new THREE.Color('#3a404b')

function Rooms({ rooms }: { rooms: Room[] }) {
  const bodies = useRef<THREE.InstancedMesh>(null!)
  const windows = useRef<THREE.InstancedMesh>(null!)

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
      windows.current.setColorAt(i, COLOR_WINDOW_DARK)
    })

    bodies.current.instanceMatrix.needsUpdate = true
    windows.current.instanceMatrix.needsUpdate = true
    if (bodies.current.instanceColor) bodies.current.instanceColor.needsUpdate = true
    if (windows.current.instanceColor) windows.current.instanceColor.needsUpdate = true
  }, [rooms])

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
  const runs = useMemo(() => corridors(layout), [layout])
  return (
    <>
      {runs.map((c) => {
        const alongX = Math.abs(c.along.x) > Math.abs(c.along.z)
        return (
          <mesh key={`${c.wing}-${c.floor}`} position={[c.x, c.y - ROOM_H / 2 - 0.05, c.z]}>
            <boxGeometry args={alongX ? [c.length + ROOM_W, 0.1, 2.4] : [2.4, 0.1, c.length + ROOM_D]} />
            <meshLambertMaterial color={COLOR_CORRIDOR} flatShading />
          </mesh>
        )
      })}
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

export function Building({ layout, rooms }: { layout: Layout; rooms: Room[] }) {
  return (
    <group>
      {/* Ground */}
      <mesh rotation={[-Math.PI / 2, 0, 0]} position={[10, -0.05, 0]} receiveShadow>
        <planeGeometry args={[400, 400]} />
        <meshLambertMaterial color="#212530" />
      </mesh>

      <Rooms rooms={rooms} />
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
