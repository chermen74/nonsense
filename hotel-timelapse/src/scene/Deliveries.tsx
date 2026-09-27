/**
 * Vans at the dock and boxes on their way to a department — SPEND_SPEC §12,
 * build order §16 step 13.
 *
 * Two `InstancedMesh`es fed from one set of legs, because a van and the box it
 * drops are the same kind of thing at different sizes and the scene should not
 * pay two frame loops for them. Nothing here is a component per delivery.
 *
 * A van points where it is going: unlike a capsule, a box that does not turn
 * reads as sliding sideways down the service road. A parked van keeps the
 * heading it arrived on, which is why the yaw falls back to the last non-zero
 * direction rather than to an axis.
 */

import { useFrame } from '@react-three/fiber'
import { useMemo, useRef } from 'react'
import * as THREE from 'three'
import { forEachActive, type Segments } from '../sim/legs'
import { DELIVERY_VAN } from '../sim/deliveries'
import { useSim } from '../store'
import { DELIVERY_COLOR } from './palette'

const VAN = { w: 2.4, h: 2.4, l: 5.6 }
const PARCEL = 0.9
/** Carried at chest height, so a box reads as being carried rather than dragged. */
const CARRY = 1.05

export function Deliveries({ segments, capacity }: { segments: Segments; capacity: number }) {
  const vans = useRef<THREE.InstancedMesh>(null!)
  const parcels = useRef<THREE.InstancedMesh>(null!)
  const scratch = useMemo(() => ({
    matrix: new THREE.Matrix4(),
    position: new THREE.Vector3(),
    quaternion: new THREE.Quaternion(),
    scale: new THREE.Vector3(1, 1, 1),
    up: new THREE.Vector3(0, 1, 0),
  }), [])

  useFrame(() => {
    const vanMesh = vans.current
    const parcelMesh = parcels.current
    if (!vanMesh || !parcelMesh) return
    const t = useSim.getState().t

    let v = 0
    let p = 0
    forEachActive(segments, t, (i, u) => {
      const isVan = segments.intent[i] === DELIVERY_VAN
      const mesh = isVan ? vanMesh : parcelMesh
      const n = isVan ? v : p
      if (n >= capacity) return

      const ax = segments.ax[i], az = segments.az[i]
      const dx = segments.bx[i] - ax, dz = segments.bz[i] - az
      const planar = Math.hypot(dx, dz)
      // A van waiting at the dock has no direction of its own, so it keeps the
      // heading of the apron it backed onto rather than snapping to an axis.
      const yaw = planar > 1e-6 ? Math.atan2(dx, dz) : 0
      scratch.quaternion.setFromAxisAngle(scratch.up, yaw)
      scratch.position.set(
        ax + dx * u,
        isVan ? VAN.h / 2 : CARRY,
        az + dz * u,
      )
      scratch.matrix.compose(scratch.position, scratch.quaternion, scratch.scale)
      mesh.setMatrixAt(n, scratch.matrix)
      mesh.setColorAt(n, DELIVERY_COLOR[segments.intent[i]] ?? DELIVERY_COLOR[0])
      if (isVan) v++
      else p++
    })

    vanMesh.count = v
    parcelMesh.count = p
    for (const [mesh, n] of [[vanMesh, v], [parcelMesh, p]] as const) {
      if (n === 0) continue
      mesh.instanceMatrix.needsUpdate = true
      if (mesh.instanceColor) mesh.instanceColor.needsUpdate = true
    }
  })

  return (
    <>
      <instancedMesh ref={vans} args={[undefined, undefined, capacity]}
                     castShadow frustumCulled={false} raycast={() => null}>
        <boxGeometry args={[VAN.w, VAN.h, VAN.l]} />
        <meshLambertMaterial flatShading />
      </instancedMesh>
      <instancedMesh ref={parcels} args={[undefined, undefined, capacity]}
                     castShadow frustumCulled={false} raycast={() => null}>
        <boxGeometry args={[PARCEL, PARCEL, PARCEL]} />
        <meshLambertMaterial flatShading />
      </instancedMesh>
    </>
  )
}
