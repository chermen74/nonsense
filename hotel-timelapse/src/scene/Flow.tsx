/**
 * §6.6 flow mode: above 1,000x the capsules give way to particles running
 * along the corridor edges, with density proportional to the movement active
 * on each edge.
 *
 * At that speed a walker crosses the property inside a single frame, so a
 * capsule at its exact position is a dot that teleports. A leg's particles are
 * therefore spread along the whole edge for as long as the leg is live: two
 * parties walking the same corridor put twice the particles on it, which is
 * the density the spec asks for, without a per-frame tally of edges.
 *
 * The drift phase advances in real time (`t / speed`), so the streams flow at
 * a readable rate whatever the clock is doing -- and, like everything else in
 * the scene, it stays a pure function of `(t, speed)`.
 */

import { useFrame } from '@react-three/fiber'
import { useMemo, useRef } from 'react'
import * as THREE from 'three'
import { forEachActive, intentMask, showsIntent, type Segments } from '../sim/segments'
import { FLOW_MIN_SPEED, useSim } from '../store'
import { intentColor } from './palette'

/** Particles one leg contributes, capped so a big party cannot flood an edge. */
const PER_LEG_MAX = 6
/** Real seconds for a particle to travel its edge once. */
const DRIFT_SECONDS = 1.2

export function Flow({ segments, capacity }: { segments: Segments; capacity: number }) {
  const geometry = useRef<THREE.BufferGeometry>(null!)

  const buffers = useMemo(() => ({
    position: new Float32Array(capacity * 3),
    color: new Float32Array(capacity * 3),
  }), [capacity])

  useFrame(() => {
    const geom = geometry.current
    if (!geom) return

    const { t, speed, filter } = useSim.getState()
    // Emptiness is expressed through the draw range, never through `visible`:
    // the parent re-renders on the clock, so any JSX visibility prop would be
    // reapplied every frame and undo whatever this loop set.
    if (speed <= FLOW_MIN_SPEED) {
      geom.setDrawRange(0, 0)
      return
    }

    const mask = intentMask(filter)
    const { position, color } = buffers
    const cycle = (t / (speed * DRIFT_SECONDS * 1000)) % 1

    let n = 0
    forEachActive(segments, t, (i) => {
      if (n >= capacity) return
      if (!showsIntent(mask, segments.intent[i])) return

      const ax = segments.ax[i], ay = segments.ay[i], az = segments.az[i]
      const dx = segments.bx[i] - ax, dy = segments.by[i] - ay, dz = segments.bz[i] - az
      if (dx * dx + dy * dy + dz * dz < 1e-4) return   // a dwell is not a flow

      const hue = intentColor(segments.intent[i])
      const count = Math.min(segments.party[i], PER_LEG_MAX)
      for (let k = 0; k < count && n < capacity; k++) {
        const f = (cycle + segments.jitter[i] + k / count) % 1
        position[n * 3] = ax + dx * f
        position[n * 3 + 1] = ay + dy * f + 0.9
        position[n * 3 + 2] = az + dz * f
        color[n * 3] = hue.r
        color[n * 3 + 1] = hue.g
        color[n * 3 + 2] = hue.b
        n++
      }
    })

    geom.setDrawRange(0, n)
    if (n > 0) {
      geom.attributes.position.needsUpdate = true
      geom.attributes.color.needsUpdate = true
    }
  })

  return (
    <points frustumCulled={false} raycast={() => null}>
      <bufferGeometry ref={geometry}>
        <bufferAttribute attach="attributes-position" args={[buffers.position, 3]} />
        <bufferAttribute attach="attributes-color" args={[buffers.color, 3]} />
      </bufferGeometry>
      <pointsMaterial size={1.4} sizeAttenuation vertexColors toneMapped={false} />
    </points>
  )
}
