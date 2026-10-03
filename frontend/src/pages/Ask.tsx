import { useState } from 'react'
import { Link } from 'react-router-dom'
import { get, type ReceiptSummary, type Row, type Task } from '../api'
import { ErrorBox, fmtDate, fmtQty, useAction } from '../ui'

/**
 * The ask box (ADR-0024): a question in plain words answered from the live lists of the site, with a link to each
 * document. It is rules, not a language model: it recognises the questions below and says so when it does not.
 */
export const ASK_EXAMPLES = ['what is late for UPSN', 'which orders are short', 'what failed to post',
  'which tasks are stuck', 'what is waiting at the dock', 'transfers to ST03', 'where is SKU-1']

export interface Answer {
  text: string
  items: { label: string; to: string; detail?: string }[]
}

const minutesUntil = (v: unknown) => (v ? (new Date(String(v)).getTime() - Date.now()) / 60_000 : Infinity)
const OPEN = (o: Row) => !['SHIPPED', 'CONFIRMED', 'CANCELLED'].includes(String(o.status))

export async function answer(site: string, question: string): Promise<Answer> {
  const q = question.trim()
  const lower = q.toLowerCase()
  const word = (re: RegExp) => re.exec(q)?.[1]?.toUpperCase()
  const orders = () => get<Row[]>(`/api/v1/sites/${site}/outbound/orders`)

  if (/\blate\b|overdue|behind|miss(ing)? (the )?cutoff/.test(lower)) {
    const carrier = word(/\b(?:for|by|with|carrier)\s+([A-Za-z0-9]{2,6})\b/)
    const late = (await orders()).filter(OPEN)
      .filter((o) => !carrier || String(o.carrier_scac ?? '').toUpperCase() === carrier)
      .filter((o) => o.status === 'SHIP_ERROR' || minutesUntil(o.cutoff_at ?? o.planned_gi_utc) < 0)
    return {
      text: `${late.length} ${carrier ? `${carrier} ` : ''}order(s) past the carrier cutoff or planned goods issue, or with a goods issue the ERP refused, at ${site}`,
      items: late.map((o) => ({ label: String(o.erp_doc_no), to: `/orders/${String(o.erp_doc_no)}`,
        detail: `${String(o.status)} · ${String(o.carrier_scac ?? 'no carrier')} · due ${fmtDate(o.cutoff_at ?? o.planned_gi_utc)}` })),
    }
  }
  if (/\bshort|backorder/.test(lower)) {
    const short = (await orders()).filter((o) => OPEN(o) && Number(o.lines_short ?? 0) > 0)
    return {
      text: `${short.length} open order(s) with lines short at ${site}; "Reallocate" on the overview tries again now`,
      items: short.map((o) => ({ label: String(o.erp_doc_no), to: `/orders/${String(o.erp_doc_no)}`,
        detail: `${String(o.lines_short)} line(s) short · ${String(o.status)}` })),
    }
  }
  if (/fail|error|reject|post/.test(lower)) {
    const [receipts, all] = await Promise.all([get<ReceiptSummary[]>(`/api/v1/sites/${site}/receipts?status=POSTING_FAILED`), orders()])
    const shipErrors = all.filter((o) => o.status === 'SHIP_ERROR')
    return {
      text: `${receipts.length} receipt(s) and ${shipErrors.length} goods issue(s) the ERP refused at ${site}`,
      items: [
        ...receipts.map((r) => ({ label: r.erpDocNo, to: `/receipts/${r.erpDocNo}`, detail: `receipt · ${r.status}` })),
        ...shipErrors.map((o) => ({ label: String(o.erp_doc_no), to: `/orders/${String(o.erp_doc_no)}`, detail: 'goods issue failed' })),
      ],
    }
  }
  if (/stuck|stale|assigned|exception/.test(lower)) {
    const tasks = await get<Task[]>(`/api/v1/sites/${site}/tasks`)
    const stuck = tasks.filter((t) => t.status === 'EXCEPTION'
      || (t.status === 'ASSIGNED' && t.assignedAt && Date.now() - new Date(t.assignedAt).getTime() > 30 * 60_000))
    return {
      text: `${stuck.length} task(s) in exception or assigned over 30 min at ${site}; "Unassign" on the overview puts them back in the queue`,
      items: stuck.map((t) => ({ label: `${t.taskType} ${t.docNo ?? t.orderRef ?? t.lpnId ?? ''}`.trim(), to: `/tasks?q=${encodeURIComponent(t.lpnId || t.fromLocation)}`,
        detail: `${t.status}${t.exceptionReason ? ` (${t.exceptionReason})` : ''} · ${t.assignedTo ?? 'unassigned'} · ${t.fromLocation}` })),
    }
  }
  if (/dock|staging|put ?away/.test(lower)) {
    const dock = await get<Row[]>(`/api/v1/sites/${site}/inventory/inbound-staging`)
    return {
      text: `${dock.length} balance(s) at the dock or receiving at ${site}`,
      items: dock.map((b) => ({ label: `${String(b.item_no)} ${fmtQty(b.qty)}`, to: `/tasks?type=PUTAWAY&q=${encodeURIComponent(String(b.lpn_id || b.location_id))}`,
        detail: `${String(b.location_id)} ${String(b.lpn_id || 'loose')} · ${String(b.stock_status)} · since ${fmtDate(b.receipt_date)}` })),
    }
  }
  const toSite = word(/\btransfers?\b.*\b(?:to|for)\s+([A-Za-z0-9-]+)/)
  if (/transfer/.test(lower)) {
    const rows = await get<Row[]>(`/api/v1/sites/${site}/outbound/transfers?direction=OUT`)
    const hits = rows.filter((r) => !toSite || String(r.to_site ?? '').toUpperCase() === toSite)
    return {
      text: `${hits.length} transfer(s) from ${site}${toSite ? ` to ${toSite}` : ''}`,
      items: hits.map((r) => ({ label: String(r.erp_doc_no), to: `/orders/${String(r.erp_doc_no)}`,
        detail: `${String(r.status)} → ${String(r.to_site ?? '')}` })),
    }
  }
  const item = word(/\b(?:where is|stock of|find|how much)\s+([A-Za-z0-9._-]+)/i)
  if (item) {
    return { text: `Stock of ${item} at ${site}: open the stock inquiry and search the item`, items: [{ label: `Stock inquiry: ${item}`, to: '/inventory' }] }
  }
  return { text: `Not a question I know yet. Try: ${ASK_EXAMPLES.join(' · ')}`, items: [] }
}

export function AskBox({ site }: { site: string }) {
  const [q, setQ] = useState('')
  const ask = useAction(() => answer(site, q))
  return (
    <div className="ask">
      <form className="row" onSubmit={(e) => { e.preventDefault(); if (q.trim()) void ask.run() }}>
        <input className="grow" value={q} onChange={(e) => setQ(e.target.value)} placeholder={`Ask about ${site}, e.g. "${ASK_EXAMPLES[0]}"`}
               aria-label="Ask a question" list="ask-examples" />
        <datalist id="ask-examples">{ASK_EXAMPLES.map((x) => <option key={x} value={x} />)}</datalist>
        <button className="primary" disabled={ask.busy || !q.trim()}>Ask</button>
      </form>
      <ErrorBox error={ask.error} />
      {ask.result && (
        <div className="ask-answer">
          <p>{ask.result.text}</p>
          {ask.result.items.length > 0 && (
            <ul>
              {ask.result.items.slice(0, 25).map((i, n) => (
                <li key={n}><Link to={i.to}>{i.label}</Link>{i.detail && <span className="muted"> · {i.detail}</span>}</li>
              ))}
              {ask.result.items.length > 25 && <li className="muted">and {ask.result.items.length - 25} more</li>}
            </ul>
          )}
        </div>
      )}
    </div>
  )
}
