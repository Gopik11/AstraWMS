import { useState } from 'react'
import { get, newIdempotencyKey, post, query, type Row } from '../api'
import { useAuth } from '../auth'
import { Badge, Card, ErrorBox, Field, Page, SearchBox, Success, Table, fmtDate, fmtQty, useAction, useLoad, useSite } from '../ui'

interface ReturnLine {
  erp_line_ref: string
  owner_id: string
  item_no: string
  qty_expected: number
  qty_received: number
  uom: string
  return_reason: string
}

interface ReturnUnit {
  id: string
  erp_line_ref?: string | null
  item_no: string
  qty: number
  uom: string
  serials: string[]
  condition_grade: string
  disposition: string
  stock_status: string
  wrong_item: boolean
  serial_flag: boolean
  over_rma: boolean
  location_id: string
  received_by: string
  received_at: string
}

interface ReturnDetail {
  rma_no: string
  return_type: string
  status: string
  customer_name?: string | null
  erp_document?: string | null
  erp_error_text?: string | null
  lines: ReturnLine[]
  units: ReturnUnit[]
}

const STATUSES = ['', 'EXPECTED', 'IN_PROGRESS', 'CLOSED', 'CONFIRMED', 'POSTING_FAILED', 'CANCELLED']
const GRADES = [
  ['A', 'A – as new'], ['B', 'B – opened, resaleable'], ['C', 'C – needs refurbishing'], ['D', 'D – defective'], ['E', 'E – destroyed'],
]
const DISPOSITIONS = ['', 'RESTOCK', 'QUARANTINE', 'REFURBISH', 'RTV', 'LIQUIDATE', 'SCRAP']

/** Customer returns (§8): RMAs from the ERP and blind returns; receive, grade and disposition units; close to post. */
export default function Returns() {
  const site = useSite()
  const { hasRole } = useAuth()
  const canReceive = hasRole('RECEIVER', 'SUPERVISOR')
  const [status, setStatus] = useState('')
  const [q, setQ] = useState('')
  const [selected, setSelected] = useState<string>()
  const list = useLoad(() => get<Row[]>(`/api/v1/sites/${site}/returns${query({ status, q })}`), [site, status, q])
  const [customer, setCustomer] = useState('')
  const blind = useAction(() => post<Row>(`/api/v1/sites/${site}/returns`, { customerName: customer || null }))

  return (
    <Page title="Customer returns" actions={
      <>
        <SearchBox value={q} onSearch={setQ} placeholder="RMA, customer, item, LPN" />
        <select value={status} onChange={(e) => setStatus(e.target.value)}>
          {STATUSES.map((s) => <option key={s} value={s}>{s || 'All statuses'}</option>)}
        </select>
      </>
    }>
      {canReceive && (
        <Card title="Return without RMA">
          <div className="row">
            <Field label="Customer (optional)"><input value={customer} onChange={(e) => setCustomer(e.target.value)} /></Field>
            <button className="primary" disabled={blind.busy} onClick={async () => {
              const r = await blind.run()
              if (r) { setCustomer(''); list.reload(); setSelected(String(r.rma_no)) }
            }}>Start blind return</button>
          </div>
          <ErrorBox error={blind.error} />
        </Card>
      )}
      <Card title="Returns">
        <ErrorBox error={list.error} />
        <Table rows={list.data} empty="No returns" onRow={(r) => setSelected(String(r.rma_no))} columns={[
          { header: 'RMA', cell: (r) => String(r.rma_no) },
          { header: 'Type', cell: (r) => String(r.return_type) },
          { header: 'Customer', cell: (r) => String(r.customer_name ?? '') },
          { header: 'Status', cell: (r) => <Badge value={String(r.status)} /> },
          { header: 'Lines', cell: (r) => String(r.lines), align: 'right' },
          { header: 'Units received', cell: (r) => fmtQty(r.units), align: 'right' },
          { header: 'Expected', cell: (r) => fmtDate(r.expected_arrival_utc) },
        ]} />
      </Card>
      {selected && <Detail key={selected} rma={selected} site={site} canReceive={canReceive}
                           canRepost={hasRole('SUPERVISOR')} onChange={list.reload} />}
    </Page>
  )
}

function Detail({ rma, site, canReceive, canRepost, onChange }:
  { rma: string; site: string; canReceive: boolean; canRepost: boolean; onChange: () => void }) {
  const base = `/api/v1/sites/${site}/returns/${encodeURIComponent(rma)}`
  const detail = useLoad(() => get<ReturnDetail>(base), [base])
  const close = useAction(() => post<Row>(`${base}/close`))
  const repost = useAction(() => post<Row>(`${base}/repost`))
  const done = async (r: unknown) => { if (r) { detail.reload(); onChange() } }
  const d = detail.data
  const open = d && ['EXPECTED', 'IN_PROGRESS'].includes(d.status)

  return (
    <Card title={`Return ${rma}`}>
      <ErrorBox error={detail.error} />
      {d && (
        <>
          <div className="facts">
            <span>Status <Badge value={d.status} /></span>
            <span>Type {d.return_type}</span>
            {d.customer_name && <span>Customer {d.customer_name}</span>}
            {d.erp_document && <span>SAP document {d.erp_document}</span>}
            {d.erp_error_text && <span className="error-text">ERP: {d.erp_error_text}</span>}
          </div>
          <h3>RMA lines</h3>
          <Table rows={d.lines} empty="Blind return – no RMA lines" columns={[
            { header: 'Line', cell: (l) => l.erp_line_ref },
            { header: 'Owner / item', cell: (l) => `${l.owner_id} / ${l.item_no}` },
            { header: 'Expected', cell: (l) => `${fmtQty(l.qty_expected)} ${l.uom}`, align: 'right' },
            { header: 'Received', cell: (l) => fmtQty(l.qty_received), align: 'right' },
            { header: 'Reason', cell: (l) => l.return_reason },
          ]} />
          <h3>Units received</h3>
          <Table rows={d.units} empty="Nothing received yet" columns={[
            { header: 'Item', cell: (u) => `${u.item_no}${u.erp_line_ref ? ` (line ${u.erp_line_ref})` : ''}` },
            { header: 'Qty', cell: (u) => `${fmtQty(u.qty)} ${u.uom}`, align: 'right' },
            { header: 'Grade', cell: (u) => u.condition_grade },
            { header: 'Disposition', cell: (u) => <Badge value={u.disposition} /> },
            { header: 'Stock status', cell: (u) => u.stock_status },
            { header: 'Location', cell: (u) => u.location_id },
            { header: 'Flags', cell: (u) => [u.wrong_item && 'wrong item', u.serial_flag && 'serial mismatch', u.over_rma && 'over RMA'].filter(Boolean).join(', ') },
            { header: 'By', cell: (u) => `${u.received_by} · ${fmtDate(u.received_at)}` },
          ]} />
          {canReceive && open && <ReceiveForm base={base} lines={d.lines} onDone={() => done(true)} />}
          <div className="actions">
            {canReceive && d.status === 'IN_PROGRESS' && (
              <button className="primary" disabled={close.busy} onClick={async () => done(await close.run())}>Close and post to ERP</button>
            )}
            {canRepost && d.status === 'POSTING_FAILED' && (
              <button disabled={repost.busy} onClick={async () => done(await repost.run())}>Repost</button>
            )}
          </div>
          <ErrorBox error={close.error ?? repost.error} />
        </>
      )}
    </Card>
  )
}

function ReceiveForm({ base, lines, onDone }: { base: string; lines: ReturnLine[]; onDone: () => void }) {
  const { hasRole } = useAuth()
  const blank = { line: lines[0]?.erp_line_ref ?? '', item: lines[0]?.item_no ?? '', owner: lines[0]?.owner_id ?? '', qty: '1', uom: lines[0]?.uom ?? 'EA',
    serials: '', grade: 'A', disposition: '', location: '', override: false }
  const [f, setF] = useState(blank)
  const [key, setKey] = useState(newIdempotencyKey('ret'))
  const receive = useAction(() => post<ReturnUnit>(`${base}/units`, {
    erpLineRef: f.line || null,
    ownerId: f.owner || null,
    itemNo: f.item,
    qty: Number(f.qty),
    uom: f.uom,
    serials: f.serials.split(/[\s,]+/).map((s) => s.trim()).filter(Boolean),
    conditionGrade: f.grade,
    disposition: f.disposition || null,
    locationId: f.location.toUpperCase(),
    override: f.override,
  }, { idempotencyKey: key }))
  const pickLine = (ref: string) => {
    const l = lines.find((x) => x.erp_line_ref === ref)
    setF({ ...f, line: ref, item: l?.item_no ?? f.item, owner: l?.owner_id ?? f.owner, uom: l?.uom ?? f.uom })
  }

  return (
    <>
      <h3>Receive a unit</h3>
      <div className="row">
        {lines.length > 0 && (
          <Field label="RMA line">
            <select value={f.line} onChange={(e) => pickLine(e.target.value)}>
              {lines.map((l) => <option key={l.erp_line_ref} value={l.erp_line_ref}>{l.erp_line_ref} · {l.item_no}</option>)}
              <option value="">Not on the RMA</option>
            </select>
          </Field>
        )}
        <Field label="Item"><input value={f.item} onChange={(e) => setF({ ...f, item: e.target.value.toUpperCase() })} size={10} /></Field>
        {!f.line && <Field label="Owner"><input value={f.owner} onChange={(e) => setF({ ...f, owner: e.target.value.toUpperCase() })} size={8} /></Field>}
        <Field label="Qty"><input type="number" min="0" value={f.qty} onChange={(e) => setF({ ...f, qty: e.target.value })} size={4} /></Field>
        <Field label="UoM"><input value={f.uom} onChange={(e) => setF({ ...f, uom: e.target.value.toUpperCase() })} size={4} /></Field>
      </div>
      <div className="row">
        <Field label="Condition">
          <select value={f.grade} onChange={(e) => setF({ ...f, grade: e.target.value })}>
            {GRADES.map(([g, label]) => <option key={g} value={g}>{label}</option>)}
          </select>
        </Field>
        <Field label="Disposition" hint="Blank = suggested">
          <select value={f.disposition} onChange={(e) => setF({ ...f, disposition: e.target.value })}>
            {DISPOSITIONS.map((d) => <option key={d} value={d}>{d || 'Suggested'}</option>)}
          </select>
        </Field>
        <Field label="Serials"><input value={f.serials} onChange={(e) => setF({ ...f, serials: e.target.value })} size={16} /></Field>
        <Field label="Returns location"><input value={f.location} onChange={(e) => setF({ ...f, location: e.target.value })} size={8} /></Field>
        {hasRole('SUPERVISOR') && (
          <label className="check"><input type="checkbox" checked={f.override} onChange={(e) => setF({ ...f, override: e.target.checked })} /> Allow over RMA</label>
        )}
      </div>
      <button className="primary" disabled={receive.busy || !f.item || !f.location || !(Number(f.qty) > 0)} onClick={async () => {
        if (await receive.run()) { setKey(newIdempotencyKey('ret')); setF({ ...f, serials: '', qty: '1' }); onDone() }
      }}>Receive</button>
      <ErrorBox error={receive.error} />
      <Success>{receive.result && `Received → ${receive.result.disposition} (${receive.result.stock_status})`}</Success>
    </>
  )
}
