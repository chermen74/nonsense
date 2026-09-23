/**
 * §6.6 trails: the last 1.5 real seconds of a walker's path, drawn as a
 * fading line strip so motion stays legible when the clock is running fast.
 *
 * "1.5 real seconds" is a real-time length, so in sim time it is
 * `1.5 s x speed` -- fifteen sim-minutes at 600x. Writing it that way keeps
 * the trail a pure function of `(t, speed)`: nothing accumulates between
 * frames, so scrubbing backwards costs what playing forwards costs, and a
 * paused scene holds a still trail instead of a smear that decays while you
 * look at it.
 *
 * The trail is clipped to the leg its walker is on. At speed the whole leg
 * fits inside the window anyway, which is the point -- each walker becomes a
 * streak along the corridor they are in.
 */

import { useFrame } from '@react-three/fiber'
import { useMemo, useRef } from 'react'
import * as THREE from 'three'
import { forEachActive, intentMask, showsIntent, type Segments } from '../sim/segments'
import { useSim } from '../store'
import { BACKGROUND, intentColor } from './palette'

/** Points along one trail; four line segments is enough to read as a streak. */
const POINTS = 5
const SEGMENTS = POINTS - 1
const TRAIL_REAL_SECONDS = 1.5

export function Trails({ segments, capacity }: { segments: Segments; capacity: number }) {
  const geometry = useRef<THREE.BufferGeometry>(null!)

  const buffers = useMemo(() => {
    const vertices = capacity * SEGMENTS * 2
    return {
      position: new Float32Array(vertices * 3),
      color: new Float32Array(vertices * 3),
    }
  }, [capacity])

  const scratch = useMemo(() => ({ head: new THREE.Color(), tail: new THREE.Color() }), [])

  useFrame(() => {
    const geom = geometry.current
    if (!geom) return
    const { t, speed, filter } = useSim.getState()
    const mask = intentMask(filter)
    const windowMs = TRAIL_REAL_SECONDS * 1000 * speed
    const { position, color } = buffers

    let v = 0                                    // vertices written
    forEachActive(segments, t, (i, u) => {
      if (v + SEGMENTS * 2 > capacity * SEGMENTS * 2) return
      if (!showsIntent(mask, segments.intent[i])) return

      const ax = segments.ax[i], ay = segments.ay[i], az = segments.az[i]
      const dx = segments.bx[i] - ax, dy = segments.by[i] - ay, dz = segments.bz[i] - az
      if (dx * dx + dy * dy + dz * dz < 1e-4) return   // a dwell leaves no trail

      const span = segments.t1[i] - segments.t0[i]
      if (span <= 0) return
      const u0 = Math.max(0, u - windowMs / span)
      if (u - u0 < 1e-4) return

      scratch.head.copy(intentColor(segments.intent[i]))

      for (let k = 0; k < SEGMENTS; k++) {
        // Two vertices per segment: LineSegments, not a strip, so one buffer
        // holds every walker's trail without stitching them together.
        for (const end of [k, k + 1]) {
          const f = u0 + ((u - u0) * end) / SEGMENTS
          position[v * 3] = ax + dx * f
          position[v * 3 + 1] = ay + dy * f
          position[v * 3 + 2] = az + dz * f
          scratch.tail.copy(BACKGROUND).lerp(scratch.head, end / SEGMENTS)
          color[v * 3] = scratch.tail.r
          color[v * 3 + 1] = scratch.tail.g
          color[v * 3 + 2] = scratch.tail.b
          v++
        }
      }
    })

    geom.setDrawRange(0, v)
    if (v > 0) {
      geom.attributes.position.needsUpdate = true
      geom.attributes.color.needsUpdate = true
    }
  })

  return (
    <lineSegments frustumCulled={false} raycast={() => null}>
      <bufferGeometry ref={geometry}>
        <bufferAttribute attach="attributes-position" args={[buffers.position, 3]} />
        <bufferAttribute attach="attributes-color" args={[buffers.color, 3]} />
      </bufferGeometry>
      <lineBasicMaterial vertexColors toneMapped={false} />
    </lineSegments>
  )
}
