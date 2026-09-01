/** Transport bar — BUILD_SPEC §5. */

import { useSim, SPEED_PRESETS, SPEED_MAX, SPEED_MIN } from '../store'
import { clockLabel, dayKey, wallClock } from '../sim/tz'

const HOUR = 3600_000
const DAY = 24 * HOUR

export function Transport() {
  const s = useSim()
  const a = s.accrual
  if (!a) return null

  const span = a.periodEnd - a.periodStart
  const progress = (s.t - a.periodStart) / span

  return (
    <div className="transport">
      <div className="clock">
        <strong>{clockLabel(s.t, a.tz)}</strong>
        <div className="daybar"><span style={{ width: `${progress * 100}%` }} /></div>
      </div>

      <div className="buttons">
        <button type="button" onClick={() => s.step(-DAY)} title="Back one day" aria-label="Back one day">⏮</button>
        <button type="button" onClick={() => s.step(-HOUR)} title="Back one hour" aria-label="Back one hour">◀</button>
        <button type="button" className="play" onClick={s.toggle} aria-label={s.playing ? 'Pause' : 'Play'}>
          {s.playing ? '❚❚' : '▶'}
        </button>
        <button type="button" onClick={() => s.step(HOUR)} title="Forward one hour" aria-label="Forward one hour">▶</button>
        <button type="button" onClick={() => s.step(DAY)} title="Forward one day" aria-label="Forward one day">⏭</button>
      </div>

      <input
        className="scrub"
        type="range"
        min={a.periodStart}
        max={a.periodEnd}
        step={60_000}
        value={s.t}
        aria-label="Scrub through the month"
        onChange={(e) => { s.pause(); s.setT(Number(e.target.value)) }}
      />

      <label className="jump">
        <span className="sr">Jump to date</span>
        <input
          type="date"
          value={dayKey(s.t, a.tz)}
          min={dayKey(a.periodStart, a.tz)}
          max={dayKey(a.periodEnd - 1, a.tz)}
          onChange={(e) => {
            if (!e.target.value) return
            s.pause()
            s.setT(wallClock(e.target.value, 0, 0, a.tz))
          }}
        />
      </label>

      <div className="speeds">
        {SPEED_PRESETS.map((p) => (
          <button
            key={p}
            type="button"
            aria-pressed={s.speed === p}
            onClick={() => s.setSpeed(p)}
          >{p}×</button>
        ))}
        <input
          type="range"
          className="speed-slider"
          min={SPEED_MIN}
          max={SPEED_MAX}
          step={1}
          value={s.speed}
          aria-label="Playback speed"
          onChange={(e) => s.setSpeed(Number(e.target.value))}
        />
        <span className="speed-readout">{s.speed.toLocaleString()}×</span>
      </div>
    </div>
  )
}
