/**
 * Guest capsules -- BUILD_SPEC §9 step 5.
 *
 * One InstancedMesh for everyone on the property (CLAUDE.md: never a component
 * per guest). Each frame the live legs are found by binary search and their
 * capsules written straight into the instance matrix; nothing is stored between
 * frames, so scrubbing backwards costs the same as playing forwards.
 */

import { useFrame } from '@react-three/fiber'
import { useLayoutEffect, useMemo, useRef } from 'react'
import * as THREE from 'three'
import { forEachActive, intentMask, showsIntent, type Segments } from '../sim/segments'
import { BOB_MAX_SPEED, FLOW_MIN_SPEED, useSim } from '../store'
import { intentColor } from './palette'

const RADIUS = 0.4
const HEIGHT = 1.7
/** §6.1: a party walks together, offset about half a metre apart. */
const PARTY_SPACING = 0.5

/** §6.6: "a subtle bob" at 1x and 10x. Metres up and down, and its period. */
const BOB_HEIGHT = 0.07
const BOB_REAL_MS = 620

export function Guests({ segments, capacity }: { segments: Segments; capacity: number }) {
  const mesh = useRef<THREE.InstancedMesh>(null!)
  const scratch = useMemo(() => ({
    matrix: new THREE.Matrix4(),
    position: new THREE.Vector3(),
    quaternion: new THREE.Quaternion(),
    scale: new THREE.Vector3(1, 1, 1),
  }), [])

  useLayoutEffect(() => {
    // Colour is per intent and never changes for a given instance slot mid-frame,
    // so it is written alongside the matrix in the frame loop.
    mesh.current.count = 0
  }, [segments])

  useFrame(() => {
    const instanced = mesh.current
    if (!instanced) return
    const { t, speed, filter } = useSim.getState()

    // §6.6: above 1,000x the Flow particles take over and nobody is drawn here.
    if (speed > FLOW_MIN_SPEED) {
      instanced.count = 0
      return
    }
    const mask = intentMask(filter)
    // The bob's period is in real time, so it reads as a walking gait at 1x
    // and at 10x alike -- and stays a pure function of (t, speed).
    const bobbing = speed <= BOB_MAX_SPEED
    const bobPhase = t / (BOB_REAL_MS * speed)

    let n = 0
    forEachActive(segments, t, (i, u) => {
      if (!showsIntent(mask, segments.intent[i])) return
      const party = segments.party[i]
      const ax = segments.ax[i], ay = segments.ay[i], az = segments.az[i]
      const dx = segments.bx[i] - ax, dy = segments.by[i] - ay, dz = segments.bz[i] - az

      // Across the direction of travel, and along it. A dwell has no
      // direction, so fall back to the x axis.
      const planar = Math.hypot(dx, dz)
      const fx = planar > 1e-6 ? dx / planar : 0
      const fz = planar > 1e-6 ? dz / planar : 1
      const px = -fz
      const pz = fx

      // Each party stands in its own spot within the leg's footprint, so a
      // busy check-in reads as a crowd rather than one solid ridge.
      const spread = segments.spread[i]
      const jitter = segments.jitter[i]
      const across = (jitter - 0.5) * spread
      const along = (((jitter * 7.3) % 1) - 0.5) * spread * 0.45

      // Standing still is standing still: a dwell does not bob.
      const walking = planar > 1e-6 || Math.abs(dy) > 1e-6
      const hue = intentColor(segments.intent[i])

      for (let k = 0; k < party && n < capacity; k++) {
        const lane = (k - (party - 1) / 2) * PARTY_SPACING
        const bob = bobbing && walking
          ? Math.sin(bobPhase + (jitter + k * 0.37) * Math.PI * 2) * BOB_HEIGHT
          : 0
        scratch.position.set(
          ax + dx * u + px * (lane + across) + fx * along,
          ay + dy * u + HEIGHT / 2 + bob,
          az + dz * u + pz * (lane + across) + fz * along,
        )
        scratch.matrix.compose(scratch.position, scratch.quaternion, scratch.scale)
        instanced.setMatrixAt(n, scratch.matrix)
        instanced.setColorAt(n, hue)
        n++
      }
    })

    instanced.count = n
    if (n > 0) {
      instanced.instanceMatrix.needsUpdate = true
      if (instanced.instanceColor) instanced.instanceColor.needsUpdate = true
    }
  })

  return (
    <instancedMesh ref={mesh} args={[undefined, undefined, capacity]}
                   frustumCulled={false} raycast={() => null}>
      <capsuleGeometry args={[RADIUS, HEIGHT - RADIUS * 2, 4, 8]} />
      <meshLambertMaterial flatShading toneMapped={false} />
    </instancedMesh>
  )
}
