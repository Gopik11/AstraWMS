import { useState, type FormEvent } from 'react'
import { api, get, post, type Row, type Task } from '../api'
import { Badge, ErrorBox, Field, Success, fmtQty, useAction, useLoad, useSite } from '../ui'

/**
 * RF work (scope §G.3): the operator asks for the next task of their role and confirms it by scanning. Receive:
 * document, item, quantity (+ lot/expiry/serials), LPN and dock check digit; putaway: LPN + location check digit;
 * pick: source check digit + quantity (+ serials); return: check digit of the return location.
 * Designed for handhelds: one task at a time, large inputs, scanner-friendly (Enter submits).
 */
export default function RfWork() {
  const site = useSite()
  const [task, setTask] = useState<Task | null>()
  const [done, setDone] = useState<string>()
  const next = useAction(async () => {
    const t = await api<Task | undefined>('POST', `/api/v1/sites/${site}/tasks/next`)
    setTask(t ?? null)
    return t
  })

  const finished = (message: string) => {
    setDone(message)
    setTask(undefined)
    void next.run()
  }

  return (
    <section className="page rf">
      <header className="page-head">
        <h1>RF work · {site}</h1>
        <button className="primary big" onClick={() => (setDone(undefined), void next.run())} disabled={next.busy}>
          {task ? 'Refresh' : 'Next task'}
        </button>
      </header>
      <Success>{done}</Success>
      <ErrorBox error={next.error} />
      {task === null && <div className="card muted">No work for you right now.</div>}
      {task && task.taskType === 'RECEIVE' && <Receive task={task} site={site} onDone={finished} />}
      {task && task.taskType === 'PUTAWAY' && <Putaway task={task} site={site} onDone={finished} />}
      {task && task.taskType === 'PICK' && <Pick task={task} site={site} onDone={finished} />}
      {task && task.taskType === 'RETURN' && <Return task={task} site={site} onDone={finished} />}
      {task && task.taskType === 'COUNT' && <Count task={task} site={site} onDone={finished} />}
      {task && task.taskType === 'REPLEN' && <Replen task={task} site={site} onDone={finished} />}
    </section>
  )
}

function TaskHead({ task }: { task: Task }) {
  return (
    <div className="task-head">
      <span className="task-type">{task.taskType}</span>
      <Badge value={task.status} />
      {task.orderRef && task.taskType !== 'RECEIVE' && <span className="muted">Order {task.orderRef} / {task.orderLineRef}</span>}
    </div>
  )
}

function Putaway({ task, site, onDone }: { task: Task; site: string; onDone: (m: string) => void }) {
  const [lpn, setLpn] = useState('')
  const [location, setLocation] = useState(task.targetLocation)
  const [checkDigit, setCheckDigit] = useState('')
  const [reason, setReason] = useState('LOCATION_BLOCKED')
  const confirm = useAction(() => post(`/api/v1/sites/${site}/tasks/${task.id}/confirm`,
    { lpnId: lpn.trim(), locationId: location.trim(), checkDigit: checkDigit.trim() }))
  const exception = useAction(() => post(`/api/v1/sites/${site}/tasks/${task.id}/exception`, { reason }))

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    if (await confirm.run()) {
      onDone(`Put away ${task.lpnId} to ${location}`)
    }
  }
  return (
    <form className="card task" onSubmit={submit}>
      <TaskHead task={task} />
      <dl className="big-facts">
        <dt>LPN</dt><dd>{task.lpnId}</dd>
        <dt>From</dt><dd>{task.fromLocation}</dd>
        <dt>To</dt><dd className="target">{task.targetLocation}</dd>
        {task.strategy && <><dt>Why</dt><dd className="muted">{task.strategy.replace('_', ' ').toLowerCase()}</dd></>}
      </dl>
      {task.contents && task.contents.length > 0 && (
        <p className="muted">{task.contents.map((c) => `${fmtQty(c.qty)} × ${c.itemNo}${c.lotNo ? ` (${c.lotNo})` : ''}`).join(', ')}</p>
      )}
      <Field label="Scan LPN"><input autoFocus value={lpn} onChange={(e) => setLpn(e.target.value)} required /></Field>
      <Field label="Location" hint="Change only to override the suggested location">
        <input value={location} onChange={(e) => setLocation(e.target.value)} required />
      </Field>
      <Field label="Location check digit"><input inputMode="numeric" value={checkDigit} onChange={(e) => setCheckDigit(e.target.value)} required /></Field>
      <ErrorBox error={confirm.error ?? exception.error} />
      <div className="actions">
        <button className="primary big" disabled={confirm.busy}>Confirm putaway</button>
      </div>
      <details>
        <summary>Report a problem</summary>
        <div className="row">
          <select value={reason} onChange={(e) => setReason(e.target.value)}>
            <option value="LOCATION_BLOCKED">Location blocked</option>
            <option value="LOCATION_OCCUPIED">Location occupied</option>
            <option value="LPN_NOT_FOUND">LPN not found</option>
          </select>
          <button type="button" onClick={async () => (await exception.run()) && onDone(`Reported ${reason}; the task was re-planned`)}>
            Report
          </button>
        </div>
      </details>
    </form>
  )
}

const SHORT_REASONS = ['SHORT_VENDOR', 'DAMAGED', 'REFUSED', 'IN_TRANSIT']
const GRADES = ['A', 'B', 'C', 'D', 'E']

interface Progress { erpLineRef: string; itemNo: string; qtyExpected: number; qtyReceived: number; uom: string }

/**
 * RF receiving (ADR-0019) of a vendor delivery (ASN) or a customer return (RMA): one scan per pallet or unit. The
 * goods go down at a dock / returns location; putaway tasks follow from there. Finish closes the document, which sends
 * the confirmation to the ERP; lines received short need a reason.
 */
function Receive({ task, site, onDone }: { task: Task; site: string; onDone: (m: string) => void }) {
  const rma = task.receiveKind === 'RMA'
  const expected = task.expectedLines ?? []
  const progressUrl = rma ? `/api/v1/sites/${site}/returns/${task.docNo}` : `/api/v1/sites/${site}/receipts/${task.docNo}`
  const progress = useLoad(() => get<Row>(progressUrl), [progressUrl])
  const lines: Progress[] = rma
    ? ((progress.data?.lines as Row[] | undefined) ?? []).map((l) => ({
        erpLineRef: String(l.erp_line_ref), itemNo: String(l.item_no), qtyExpected: Number(l.qty_expected),
        qtyReceived: Number(l.qty_received), uom: String(l.uom) }))
    : ((progress.data?.lines as Progress[] | undefined) ?? [])
  const blank = { doc: '', item: '', owner: '', qty: '', uom: expected[0]?.uom ?? 'EA', lot: '', expiry: '', serials: '',
    lpn: '', location: '', checkDigit: '', grade: 'A', disposition: '', reason: '' }
  const [f, setF] = useState(blank)
  const [scanId, setScanId] = useState(() => crypto.randomUUID())
  const [reasons, setReasons] = useState<Record<string, string>>({})
  const set = (k: keyof typeof blank) => (e: { target: { value: string } }) => setF({ ...f, [k]: e.target.value })
  const pickItem = (item: string) => {
    const l = expected.find((x) => x.itemNo === item)
    setF({ ...f, item, uom: l?.uom ?? f.uom })
  }
  const scan = useAction(() => post<{ result: Row }>(`/api/v1/sites/${site}/tasks/${task.id}/receive`, {
    scanId, docNo: f.doc.trim(), itemNo: f.item.trim().toUpperCase(), ownerId: f.owner.trim() || null, qty: Number(f.qty),
    uom: f.uom.trim().toUpperCase(), lotNo: f.lot.trim() || null, expiryDate: f.expiry || null,
    serials: f.serials.split(/[\s,]+/).map((s) => s.trim()).filter(Boolean), lpnId: f.lpn.trim() || null,
    locationId: f.location.trim().toUpperCase(), checkDigit: f.checkDigit.trim(),
    conditionGrade: rma ? f.grade : null, disposition: rma && f.disposition ? f.disposition : null,
    returnReason: rma && f.reason ? f.reason : null,
  }))
  const short = lines.filter((l) => l.qtyReceived < l.qtyExpected)
  const finish = useAction(() => post(`/api/v1/sites/${site}/tasks/${task.id}/receive/close`,
    { shortReasons: rma ? {} : reasons }))
  const handBack = useAction(() => post(`/api/v1/sites/${site}/tasks/${task.id}/release`))
  const [scansDone, setScansDone] = useState(task.scans ?? 0)

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    if (await scan.run()) {
      setScanId(crypto.randomUUID())                       // the next scan is a new one; a retry keeps the same ID
      setScansDone(scansDone + 1)
      setF({ ...f, item: '', qty: '', lot: '', expiry: '', serials: '', lpn: '' })
      progress.reload()
    }
  }
  const unit = scan.result?.result
  const shown = lines.length ? lines
    : expected.map((l) => ({ erpLineRef: l.lineRef, itemNo: l.itemNo, qtyExpected: l.qty, qtyReceived: 0, uom: l.uom }))
  return (
    <form className="card task" onSubmit={submit}>
      <TaskHead task={task} />
      <dl className="big-facts">
        <dt>{rma ? 'Return' : 'Delivery'}</dt><dd className="target">{task.docNo}</dd>
        {task.partner && <><dt>{rma ? 'Customer' : 'Vendor'}</dt><dd>{task.partner}</dd></>}
      </dl>
      <table className="table">
        <thead><tr><th>Line</th><th>Item</th><th>Expected</th><th>Received</th></tr></thead>
        <tbody>
          {shown.map((l) => (
            <tr key={l.erpLineRef} onClick={() => pickItem(l.itemNo)}>
              <td>{l.erpLineRef}</td><td>{l.itemNo}</td>
              <td>{fmtQty(l.qtyExpected)} {l.uom}</td>
              <td>{fmtQty(l.qtyReceived)}{l.qtyReceived >= l.qtyExpected ? ' ✓' : ''}</td>
            </tr>
          ))}
          {rma && shown.length === 0 && <tr><td colSpan={4} className="muted">Return without RMA: scan what arrived</td></tr>}
        </tbody>
      </table>
      <Field label={rma ? 'Scan return / RMA no.' : 'Scan delivery no.'}><input autoFocus value={f.doc} onChange={set('doc')} required /></Field>
      <div className="row">
        <Field label="Item"><input value={f.item} onChange={(e) => pickItem(e.target.value.toUpperCase())} required size={10} /></Field>
        <Field label="Qty"><input type="number" min={0} step="any" inputMode="decimal" value={f.qty} onChange={set('qty')} required size={4} /></Field>
        <Field label="UoM"><input value={f.uom} onChange={set('uom')} required size={3} /></Field>
      </div>
      {rma && expected.length === 0 && <Field label="Owner"><input value={f.owner} onChange={set('owner')} size={6} required /></Field>}
      <div className="row">
        <Field label="Lot" hint="If the item is lot-controlled"><input value={f.lot} onChange={set('lot')} size={8} /></Field>
        {!rma && <Field label="Expiry"><input type="date" value={f.expiry} onChange={set('expiry')} /></Field>}
      </div>
      <Field label="Serial numbers" hint="Serial-tracked items only; one per unit"><textarea rows={2} value={f.serials} onChange={set('serials')} /></Field>
      {rma && (
        <div className="row">
          <Field label="Condition">
            <select value={f.grade} onChange={set('grade')}>{GRADES.map((g) => <option key={g}>{g}</option>)}</select>
          </Field>
          <Field label="Disposition" hint="Blank = suggested">
            <select value={f.disposition} onChange={set('disposition')}>
              {['', 'RESTOCK', 'QUARANTINE', 'REFURBISH', 'RTV', 'LIQUIDATE', 'SCRAP'].map((d) => <option key={d} value={d}>{d || 'Suggested'}</option>)}
            </select>
          </Field>
        </div>
      )}
      <Field label="LPN" hint={rma ? 'Blank: one LPN per unit is created' : 'Pallet label; blank for loose stock'}><input value={f.lpn} onChange={set('lpn')} /></Field>
      <div className="row">
        <Field label={rma ? 'Returns / dock location' : 'Dock location'}><input value={f.location} onChange={set('location')} required size={8} /></Field>
        <Field label="Check digit"><input inputMode="numeric" value={f.checkDigit} onChange={set('checkDigit')} required size={3} /></Field>
      </div>
      <ErrorBox error={scan.error} />
      <Success>{unit && (rma
        ? `Received ${String(unit.item_no)} → ${String(unit.disposition)} (${String(unit.stock_status)}) on ${String(unit.lpn_id ?? '')}`
        : `Received; ${scansDone} scan(s) on this task`)}</Success>
      <div className="actions"><button className="primary big" disabled={scan.busy}>Confirm receipt</button></div>
      <details>
        <summary>Finish {rma ? 'return' : 'delivery'}</summary>
        {!rma && short.length > 0 && (
          <>
            <p className="muted">Lines received short need a reason:</p>
            {short.map((l) => (
              <Field key={l.erpLineRef} label={`${l.erpLineRef} · ${l.itemNo} (${fmtQty(l.qtyReceived)} of ${fmtQty(l.qtyExpected)})`}>
                <select value={reasons[l.erpLineRef] ?? ''} onChange={(e) => setReasons({ ...reasons, [l.erpLineRef]: e.target.value })}>
                  <option value="">Choose…</option>
                  {SHORT_REASONS.map((r) => <option key={r}>{r}</option>)}
                </select>
              </Field>
            ))}
          </>
        )}
        <div className="row">
          <button type="button" className="primary" disabled={finish.busy}
                  onClick={async () => { if (await finish.run()) onDone(`${task.docNo} closed and confirmed to the ERP`) }}>
            Close and confirm to ERP
          </button>
          <button type="button" disabled={handBack.busy}
                  onClick={async () => { if (await handBack.run()) onDone(`${task.docNo} handed back to the queue`) }}>
            Stop for now
          </button>
        </div>
        <ErrorBox error={finish.error ?? handBack.error} />
      </details>
    </form>
  )
}

function Pick({ task, site, onDone }: { task: Task; site: string; onDone: (m: string) => void }) {
  const [checkDigit, setCheckDigit] = useState('')
  const [qty, setQty] = useState(String(task.qty ?? ''))
  const [serials, setSerials] = useState('')
  const confirm = useAction(() => post(`/api/v1/sites/${site}/tasks/${task.id}/pick`, {
    checkDigit: checkDigit.trim(),
    qty: Number(qty),
    serials: serials.split(/[\s,]+/).map((s) => s.trim()).filter(Boolean),
  }))
  const short = qty !== '' && Number(qty) < Number(task.qty)

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    if (await confirm.run()) {
      onDone(`Picked ${qty} × ${task.itemNo} for ${task.orderRef}${short ? ' (short pick)' : ''}`)
    }
  }
  return (
    <form className="card task" onSubmit={submit}>
      <TaskHead task={task} />
      <dl className="big-facts">
        <dt>From</dt><dd className="target">{task.fromLocation}</dd>
        <dt>Item</dt><dd>{task.itemNo}{task.lotNo ? ` · lot ${task.lotNo}` : ''}</dd>
        <dt>Quantity</dt><dd>{fmtQty(task.qty)} {task.uom}</dd>
        <dt>To</dt><dd>{task.targetLocation} · {task.toLpn}</dd>
      </dl>
      <Field label="Source location check digit">
        <input autoFocus inputMode="numeric" value={checkDigit} onChange={(e) => setCheckDigit(e.target.value)} required />
      </Field>
      <Field label="Quantity picked" hint={short ? 'Less than requested: this is a short pick; the rest is re-allocated' : undefined}>
        <input type="number" min={0} max={task.qty ?? undefined} step="any" value={qty} onChange={(e) => setQty(e.target.value)} required />
      </Field>
      <Field label="Serial numbers" hint="Serial-tracked items only; scan one per unit">
        <textarea rows={2} value={serials} onChange={(e) => setSerials(e.target.value)} />
      </Field>
      <ErrorBox error={confirm.error} />
      <div className="actions"><button className="primary big" disabled={confirm.busy}>{short ? 'Confirm short pick' : 'Confirm pick'}</button></div>
    </form>
  )
}

function Return({ task, site, onDone }: { task: Task; site: string; onDone: (m: string) => void }) {
  const [checkDigit, setCheckDigit] = useState('')
  const confirm = useAction(() => post(`/api/v1/sites/${site}/tasks/${task.id}/return`, { checkDigit: checkDigit.trim() }))
  const submit = async (e: FormEvent) => {
    e.preventDefault()
    if (await confirm.run()) {
      onDone(`Returned ${task.qty} × ${task.itemNo} to ${task.targetLocation}`)
    }
  }
  return (
    <form className="card task" onSubmit={submit}>
      <TaskHead task={task} />
      <p>Order {task.orderRef} was cancelled: bring the picked stock back.</p>
      <dl className="big-facts">
        <dt>Take from</dt><dd>{task.fromLocation} · {task.lpnId}</dd>
        <dt>Item</dt><dd>{fmtQty(task.qty)} × {task.itemNo}</dd>
        <dt>Put back to</dt><dd className="target">{task.targetLocation}</dd>
      </dl>
      <Field label="Return location check digit">
        <input autoFocus inputMode="numeric" value={checkDigit} onChange={(e) => setCheckDigit(e.target.value)} required />
      </Field>
      <ErrorBox error={confirm.error} />
      <div className="actions"><button className="primary big" disabled={confirm.busy}>Confirm return</button></div>
    </form>
  )
}

interface CountedLine { ownerId: string; itemNo: string; lotNo: string; lpnId: string; qty: string }

/** Blind cycle count (INV-003): the counter records what is in the location without seeing the system quantity. */
function Count({ task, site, onDone }: { task: Task; site: string; onDone: (m: string) => void }) {
  const [checkDigit, setCheckDigit] = useState('')
  const [lines, setLines] = useState<CountedLine[]>([{ ownerId: '', itemNo: '', lotNo: '', lpnId: '', qty: '' }])
  const filled = lines.filter((l) => l.itemNo.trim() && l.qty !== '')
  const confirm = useAction(() => post(`/api/v1/sites/${site}/tasks/${task.id}/count`, {
    checkDigit: checkDigit.trim(),
    lines: filled.map((l) => ({ ownerId: l.ownerId.trim(), itemNo: l.itemNo.trim(), lotNo: l.lotNo.trim() || null,
      lpnId: l.lpnId.trim() || null, qty: Number(l.qty) })),
  }))
  const set = (i: number, k: keyof CountedLine, v: string) => setLines(lines.map((l, j) => (j === i ? { ...l, [k]: v } : l)))
  const submit = async (e: FormEvent) => {
    e.preventDefault()
    if (await confirm.run()) {
      onDone(`Counted ${task.fromLocation}: ${filled.length === 0 ? 'empty' : `${filled.length} line(s)`}`)
    }
  }
  return (
    <form className="card task" onSubmit={submit}>
      <TaskHead task={task} />
      <p>{(task.countSequence ?? 1) > 1 ? `Recount ${task.countSequence}: count independently.` : 'Count everything in this location.'}</p>
      <dl className="big-facts"><dt>Location</dt><dd className="target">{task.fromLocation}</dd></dl>
      <Field label="Location check digit"><input autoFocus inputMode="numeric" value={checkDigit} onChange={(e) => setCheckDigit(e.target.value)} required /></Field>
      {lines.map((l, i) => (
        <div key={i} className="count-line">
          <div className="row">
            <Field label="Owner"><input value={l.ownerId} onChange={(e) => set(i, 'ownerId', e.target.value.toUpperCase())} size={6} required={!!l.itemNo} /></Field>
            <Field label="Item"><input value={l.itemNo} onChange={(e) => set(i, 'itemNo', e.target.value.toUpperCase())} size={10} /></Field>
            <Field label="Qty"><input type="number" min={0} step="any" value={l.qty} onChange={(e) => set(i, 'qty', e.target.value)} size={5} /></Field>
          </div>
          <div className="row">
            <Field label="Lot"><input value={l.lotNo} onChange={(e) => set(i, 'lotNo', e.target.value)} size={8} /></Field>
            <Field label="LPN"><input value={l.lpnId} onChange={(e) => set(i, 'lpnId', e.target.value)} size={12} /></Field>
          </div>
        </div>
      ))}
      <button type="button" onClick={() => setLines([...lines, { ownerId: lines[lines.length - 1]?.ownerId ?? '', itemNo: '', lotNo: '', lpnId: '', qty: '' }])}>
        Add item
      </button>
      <ErrorBox error={confirm.error} />
      <div className="actions">
        <button className="primary big" disabled={confirm.busy}>{filled.length === 0 ? 'Location is empty' : 'Submit count'}</button>
      </div>
    </form>
  )
}

/** Replenishment (§7): take the reserved stock from reserve and drop it at the forward pick location. */
function Replen({ task, site, onDone }: { task: Task; site: string; onDone: (m: string) => void }) {
  const [checkDigit, setCheckDigit] = useState('')
  const confirm = useAction(() => post(`/api/v1/sites/${site}/tasks/${task.id}/replenish`, { checkDigit: checkDigit.trim() }))
  const submit = async (e: FormEvent) => {
    e.preventDefault()
    if (await confirm.run()) {
      onDone(`Replenished ${task.targetLocation} with ${fmtQty(task.qty)} × ${task.itemNo}`)
    }
  }
  return (
    <form className="card task" onSubmit={submit}>
      <TaskHead task={task} />
      <dl className="big-facts">
        <dt>Take from</dt><dd>{task.fromLocation}{task.lpnId ? ` · ${task.lpnId}` : ''}</dd>
        <dt>Item</dt><dd>{fmtQty(task.qty)} {task.uom} × {task.itemNo}{task.lotNo ? ` · lot ${task.lotNo}` : ''}</dd>
        <dt>Drop at</dt><dd className="target">{task.targetLocation}</dd>
      </dl>
      <Field label="Forward location check digit">
        <input autoFocus inputMode="numeric" value={checkDigit} onChange={(e) => setCheckDigit(e.target.value)} required />
      </Field>
      <ErrorBox error={confirm.error} />
      <div className="actions"><button className="primary big" disabled={confirm.busy}>Confirm replenishment</button></div>
    </form>
  )
}
