/**
 * The side panel's two faces — §14's live P&L waterfall and §7's revenue
 * tally. §14 says the tally panel *becomes* the waterfall in the global view,
 * so the waterfall is the default; the tally is one click away because §7's
 * department filter lives on its lines.
 */

import { useSim, type PanelView } from '../store'

const TABS: { view: PanelView; label: string }[] = [
  { view: 'pnl', label: 'P&L' },
  { view: 'revenue', label: 'Revenue' },
]

export function PanelTabs() {
  const panel = useSim((s) => s.panel)
  const setPanel = useSim((s) => s.setPanel)
  return (
    <div className="panel-tabs" role="tablist" aria-label="Panel view">
      {TABS.map(({ view, label }) => (
        <button key={view} type="button" role="tab" aria-selected={panel === view}
                onClick={() => setPanel(view)}>{label}</button>
      ))}
    </div>
  )
}
