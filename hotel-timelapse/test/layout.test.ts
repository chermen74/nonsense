/**
 * §9 step 2 acceptance: the layout must describe exactly the rooms the property
 * claims. The count is not hard-coded here -- a deployment swaps in its own
 * layout.json and property.json, and this still has to hold.
 */

import { readFileSync } from 'node:fs'
import { expandRooms, corridors } from '../src/sim/rooms'
import type { Layout, Property } from '../src/types'

const read = <T,>(p: string): T => JSON.parse(readFileSync(p, 'utf8')) as T

const layout = read<Layout>('public/layout.json')
const property = read<Property>('public/property.demo.json')

let failures = 0
function check(name: string, ok: boolean, detail: string) {
  if (!ok) failures++
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}: ${detail}`)
}

const rooms = expandRooms(layout)

check(
  'layout room count matches property.rooms',
  rooms.length === property.rooms,
  `layout expands to ${rooms.length}, property.json says ${property.rooms}`,
)

check(
  'every room number is unique',
  new Set(rooms.map((r) => r.number)).size === rooms.length,
  `${new Set(rooms.map((r) => r.number)).size} distinct of ${rooms.length}`,
)

check(
  'every wing floor declares a numbering range of the right size',
  layout.wings.every((w) =>
    Array.from({ length: w.floors }, (_, i) => w.room_numbers[`floor_${i + 1}`]).every((range) => {
      if (!range) return false
      const [a, b] = range.split('-').map(Number)
      return b - a + 1 === w.rooms_per_floor
    }),
  ),
  layout.wings.map((w) => `${w.id}:${w.floors}x${w.rooms_per_floor}`).join(' '),
)

check(
  'one corridor per floor per wing',
  corridors(layout).length === layout.wings.reduce((n, w) => n + w.floors, 0),
  `${corridors(layout).length} corridors`,
)

// §10: departments come from layout.json, so the file must actually carry them.
check(
  'layout declares departments with an anchor and a camera preset',
  Array.isArray(layout.departments) &&
    layout.departments.length > 0 &&
    layout.departments.every((d) => d.id && d.anchor && d.camera),
  `${layout.departments?.length ?? 0} departments`,
)

// Every outlet and function room the data can reference must exist in geometry.
check(
  'outlets and function rooms have ids',
  layout.outlets.every((o) => !!o.id) && layout.function_rooms.every((f) => !!f.id),
  `outlets ${layout.outlets.map((o) => o.id).join(',')} · rooms ${layout.function_rooms.map((f) => f.id).join(',')}`,
)

console.log(failures === 0 ? '\nALL CHECKS PASS' : `\n${failures} CHECK(S) FAILED`)
process.exit(failures === 0 ? 0 : 1)
