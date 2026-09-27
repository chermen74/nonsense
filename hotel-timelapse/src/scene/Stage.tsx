import { Canvas, useFrame } from '@react-three/fiber'
import { OrbitControls } from '@react-three/drei'
import { useEffect, useRef } from 'react'
import type { OrbitControls as OrbitControlsImpl } from 'three-stdlib'
import * as THREE from 'three'
import { Building } from './Building'
import { Deliveries } from './Deliveries'
import { Flow } from './Flow'
import { Guests } from './Guests'
import { Trails } from './Trails'
import { useSim, type CameraPreset } from '../store'
import type { Layout } from '../types'
import type { Room } from '../sim/rooms'
import type { Lighting, Segments } from '../sim/segments'
import type { Turns } from '../sim/staff'
import type { Venues } from '../sim/venues'
import { STAFF_CHANNEL } from './palette'

/**
 * §14: "camera flies to its preset". The department's own `camera` in
 * layout.json is where it flies to; its `anchor` is what it looks at.
 */
function deptView(layout: Layout, id: string) {
  const dept = (layout.departments ?? []).find((d) => d.id === id)
  if (!dept) return null
  return {
    pos: new THREE.Vector3(dept.camera.x, dept.camera.y, dept.camera.z),
    target: new THREE.Vector3(dept.anchor.x, dept.anchor.y + 1.2, dept.anchor.z),
  }
}

/** Camera presets named in §7: Aerial · Lobby · Wing A. */
function presetView(preset: CameraPreset, layout: Layout): { pos: THREE.Vector3; target: THREE.Vector3 } {
  const wing = layout.wings[0]
  switch (preset) {
    case 'lobby':
      // Close enough that a 1.7 m guest reads as a person, not a speck.
      return {
        pos: new THREE.Vector3(layout.front_desk.x + 6, 7, layout.entrance.z + 12),
        target: new THREE.Vector3(layout.front_desk.x, 1.2, layout.front_desk.z - 4),
      }
    case 'wing': {
      const len = (wing.rooms_per_floor - 1) * wing.room_pitch
      const cx = wing.origin.x + (wing.dir.x * len) / 2
      const cz = wing.origin.z + (wing.dir.z * len) / 2
      return {
        pos: new THREE.Vector3(cx, 26, cz + 70),
        target: new THREE.Vector3(cx, 6, cz),
      }
    }
    default:
      return { pos: new THREE.Vector3(30, 150, 190), target: new THREE.Vector3(10, 0, -10) }
  }
}

function Clock() {
  const advance = useSim((s) => s.advance)
  useFrame((_, delta) => advance(Math.min(delta, 0.5)))
  return null
}

/** How long the §14 fly-to takes, in real seconds. */
const FLIGHT = 0.9

function CameraRig({ layout }: { layout: Layout }) {
  const controls = useRef<OrbitControlsImpl>(null)
  const camera = useSim((s) => s.camera)
  /** Where the flight is going, and how far through it we are. */
  const flight = useRef<{ pos: THREE.Vector3; target: THREE.Vector3; u: number } | null>(null)

  useEffect(() => {
    const c = controls.current
    if (!c) return
    const view = camera.kind === 'dept'
      ? deptView(layout, camera.key)
      : presetView(camera.key as CameraPreset, layout)
    if (!view) return
    // §14 says the camera *flies*. A cut would lose the one thing the move is
    // for: seeing which part of the building the department you clicked is.
    flight.current = { ...view, u: 0 }
    // `camera.seq` is in the deps on purpose: asking for the same view twice
    // is two instructions, and the second one still has to fly.
  }, [camera, layout])

  useFrame((_, delta) => {
    const c = controls.current
    const f = flight.current
    if (!c || !f) return
    f.u = Math.min(f.u + delta / FLIGHT, 1)
    // Ease out, so it arrives rather than stopping.
    const k = 1 - Math.pow(1 - f.u, 3)
    c.object.position.lerp(f.pos, k)
    c.target.lerp(f.target, k)
    c.update()
    if (f.u >= 1) flight.current = null
  })

  return <OrbitControls ref={controls} makeDefault enableDamping dampingFactor={0.08} maxPolarAngle={Math.PI / 2.05} />
}

/**
 * §14: "the scene dims everything outside the zone".
 *
 * Done with the lights rather than per instance, because almost everything in
 * the scene is lit: dropping the ambient and hemisphere and putting a lamp
 * over the department's anchor dims the ground, the corridors, the room
 * bodies, the capsules and the vans in one move. The two things the lights do
 * not reach -- the unlit window faces and the flow particles -- dim themselves
 * against the zone.
 */
function Lights({ layout }: { layout: Layout }) {
  const zoom = useSim((s) => s.zoom)
  const dept = zoom ? (layout.departments ?? []).find((d) => d.id === zoom) : null
  return (
    <>
      <ambientLight intensity={dept ? 0.16 : 0.55} />
      <hemisphereLight args={['#9fb4d2', '#1b1f26', dept ? 0.3 : 1.05]} />
      <directionalLight position={[80, 140, 90]} intensity={dept ? 0.3 : 1.0} castShadow />
      {dept && (
        <pointLight position={[dept.anchor.x, 22, dept.anchor.z]}
                    intensity={2600} distance={130} decay={2} color="#eaf0ff" />
      )}
    </>
  )
}

export function Stage({ layout, rooms, lighting, segments, staff, turns, deliveries, venues,
                       guestCapacity, staffCapacity, deliveryCapacity }: {
  layout: Layout
  rooms: Room[]
  lighting: Lighting
  segments: Segments
  staff: Segments
  turns: Turns
  deliveries: Segments
  venues: Venues
  guestCapacity: number
  staffCapacity: number
  deliveryCapacity: number
}) {
  // §13's toggle. Read here rather than inside the renderers so a hidden
  // population costs nothing at all, not merely an empty draw.
  const population = useSim((s) => s.population)
  const showGuests = population !== 'staff'
  const showStaff = population !== 'guests'
  return (
    <Canvas
      shadows
      dpr={[1, 2]}
      camera={{ position: [30, 150, 190], fov: 45, near: 0.5, far: 2000 }}
      gl={{ antialias: true }}
    >
      <color attach="background" args={['#0e1013']} />
      <fog attach="fog" args={['#0e1013', 260, 620]} />
      <Lights layout={layout} />
      <Building layout={layout} rooms={rooms} lighting={lighting} turns={turns} venues={venues} />
      {showGuests && <>
        <Guests segments={segments} capacity={guestCapacity} />
        <Trails segments={segments} capacity={guestCapacity} />
        <Flow segments={segments} capacity={guestCapacity} />
      </>}
      {showStaff && <>
        <Guests segments={staff} capacity={staffCapacity} channel={STAFF_CHANNEL} />
        <Trails segments={staff} capacity={staffCapacity} channel={STAFF_CHANNEL} />
        <Flow segments={staff} capacity={staffCapacity} channel={STAFF_CHANNEL} />
        {/* §12's vans are back-of-house machinery, so they come and go with
            the staff rather than with the guests they are invisible to. */}
        <Deliveries segments={deliveries} capacity={deliveryCapacity} />
      </>}
      <CameraRig layout={layout} />
      <Clock />
    </Canvas>
  )
}
