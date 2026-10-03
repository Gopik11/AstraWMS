import { useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { get, post, put, query, type Row } from '../api'
import { useAuth } from '../auth'
import { Badge, Card, ErrorBox, Field, Page, SearchBox, Success, Table, fmtDate, fmtQty, useAction, useLoad, useParamSetter, useSite } from '../ui'

const TYPES = [['COST_CENTER', 'Cost centre'], ['WBS', 'WBS element'], ['ORDER', 'Internal order']]
const STATUSES = ['', 'REQUESTED', 'APPROVED,PARTIALLY_ISSUED', 'APPROVED', 'PARTIALLY_ISSUED', 'ISSUED', 'REJECTED', 'CANCELLED', 'CLOSED']
const STATUS_LABEL: Record<string, string> = { '': 'All statuses', 'APPROVED,PARTIALLY_ISSUED': 'To issue (approved or part issued)' }
const typeLabel = (t: unknown) => TYPES.find(([v]) => v === t)?.[1] ?? String(t)

/**
 * Controlled material issue (ADR-0022): requests to a cost centre, WBS element or order, approved by a supervisor or
 * inventory manager (not the requester), scanned out on RF; unused material is returned against the line. Each issue
 * and return is posted to SAP with the account assignment (201/221/261, reversals 202/222/262).
 */
export default function MaterialIssues() {
  const site = useSite()
  const { hasRole } = useAuth()
  const base = `/api/v1/sites/${site}/inventory`
  const [params, setParams] = useSearchParams()
  const setParam = useParamSetter(params, setParams)
  const status = params.get('status') ?? ''
  const q = params.get('q') ?? ''
  // Same query as the overview tiles: blank filters are left out (an empty status would match nothing).
  const list = useLoad(() => get<Row[]>(`${base}/material-issues${query({ status, q })}`), [base, status, q])
  const [selected, setSelected] = useState<string>()
  return (
    <Page title="Material issues" actions={<Link to="/rf/issue">Issue on RF</Link>}>
      <Card title="Requests" actions={
        <div className="row">
          <select value={status} onChange={(e) => setParam('status', e.target.value)}>
            {STATUSES.map((s) => <option key={s} value={s}>{STATUS_LABEL[s] ?? s}</option>)}
          </select>
          <SearchBox value={q} onSearch={(v) => setParam('q', v)} placeholder="Issue, cost object, recipient, item" />
        </div>
      }>
        <ErrorBox error={list.error} />
        <Table rows={list.data} empty="No material issues" onRow={(r) => setSelected(String(r.issue_no))} columns={[
          { header: 'Issue', cell: (r) => String(r.issue_no) },
          { header: 'To', cell: (r) => `${typeLabel(r.object_type)} ${String(r.object_code)}` },
          { header: 'Recipient', cell: (r) => String(r.recipient) },
          { header: 'Status', cell: (r) => <Badge value={String(r.status)} /> },
          { header: 'Lines', cell: (r) => String(r.lines), align: 'right' },
          { header: 'Requested', cell: (r) => `${fmtDate(r.requested_at)} · ${String(r.requested_by)}` },
          { header: 'Approved by', cell: (r) => String(r.decided_by ?? '') },
        ]} />
      </Card>
      {selected && <Detail key={selected} base={base} issueNo={selected} onChange={list.reload} />}
      {hasRole('RECEIVER', 'PICKER', 'INV_ANALYST', 'INV_MANAGER', 'SUPERVISOR') && <NewRequest base={base} onDone={(no) => { list.reload(); setSelected(no) }} />}
      <CostObjects base={base} canEdit={hasRole('SOLUTION_ADMIN', 'INV_MANAGER')} />
    </Page>
  )
}

function Detail({ base, issueNo, onChange }: { base: string; issueNo: string; onChange: () => void }) {
  const { hasRole, session } = useAuth()
  const url = `${base}/material-issues/${issueNo}`
  const d = useLoad(() => get<Row>(url), [url])
  const act = useAction((step: string, note?: string) => post<Row>(`${url}/${step}`, { note: note ?? null }))
  const run = async (step: string, note?: string) => { if (await act.run(step, note)) { d.reload(); onChange() } }
  const v = d.data
  const approver = hasRole('SUPERVISOR', 'INV_MANAGER')
  return (
    <Card title={`Issue ${issueNo}`}>
      <ErrorBox error={d.error ?? act.error} />
      {v && (
        <>
          <div className="facts">
            <span>Status <Badge value={String(v.status)} /></span>
            <span>{typeLabel(v.object_type)} {String(v.object_code)}{v.object_description ? ` · ${String(v.object_description)}` : ''}</span>
            {v.department != null && <span>Department {String(v.department)}</span>}
            <span>Recipient {String(v.recipient)}</span>
            <span>Owner {String(v.owner_id)}</span>
            <span>Requested by {String(v.requested_by)} {fmtDate(v.requested_at)}</span>
            {v.decided_by != null && <span>{v.status === 'REJECTED' ? 'Rejected' : 'Approved'} by {String(v.decided_by)}{v.decision_note ? `: ${String(v.decision_note)}` : ''}</span>}
          </div>
          {v.note != null && <p className="muted">{String(v.note)}</p>}
          <Table rows={v.lines as Row[]} columns={[
            { header: 'Line', cell: (l) => String(l.line_no) },
            { header: 'Item', cell: (l) => `${String(l.item_no)}${l.lot_no ? ` · lot ${String(l.lot_no)}` : ''}` },
            { header: 'Requested', cell: (l) => `${fmtQty(l.qty_requested)} ${String(l.uom)}`, align: 'right' },
            { header: 'Issued', cell: (l) => fmtQty(l.qty_issued), align: 'right' },
            { header: 'Returned', cell: (l) => fmtQty(l.qty_returned), align: 'right' },
          ]} />
          {(v.moves as Row[]).length > 0 && (
            <>
              <h3>Scans and SAP postings</h3>
              <Table rows={v.moves as Row[]} columns={[
                { header: 'When', cell: (m) => fmtDate(m.moved_at) },
                { header: 'Line', cell: (m) => String(m.line_no) },
                { header: 'What', cell: (m) => String(m.kind).toLowerCase() },
                { header: 'Qty', cell: (m) => fmtQty(m.qty), align: 'right' },
                { header: 'From / to', cell: (m) => `${String(m.location_id)}${m.lpn_id ? ` / ${String(m.lpn_id)}` : ''}${m.lot_no ? ` · lot ${String(m.lot_no)}` : ''}` },
                { header: 'By', cell: (m) => String(m.moved_by) },
                { header: 'WMS txn', cell: (m) => String(m.wms_txn_id ?? '') },
              ]} />
            </>
          )}
          <div className="actions">
            {approver && v.status === 'REQUESTED' && v.requested_by !== session.userName && <>
              <button className="primary" disabled={act.busy} onClick={() => void run('approve')}>Approve</button>
              <button disabled={act.busy} onClick={() => { const n = window.prompt('Why is it rejected?'); if (n) void run('reject', n) }}>Reject</button>
            </>}
            {approver && ['REQUESTED', 'APPROVED'].includes(String(v.status)) && (
              <button disabled={act.busy} onClick={() => void run('close')}>Cancel request</button>)}
            {approver && v.status === 'PARTIALLY_ISSUED' && (
              <button disabled={act.busy} onClick={() => void run('close')}>Close (rest not issued)</button>)}
          </div>
        </>
      )}
    </Card>
  )
}

function NewRequest({ base, onDone }: { base: string; onDone: (issueNo: string) => void }) {
  const objects = useLoad(() => get<Row[]>(`${base}/cost-objects`), [base])
  const [f, setF] = useState({ ownerId: '', objectType: 'COST_CENTER', objectCode: '', recipient: '', note: '' })
  const [lines, setLines] = useState([{ itemNo: '', qty: '', uom: 'EA', lotNo: '' }])
  const create = useAction(() => post<Row>(`${base}/material-issues`, {
    ...f, note: f.note || null,
    lines: lines.filter((l) => l.itemNo && l.qty).map((l) => ({ itemNo: l.itemNo, qty: Number(l.qty), uom: l.uom, lotNo: l.lotNo || null })),
  }))
  const setLine = (i: number, k: string, v: string) => setLines(lines.map((l, n) => (n === i ? { ...l, [k]: v } : l)))
  const options = (objects.data ?? []).filter((o) => o.object_type === f.objectType && o.active)
  return (
    <Card title="New issue request">
      <div className="row">
        <Field label="Owner"><input value={f.ownerId} onChange={(e) => setF({ ...f, ownerId: e.target.value.toUpperCase() })} size={8} required /></Field>
        <Field label="Charge to">
          <select value={f.objectType} onChange={(e) => setF({ ...f, objectType: e.target.value, objectCode: '' })}>
            {TYPES.map(([v, l]) => <option key={v} value={v}>{l}</option>)}
          </select>
        </Field>
        <Field label="Code">
          <select value={f.objectCode} onChange={(e) => setF({ ...f, objectCode: e.target.value })}>
            <option value="">Choose…</option>
            {options.map((o) => <option key={String(o.code)} value={String(o.code)}>{String(o.code)} · {String(o.description)}</option>)}
          </select>
        </Field>
        <Field label="Recipient" hint="Person or department"><input value={f.recipient} onChange={(e) => setF({ ...f, recipient: e.target.value })} size={16} /></Field>
        <Field label="Note"><input value={f.note} onChange={(e) => setF({ ...f, note: e.target.value })} size={20} /></Field>
      </div>
      {lines.map((l, i) => (
        <div className="row" key={i}>
          <Field label={`Item ${i + 1}`}><input value={l.itemNo} onChange={(e) => setLine(i, 'itemNo', e.target.value.toUpperCase())} size={10} /></Field>
          <Field label="Qty"><input type="number" min={0} step="any" value={l.qty} onChange={(e) => setLine(i, 'qty', e.target.value)} size={5} /></Field>
          <Field label="UoM"><input value={l.uom} onChange={(e) => setLine(i, 'uom', e.target.value.toUpperCase())} size={3} /></Field>
          <Field label="Lot" hint="Optional"><input value={l.lotNo} onChange={(e) => setLine(i, 'lotNo', e.target.value)} size={8} /></Field>
        </div>
      ))}
      <div className="actions">
        <button onClick={() => setLines([...lines, { itemNo: '', qty: '', uom: 'EA', lotNo: '' }])}>Add line</button>
        <button className="primary" disabled={create.busy || !f.objectCode || !f.recipient || !f.ownerId}
                onClick={async () => { const r = await create.run(); if (r) { onDone(String(r.issue_no)); setLines([{ itemNo: '', qty: '', uom: 'EA', lotNo: '' }]) } }}>
          Request</button>
      </div>
      <ErrorBox error={objects.error ?? create.error} />
      <Success>{create.result && `Request ${String(create.result.issue_no)} waits for approval`}</Success>
    </Card>
  )
}

function CostObjects({ base, canEdit }: { base: string; canEdit: boolean }) {
  const list = useLoad(() => get<Row[]>(`${base}/cost-objects`), [base])
  const [f, setF] = useState({ type: 'COST_CENTER', code: '', description: '', department: '', requesters: '', active: true })
  const save = useAction(() => put(`${base}/cost-objects/${f.type}/${encodeURIComponent(f.code)}`, {
    description: f.description, department: f.department || null, active: f.active,
    allowedRequesters: f.requesters.split(',').map((s) => s.trim()).filter(Boolean),
  }))
  return (
    <Card title="Cost objects">
      <Table rows={list.data} empty="No cost objects yet" onRow={(o) => canEdit && setF({
        type: String(o.object_type), code: String(o.code), description: String(o.description),
        department: String(o.department ?? ''), requesters: String(o.allowed_requesters ?? ''), active: Boolean(o.active) })} columns={[
        { header: 'Type', cell: (o) => typeLabel(o.object_type) },
        { header: 'Code', cell: (o) => String(o.code) },
        { header: 'Description', cell: (o) => String(o.description) },
        { header: 'Department', cell: (o) => String(o.department ?? '') },
        { header: 'Who may request', cell: (o) => String(o.allowed_requesters || 'anyone') },
        { header: 'Active', cell: (o) => (o.active ? 'yes' : 'closed') },
      ]} />
      {canEdit && (
        <div className="row">
          <Field label="Type"><select value={f.type} onChange={(e) => setF({ ...f, type: e.target.value })}>
            {TYPES.map(([v, l]) => <option key={v} value={v}>{l}</option>)}</select></Field>
          <Field label="Code"><input value={f.code} onChange={(e) => setF({ ...f, code: e.target.value.toUpperCase() })} size={10} /></Field>
          <Field label="Description"><input value={f.description} onChange={(e) => setF({ ...f, description: e.target.value })} size={18} /></Field>
          <Field label="Department"><input value={f.department} onChange={(e) => setF({ ...f, department: e.target.value })} size={10} /></Field>
          <Field label="Who may request" hint="User IDs, comma-separated; blank = anyone"><input value={f.requesters} onChange={(e) => setF({ ...f, requesters: e.target.value })} size={14} /></Field>
          <label className="check"><input type="checkbox" checked={f.active} onChange={(e) => setF({ ...f, active: e.target.checked })} /> Open for postings</label>
          <button className="primary" disabled={save.busy || !f.code || !f.description} onClick={async () => { if (await save.run()) list.reload() }}>Save</button>
        </div>
      )}
      <ErrorBox error={list.error ?? save.error} />
    </Card>
  )
}
