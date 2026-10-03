import { useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { get, newIdempotencyKey, type Row } from '../api'
import { parseGs1 } from '../gs1'
import { isNetworkError, isQueued, rfPost } from '../offline'
import { OfflineBar } from './OfflineBar'
import { Badge, ErrorBox, Field, Success, fmtQty, useAction, useSite } from '../ui'

/**
 * RF material issue (ADR-0022): scan the approved issue document, pick a line, scan the bin and the item (item
 * number, GTIN or GS1 label; a GS1 lot fills the lot), key the quantity and confirm. Returns of unused material work
 * the same way against the line.
 */
export default function RfIssue() {
  const site = useSite()
  const base = `/api/v1/sites/${site}/inventory/material-issues`
  const [docInput, setDocInput] = useState('')
  const [issue, setIssue] = useState<Row>()
  const [line, setLine] = useState<Row>()
  const [mode, setMode] = useState<'issue' | 'return'>('issue')
  const [f, setF] = useState({ location: '', lpn: '', item: '', lot: '', qty: '' })
  const [key, setKey] = useState(() => newIdempotencyKey('mi'))
  // ADR-0023: issue documents opened online are kept on the device, so issuing can go on without network.
  const cacheKey = (no: string) => `astra.offline.issue.${site}.${no}`
  const load = useAction(async (no: string) => {
    const id = no.trim().toUpperCase()
    let d: Row
    try {
      d = await get<Row>(`${base}/${encodeURIComponent(id)}`)
      try { window.localStorage.setItem(cacheKey(id), JSON.stringify(d)) } catch { /* storage unavailable */ }
    } catch (e) {
      const cached = (() => { try { return window.localStorage.getItem(cacheKey(id)) } catch { return null } })()
      if (!isNetworkError(e) || !cached) throw e
      d = JSON.parse(cached) as Row
    }
    setIssue(d)
    setLine(undefined)
    return d
  })
  const confirm = useAction(() => rfPost<Row>(`${base}/${String(issue?.issue_no)}/lines/${String(line?.line_no)}/${mode}`, {
    locationId: f.location.trim().toUpperCase(), lpnId: f.lpn.trim() || null, itemScan: f.item.trim(),
    lotNo: f.lot.trim() || null, qty: Number(f.qty),
  }, `${mode} ${String(issue?.issue_no)}/${String(line?.line_no)}`, key))
  const open = (l: Row) => Number(mode === 'issue' ? Number(l.qty_requested) - Number(l.qty_issued) : Number(l.qty_issued) - Number(l.qty_returned))
  const submit = async (e: FormEvent) => {
    e.preventDefault()
    const d = await confirm.run()
    if (d && isQueued(d) && issue && line) {
      // Kept on the device: count it locally so the screen shows what is left.
      const qty = Number(f.qty)
      const field = mode === 'issue' ? 'qty_issued' : 'qty_returned'
      const lines = (issue.lines as Row[]).map((l) => (l.line_no === line.line_no ? { ...l, [field]: Number(l[field]) + qty } : l))
      setIssue({ ...issue, lines })
      setKey(newIdempotencyKey('mi'))
      setF({ ...f, item: '', lot: '', qty: '', lpn: '' })
      setLine(undefined)
      return
    }
    if (d) {
      setIssue(d)
      setKey(newIdempotencyKey('mi'))
      setF({ ...f, item: '', lot: '', qty: '', lpn: '' })
      const next = (d.lines as Row[]).find((l) => l.line_no === line?.line_no)
      setLine(next && open(next) > 0 ? next : undefined)
    }
  }
  const scanItem = (raw: string) => {
    const d = parseGs1(raw)
    if (d?.lot && !f.lot) setF({ ...f, item: raw, lot: d.lot, qty: f.qty || (d.count === undefined ? '' : String(d.count)) })
  }
  const issuable = issue && ['APPROVED', 'PARTIALLY_ISSUED'].includes(String(issue.status))
  const returnable = issue && ['PARTIALLY_ISSUED', 'ISSUED', 'CLOSED'].includes(String(issue.status))

  return (
    <section className="page rf">
      <header className="page-head">
        <h1>Material issue · {site}</h1>
        <Link to="/rf">Tasks</Link>
      </header>
      <OfflineBar site={site} />
      <form className="card task" onSubmit={async (e) => { e.preventDefault(); await load.run(docInput) }}>
        <Field label="Scan issue document"><input autoFocus value={docInput} onChange={(e) => setDocInput(e.target.value)} required /></Field>
        <ErrorBox error={load.error} />
      </form>
      {issue && (
        <div className="card task">
          <div className="task-head">
            <span className="task-type">{String(issue.issue_no)}</span><Badge value={String(issue.status)} />
            <span className="muted">{String(issue.object_type)} {String(issue.object_code)} · {String(issue.recipient)}</span>
          </div>
          {!issuable && !returnable && <p className="alert">This request cannot be issued now ({String(issue.status)}).</p>}
          <div className="row">
            <label className="check"><input type="radio" checked={mode === 'issue'} disabled={!issuable} onChange={() => { setMode('issue'); setLine(undefined) }} /> Issue</label>
            <label className="check"><input type="radio" checked={mode === 'return'} disabled={!returnable} onChange={() => { setMode('return'); setLine(undefined) }} /> Return unused</label>
          </div>
          <table className="table">
            <thead><tr><th>Line</th><th>Item</th><th>Requested</th><th>Issued</th><th>Returned</th></tr></thead>
            <tbody>
              {(issue.lines as Row[]).map((l) => (
                <tr key={String(l.line_no)} onClick={() => open(l) > 0 && setLine(l)} className={line?.line_no === l.line_no ? 'selected' : ''}>
                  <td>{String(l.line_no)}</td><td>{String(l.item_no)}{l.lot_no ? ` · ${String(l.lot_no)}` : ''}</td>
                  <td>{fmtQty(l.qty_requested)} {String(l.uom)}</td><td>{fmtQty(l.qty_issued)}</td><td>{fmtQty(l.qty_returned)}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {!line && (issuable || returnable) && <p className="muted">Tap a line to {mode === 'issue' ? 'issue' : 'return'} it.</p>}
        </div>
      )}
      {issue && line && (
        <form className="card task" onSubmit={submit}>
          <dl className="big-facts">
            <dt>{mode === 'issue' ? 'Issue' : 'Return'}</dt>
            <dd className="target">{fmtQty(open(line))} {String(line.uom)} × {String(line.item_no)}</dd>
          </dl>
          <div className="row">
            <Field label={mode === 'issue' ? 'Scan bin' : 'Put back to bin'}><input autoFocus value={f.location} onChange={(e) => setF({ ...f, location: e.target.value })} required size={10} /></Field>
            <Field label="LPN" hint="If the stock is on one"><input value={f.lpn} onChange={(e) => setF({ ...f, lpn: e.target.value })} size={10} /></Field>
          </div>
          <Field label="Scan item" hint="Item no., GTIN or GS1 label">
            <input value={f.item} onChange={(e) => setF({ ...f, item: e.target.value })} onBlur={(e) => scanItem(e.target.value)} required />
          </Field>
          <div className="row">
            <Field label="Lot" hint="Lot-controlled items"><input value={f.lot} onChange={(e) => setF({ ...f, lot: e.target.value })} size={8} /></Field>
            <Field label={`Qty (${String(line.uom)})`}><input type="number" min={0} step="any" inputMode="decimal" value={f.qty} onChange={(e) => setF({ ...f, qty: e.target.value })} required size={5} /></Field>
          </div>
          <ErrorBox error={confirm.error} />
          <Success>{confirm.result && (isQueued(confirm.result) ? 'Saved on this device; it is sent when the network is back'
            : `${mode === 'issue' ? 'Issued' : 'Returned'}; posted to SAP`)}</Success>
          <div className="actions"><button className="primary big" disabled={confirm.busy}>Confirm {mode}</button></div>
        </form>
      )}
    </section>
  )
}
