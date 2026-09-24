/**
 * §6 colour by intent, shared by the capsules, their trails and the flow
 * particles so one walker never changes colour when the renderer switches.
 */

import * as THREE from 'three'

export const INTENT_COLOR = [
  new THREE.Color('#2fb3a0'),   // arriving -- teal
  new THREE.Color('#e0a23a'),   // departing -- amber
  new THREE.Color('#ef7b5a'),   // dining -- coral
  new THREE.Color('#9b6fe0'),   // banquet -- violet
]

export function intentColor(intent: number): THREE.Color {
  return INTENT_COLOR[intent] ?? INTENT_COLOR[0]
}

/** The scene background. A trail fades into it rather than to transparent. */
export const BACKGROUND = new THREE.Color('#0e1013')
