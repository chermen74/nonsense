/**
 * §6 colour by intent, shared by the capsules, their trails and the flow
 * particles so one walker never changes colour when the renderer switches.
 *
 * §13 puts staff on "a separate colour channel". Guests are warm and varied —
 * teal, amber, coral, violet — and staff are one blue family, so a glance at
 * the scene separates the people being served from the people serving. The
 * family centre is the blue the §14 waterfall draws labor in: the dollars in
 * that bar and the bodies on the floor are the same people.
 */

import * as THREE from 'three'
import { intentMask, type TallyLine } from '../sim/segments'
import { staffIntentMask } from '../sim/staff'

export const INTENT_COLOR = [
  new THREE.Color('#2fb3a0'),   // arriving -- teal
  new THREE.Color('#e0a23a'),   // departing -- amber
  new THREE.Color('#ef7b5a'),   // dining -- coral
  new THREE.Color('#9b6fe0'),   // banquet -- violet
]

export function intentColor(intent: number): THREE.Color {
  return INTENT_COLOR[intent] ?? INTENT_COLOR[0]
}

/** §13, in the order of the `STAFF_*` intents in `sim/staff.ts`. */
export const STAFF_COLOR = [
  new THREE.Color('#8794a8'),   // clocking in or out -- slate
  new THREE.Color('#63c2e8'),   // front desk
  new THREE.Color('#4cd0c0'),   // housekeeping
  new THREE.Color('#4c7fd1'),   // kitchen -- the waterfall's labor blue
  new THREE.Color('#6f9fe8'),   // outlet floor
  new THREE.Color('#8fb0ff'),   // banquet
  new THREE.Color('#3f6fa8'),   // engineering
  new THREE.Color('#6b7a91'),   // on station
]

export function staffColor(intent: number): THREE.Color {
  return STAFF_COLOR[intent] ?? STAFF_COLOR[0]
}

/**
 * One population's colours and its reading of §7's department filter. The
 * renderers take a channel rather than knowing about guests or staff, so both
 * populations go through exactly the same instanced path.
 */
export interface Channel {
  color(intent: number): THREE.Color
  mask(filter: TallyLine | null): number
}

export const GUEST_CHANNEL: Channel = { color: intentColor, mask: intentMask }
export const STAFF_CHANNEL: Channel = { color: staffColor, mask: staffIntentMask }

/** The scene background. A trail fades into it rather than to transparent. */
export const BACKGROUND = new THREE.Color('#0e1013')
