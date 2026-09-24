/** Shapes from BUILD_SPEC §3 and SPEND_SPEC §11. Nothing here is property-specific. */

export interface Meta {
  month: string
  tz: string
  period_start: string
  period_end: string
  source: string
  generated_at?: string
  cos_pct?: Record<string, { food: number; bev: number }>
  benefits_load?: number
}

export interface Night { date: string; rate: number }

export interface Stay {
  id: string
  room: string
  arrive: string
  depart: string
  guests: number
  market: string
  nights: Night[]
}

export interface Check {
  id: string
  outlet: string
  opened: string
  closed: string
  covers: number
  food: number
  bev: number
  room: string | null
}

export interface BanquetEvent {
  id: string
  function_room: string
  name: string
  start: string
  end: string
  attendees: number
  food: number
  bev: number
  room_rental: number
  av: number
}

/** SPEND_SPEC §11: one record per punch pair, already split by §15's rules. */
export interface Shift {
  id: string
  employee: string
  dept: string
  role: string
  in: string
  out: string
  rate: number
}

export interface Salaried {
  dept: string
  headcount: number
  monthly: number
}

export interface Expense {
  id: string
  dept: string
  category: string
  vendor: string
  date: string
  amount: number
  timing: 'invoice' | 'accrual'
}

export interface MonthData {
  meta: Meta
  stays: Stay[]
  checks: Check[]
  events: BanquetEvent[]
  shifts?: Shift[]
  salaried?: Salaried[]
  expenses?: Expense[]
  fixed_charges?: { monthly: number }
}

export interface Vec3 { x: number; y: number; z: number }

export interface Wing {
  id: string
  origin: { x: number; z: number }
  dir: { x: number; z: number }
  floors: number
  rooms_per_floor: number
  floor_height: number
  room_pitch: number
  room_numbers: Record<string, string>
}

export interface Outlet {
  id: string; name: string; x: number; y: number; z: number
  w: number; d: number; seats: number
}

export interface FunctionRoom {
  id: string; name: string; x: number; y: number; z: number
  w: number; d: number; capacity: number
}

export interface Department {
  id: string; name: string
  /** SPEND_SPEC §10: operated · support · undistributed. */
  type: string
  allocates_to?: string[]
  /**
   * Which revenue in the month file belongs to this department. Named in
   * config rather than inferred from ids that happen to match, so a property
   * whose POS calls an outlet something other than its department still ties.
   */
  sources?: { rooms?: boolean; outlets?: string[]; function_rooms?: string[] }
  anchor: Vec3
  camera: Vec3
}

export interface BohBox { id: string; dept: string; x: number; z: number; w: number; d: number }

export interface Layout {
  units: string
  entrance: Vec3
  staff_entrance?: Vec3
  loading_dock?: Vec3
  front_desk: Vec3
  lobby_hub: Vec3
  elevators: Array<{ id: string } & Vec3>
  wings: Wing[]
  outlets: Outlet[]
  function_rooms: FunctionRoom[]
  departments?: Department[]
  boh?: BohBox[]
}

export interface Property {
  id: string
  name: string
  /** Where the property is, for display. Not a BCP-47 tag. */
  region?: string
  timezone: string
  currency: string
  rooms: number
  logo?: string | null
  synthetic?: boolean
  notice?: string
  brand?: Record<string, string>
}
