/**
 * §14's global waterfall — SPEND_SPEC §16 step 11.
 *
 * One bar per department: revenue on the upper track, the three cost lines
 * stacked on the lower one (cost of sales grey, labor blue, other expense
 * slate), all on the one shared scale `waterfallScale` fixes at the file
 * totals. The residual — department profit — is the overhang of one track
 * past the other, and the figure at the right of the row. GOP and its margin
 * sit at the bottom and move every frame.
 *
 * §14 asks for "every frame", which is the one thing React must not do here.
 * The skeleton is rendered once; after that a store subscription writes bar
 * transforms and text straight onto the nodes, so a frame costs ten curve
 * lookups and forty style writes instead of a reconciliation pass. The bars
 * are `translateX` + `scaleX` rather than widths so nothing relayouts.
 *
 * Read from the top, it is §10's arithmetic in order: the operated
 * departments' profit, less what the support and undistributed departments
 * cost, is GOP. Fixed charges are the one toggle-able line below it.
 */

import { useEffect, useRef } from 'react'
import { useSim } from '../store'
import { waterfallRows, waterfallScale, type WaterfallRow } from '../sim/waterfall'
import { clockLabel } from '../sim/tz'
import { PanelTabs } from './PanelTabs'

/** The four segments and the profit figure of one department's row. */
interface RowNodes {
  revenue: HTMLElement | null
  cos: HTMLElement | null
  labor: HTMLElement | null
  other: HTMLElement | null
  profit: HTMLElement | null
}

function place(el: HTMLElement | null, offset: number, size: number) {
  if (!el) return
  el.style.transform = `translateX(${(offset * 100).toFixed(4)}%) scaleX(${size.toFixed(5)})`
}

function setText(el: HTMLElement | null, text: string) {
  if (el && el.textContent !== text) el.textContent = text
}

export function Waterfall() {
  const costs = useSim((s) => s.costs)
  const accrual = useSim((s) => s.accrual)
  const property = useSim((s) => s.property)
  const showFixed = useSim((s) => s.showFixed)
  const toggleFixed = useSim((s) => s.toggleFixed)

  const rowNodes = useRef(new Map<string, RowNodes>())
  const clockEl = useRef<HTMLParagraphElement>(null)
  const deptProfitEl = useRef<HTMLElement>(null)
  const overheadEl = useRef<HTMLElement>(null)
  const gopEl = useRef<HTMLElement>(null)
  const marginEl = useRef<HTMLElement>(null)
  const fixedEl = useRef<HTMLElement>(null)
  const afterFixedEl = useRef<HTMLElement>(null)

  const departments = costs?.departments ?? []

  useEffect(() => {
    if (!costs || !accrual || !property) return
    const scale = waterfallScale(costs)
    const rows: WaterfallRow[] = []
    // The viewer's own locale formats the numbers; the property file supplies
    // the currency, and its `region` is a place name, not a language tag.
    const whole = new Intl.NumberFormat(undefined, {
      style: 'currency', currency: property.currency, maximumFractionDigits: 0,
    })
    const compact = new Intl.NumberFormat(undefined, {
      style: 'currency', currency: property.currency,
      notation: 'compact', maximumFractionDigits: 1,
    })
    // A negative goes in parentheses, as a P&L reads.
    const signed = (v: number, fmt: Intl.NumberFormat) =>
      v < -0.5 ? `(${fmt.format(-v)})` : fmt.format(Math.max(v, 0))

    const paint = (t: number) => {
      setText(clockEl.current, clockLabel(t, accrual.tz))

      waterfallRows(costs, t, scale, rows)
      for (const row of rows) {
        const nodes = rowNodes.current.get(row.id)
        if (!nodes) continue
        place(nodes.revenue, 0, row.revenueFrac)
        place(nodes.cos, 0, row.cosFrac)
        place(nodes.labor, row.cosFrac, row.laborFrac)
        place(nodes.other, row.cosFrac + row.laborFrac, row.otherFrac)
        setText(nodes.profit, signed(row.lines.profit, compact))
        nodes.profit?.classList.toggle('neg', row.lines.profit < -0.5)
      }

      const hotel = costs.hotel(t)
      setText(deptProfitEl.current, signed(hotel.deptProfitTotal, whole))
      setText(overheadEl.current, signed(-hotel.undistributedTotal, whole))
      setText(gopEl.current, signed(hotel.gop, whole))
      setText(marginEl.current, `${(hotel.gopMargin * 100).toFixed(1)}%`)
      setText(fixedEl.current, signed(-hotel.fixedCharges, whole))
      setText(afterFixedEl.current, signed(hotel.gop - hotel.fixedCharges, whole))
    }

    paint(useSim.getState().t)
    let last = useSim.getState().t
    return useSim.subscribe((s) => {
      if (s.t === last) return
      last = s.t
      paint(s.t)
    })
    // `showFixed` is in the deps so the newly mounted fixed-charge nodes get
    // their first paint; the subscription itself does not care.
  }, [costs, accrual, property, showFixed])

  if (!costs || !accrual || !property) return null

  const nodesFor = (id: string): RowNodes => {
    let nodes = rowNodes.current.get(id)
    if (!nodes) {
      nodes = { revenue: null, cos: null, labor: null, other: null, profit: null }
      rowNodes.current.set(id, nodes)
    }
    return nodes
  }

  return (
    <section className="tally pnl" aria-live="off">
      <header>
        <PanelTabs />
        <p ref={clockEl} />
      </header>

      <ol className="wf">
        {departments.map((dept) => {
          const nodes = nodesFor(dept.id)
          return (
            <li key={dept.id} className={`wf-row ${dept.type}`}>
              <span className="wf-name" title={dept.name}>{dept.name}</span>
              <div className="wf-bars">
                <div className="wf-track">
                  <i className="wf-seg rev" ref={(el) => { nodes.revenue = el }} />
                </div>
                <div className="wf-track">
                  <i className="wf-seg cos" ref={(el) => { nodes.cos = el }} />
                  <i className="wf-seg labor" ref={(el) => { nodes.labor = el }} />
                  <i className="wf-seg other" ref={(el) => { nodes.other = el }} />
                </div>
              </div>
              <b className="wf-profit" ref={(el) => { nodes.profit = el }} />
            </li>
          )
        })}
      </ol>

      <ul className="wf-key">
        <li><i className="rev" />Revenue</li>
        <li><i className="cos" />Cost of sales</li>
        <li><i className="labor" />Labor</li>
        <li><i className="other" />Other</li>
      </ul>

      <hr />
      <div className="tally-line"><span>Department profit</span><b ref={deptProfitEl} /></div>
      <div className="tally-line"><span>Support &amp; undistributed</span><b ref={overheadEl} /></div>
      <div className="tally-line strong gop">
        <span>GOP <em ref={marginEl} /></span><b ref={gopEl} />
      </div>

      {showFixed && (
        <>
          <div className="tally-line"><span>Fixed charges</span><b ref={fixedEl} /></div>
          <div className="tally-line"><span>After fixed charges</span><b ref={afterFixedEl} /></div>
        </>
      )}
      <button type="button" className="wf-fixed" aria-pressed={showFixed} onClick={toggleFixed}>
        {showFixed ? 'Hide fixed charges' : 'Show fixed charges'}
      </button>
    </section>
  )
}
