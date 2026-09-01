import { Canvas, useFrame } from '@react-three/fiber'
import { OrbitControls } from '@react-three/drei'
import { useEffect, useRef } from 'react'
import type { OrbitControls as OrbitControlsImpl } from 'three-stdlib'
import * as THREE from 'three'
import { Building } from './Building'
import { useSim, type CameraPreset } from '../store'
import type { Layout } from '../types'
import type { Room } from '../sim/rooms'

/** Camera presets named in §7: Aerial · Lobby · Wing A. */
function presetView(preset: CameraPreset, layout: Layout): { pos: THREE.Vector3; target: THREE.Vector3 } {
  const wing = layout.wings[0]
  switch (preset) {
    case 'lobby':
      return {
        pos: new THREE.Vector3(layout.entrance.x, 18, layout.entrance.z + 45),
        target: new THREE.Vector3(layout.lobby_hub.x, 2, layout.lobby_hub.z),
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

function CameraRig({ layout }: { layout: Layout }) {
  const controls = useRef<OrbitControlsImpl>(null)
  const preset = useSim((s) => s.preset)

  useEffect(() => {
    const c = controls.current
    if (!c) return
    const { pos, target } = presetView(preset, layout)
    c.object.position.copy(pos)
    c.target.copy(target)
    c.update()
  }, [preset, layout])

  return <OrbitControls ref={controls} makeDefault enableDamping dampingFactor={0.08} maxPolarAngle={Math.PI / 2.05} />
}

export function Stage({ layout, rooms }: { layout: Layout; rooms: Room[] }) {
  return (
    <Canvas
      shadows
      dpr={[1, 2]}
      camera={{ position: [30, 150, 190], fov: 45, near: 0.5, far: 2000 }}
      gl={{ antialias: true }}
    >
      <color attach="background" args={['#0e1013']} />
      <fog attach="fog" args={['#0e1013', 260, 620]} />
      <ambientLight intensity={0.55} />
      <hemisphereLight args={['#9fb4d2', '#1b1f26', 1.05]} />
      <directionalLight position={[80, 140, 90]} intensity={1.0} castShadow />
      <Building layout={layout} rooms={rooms} />
      <CameraRig layout={layout} />
      <Clock />
    </Canvas>
  )
}
