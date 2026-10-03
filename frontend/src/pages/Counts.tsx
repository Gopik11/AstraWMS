import { useState } from 'react'
import { get, post, query, type Row } from '../api'
import { useAuth } from '../auth'
import { Badge, Card, ErrorBox, Field, Page, Success, Table, fmtDate, fmtQty, useAction, useLoad, useSite } from '../ui'

interface CountView {
  id: string
  locationId: string
  trigger: string
  status: string
  countsDone: number
  requestedBy: string
  note?: string
  varianceValue?: number | null
  decidedBy?: string | null
  results: { sequence: number; countedBy: string; countedAt: string; lines: { ownerId: string; itemNo: string; lotNo: string; lpnId: string; qty: number }[] }[]
  variances: { ownerId: string; itemNo: string; lotNo: string; lpnId: string; systemQty: number; countedQty: number; variance: number; unitCost?: number | null }[]
}

const STATUSES = ['', 'OPEN', 'RECOUNT', 'PENDING_APPROVAL', 'ADJUSTED', 'CLOSED', 'REJECTED']

/** Cycle counts (§6.3): request ad-hoc counts, follow them, approve or reject variances. */
export default function Counts() {
  const site = useSite()
  const { hasRole } = useAuth()
  const [status, setStatus] = useState('PENDING_APPROVAL')
  const [selected, setSelected] = useState<string>()
  const list = useLoad(() => get<Row[]>(`/api/v1/sites/${site}/inventory/counts${query({ status })}`), [site, status])
  const [locations, setLocations] = useState('')
  const create = useAction(() => post<{ countIds: string[] }>(`/api/v1/sites/${site}/inventory/counts`, {
    locationIds: locations.split(/[\s,]+/).map((l) => l.trim().toUpperCase()).filter(Boolean),
  }))

  return (
    <Page title="Cycle counts" actions={
      <select value={status} onChange={(e) => setStatus(e.target.value)}>
        {STATUSES.map((s) => <option key={s} value={s}>{s || 'All statuses'}</option>)}
      </select>
    }>
      <Card title="Request a count">
        <div className="row">
          <Field label="Locations" hint="Comma or space separated"><input value={locations} onChange={(e) => setLocations(e.target.value)} size={30} /></Field>
          <button className="primary" disabled={create.busy || !locations.trim()}
                  onClick={async () => { if (await create.run()) { setLocations(''); list.reload() } }}>Create count tasks</button>
        </div>
        <ErrorBox error={create.error} />
        <Success>{create.result && `${create.result.countIds.length} count(s) requested; they appear as RF count tasks`}</Success>
      </Card>
      <Card title="Counts">
        <ErrorBox error={list.error} />
        <Table rows={list.data} empty="No counts" onRow={(r) => setSelected(String(r.id))} columns={[
          { header: 'Location', cell: (r) => String(r.location_id) },
          { header: 'Trigger', cell: (r) => String(r.trigger) },
          { header: 'Status', cell: (r) => <Badge value={String(r.status)} /> },
          { header: 'Counts', cell: (r) => String(r.counts_done), align: 'right' },
          { header: 'Variance value', cell: (r) => fmtQty(r.variance_value), align: 'right' },
          { header: 'Requested', cell: (r) => `${fmtDate(r.created_at)} · ${String(r.requested_by)}` },
          { header: 'Decided by', cell: (r) => String(r.decided_by ?? '') },
        ]} />
      </Card>
      {selected && <Detail id={selected} site={site} canDecide={hasRole('INV_MANAGER', 'SUPERVISOR')} onChange={list.reload} />}
      <PhysicalInventory site={site} canRun={hasRole('INV_MANAGER', 'SUPERVISOR')} onChange={list.reload} />
    </Page>
  )
}

function Detail({ id, site, canDecide, onChange }: { id: string; site: string; canDecide: boolean; onChange: () => void }) {
  const base = `/api/v1/sites/${site}/inventory/counts/${id}`
  const detail = useLoad(() => get<CountView>(base), [base])
  const approve = useAction(() => post<CountView>(`${base}/approve`))
  const [note, setNote] = useState('')
  const reject = useAction(() => post<CountView>(`${base}/reject`, { note: note || null }))
  const done = async (r: unknown) => { if (r) { detail.reload(); onChange() } }
  const c = detail.data
  return (
    <Card title={c ? `Count of ${c.locationId}` : 'Count'}>
      <ErrorBox error={detail.error} />
      {c && (
        <>
          <div className="facts">
            <span>Status <Badge value={c.status} /></span>
            <span>Trigger {c.trigger}</span>
            <span>Counts {c.countsDone}</span>
            {c.varianceValue != null && <span>Variance value {fmtQty(c.varianceValue)}</span>}
            {c.decidedBy && <span>Decided by {c.decidedBy}</span>}
            {c.note && <span>Note: {c.note}</span>}
          </div>
          <h3>Variances</h3>
          <Table rows={c.variances} empty="No variance" columns={[
            { header: 'Owner / item', cell: (v) => `${v.ownerId} / ${v.itemNo}${v.lotNo ? ` · ${v.lotNo}` : ''}` },
            { header: 'LPN', cell: (v) => v.lpnId },
            { header: 'System', cell: (v) => fmtQty(v.systemQty), align: 'right' },
            { header: 'Counted', cell: (v) => fmtQty(v.countedQty), align: 'right' },
            { header: 'Variance', cell: (v) => fmtQty(v.variance), align: 'right' },
            { header: 'Value', cell: (v) => (v.unitCost == null ? 'no cost' : fmtQty(Math.abs(v.variance) * v.unitCost)), align: 'right' },
          ]} />
          <h3>Counts</h3>
          <Table rows={c.results} empty="Not counted yet" columns={[
            { header: '#', cell: (r) => r.sequence },
            { header: 'Counted by', cell: (r) => r.countedBy },
            { header: 'When', cell: (r) => fmtDate(r.countedAt) },
            { header: 'Found', cell: (r) => (r.lines.length === 0 ? 'empty' : r.lines.map((l) => `${fmtQty(l.qty)} × ${l.itemNo}${l.lpnId ? ` (${l.lpnId})` : ''}`).join(', ')) },
          ]} />
          {canDecide && c.status === 'PENDING_APPROVAL' && (
            <div className="actions">
              <button className="primary" disabled={approve.busy} onClick={async () => done(await approve.run())}>Approve and adjust</button>
              <input placeholder="Reason for rejecting" value={note} onChange={(e) => setNote(e.target.value)} />
              <button disabled={reject.busy} onClick={async () => done(await reject.run())}>Reject</button>
            </div>
          )}
          <ErrorBox error={approve.error ?? reject.error} />
        </>
      )}
    </Card>
  )
}

/**
 * Full physical inventory (ADR-0022): a count of every location of the site or of chosen zones, optionally frozen
 * (no movement, no allocation) while counting; differences are reviewed and posted together by someone who did not
 * count (to SAP as 701/702).
 */
function PhysicalInventory({ site, canRun, onChange }: { site: string; canRun: boolean; onChange: () => void }) {
  const base = `/api/v1/sites/${site}/inventory/physical-inventories`
  const list = useLoad(() => get<Row[]>(base), [base])
  const [selected, setSelected] = useState<string>()
  const [f, setF] = useState({ zones: '', freeze: true, note: '' })
  const create = useAction(() => post<Row>(base, {
    zones: f.zones.split(/[\s,]+/).map((z) => z.trim().toUpperCase()).filter(Boolean), freeze: f.freeze, note: f.note || null }))
  const detail = useLoad(() => (selected ? get<Row>(`${base}/${selected}`) : Promise.resolve(undefined)), [base, selected])
  const act = useAction((step: string) => post<Row>(`${base}/${selected}/${step}`))
  const run = async (step: string) => { if (await act.run(step)) { detail.reload(); list.reload(); onChange() } }
  const d = detail.data
  return (
    <Card title="Physical inventory">
      <Table rows={list.data} empty="No physical inventories" onRow={(r) => setSelected(String(r.pi_no))} columns={[
        { header: 'PI', cell: (r) => String(r.pi_no) },
        { header: 'Scope', cell: (r) => String(r.zones || 'whole site') },
        { header: 'Status', cell: (r) => <Badge value={String(r.status)} /> },
        { header: 'Counted', cell: (r) => `${String(r.counted)} / ${String(r.locations)}`, align: 'right' },
        { header: 'Freeze', cell: (r) => (r.freeze ? 'yes' : 'no') },
        { header: 'Created', cell: (r) => `${fmtDate(r.created_at)} · ${String(r.created_by)}` },
        { header: 'Posted', cell: (r) => (r.posted_at ? `${fmtDate(r.posted_at)} · ${String(r.posted_by)}` : '') },
      ]} />
      {canRun && (
        <div className="row">
          <Field label="Zones" hint="Blank = whole site"><input value={f.zones} onChange={(e) => setF({ ...f, zones: e.target.value.toUpperCase() })} size={16} /></Field>
          <label className="check"><input type="checkbox" checked={f.freeze} onChange={(e) => setF({ ...f, freeze: e.target.checked })} /> Freeze stock while counting</label>
          <Field label="Note"><input value={f.note} onChange={(e) => setF({ ...f, note: e.target.value })} size={20} /></Field>
          <button disabled={create.busy} onClick={async () => { const r = await create.run(); if (r) { list.reload(); setSelected(String(r.pi_no)) } }}>Plan physical inventory</button>
        </div>
      )}
      <ErrorBox error={list.error ?? create.error ?? detail.error ?? act.error} />
      {d && (
        <>
          <h3>{String(d.pi_no)} · <Badge value={String(d.status)} /></h3>
          <div className="facts">
            <span>Scope {String(d.zones || 'whole site')}</span>
            <span>Frozen locations {String(d.frozenLocations)}</span>
            {Object.entries((d.progress as Record<string, number>) ?? {}).map(([k, v]) => <span key={k}>{k.toLowerCase().replace('_', ' ')} {v}</span>)}
            <span>Net difference value {String(d.netValue)}</span>
          </div>
          <Table rows={d.differences as Row[]} empty="No differences" columns={[
            { header: 'Location', cell: (v) => String(v.location_id) },
            { header: 'Item', cell: (v) => `${String(v.owner_id)} / ${String(v.item_no)}${v.lot_no ? ` · ${String(v.lot_no)}` : ''}${v.lpn_id ? ` · ${String(v.lpn_id)}` : ''}` },
            { header: 'System', cell: (v) => fmtQty(v.system_qty), align: 'right' },
            { header: 'Counted', cell: (v) => fmtQty(v.counted_qty), align: 'right' },
            { header: 'Difference', cell: (v) => fmtQty(v.difference), align: 'right' },
            { header: 'Value', cell: (v) => String(v.value ?? ''), align: 'right' },
            { header: 'Count', cell: (v) => <Badge value={String(v.status)} /> },
          ]} />
          {canRun && (
            <div className="actions">
              {d.status === 'PLANNED' && <button className="primary" disabled={act.busy} onClick={() => void run('start')}>Start counting (create RF count tasks)</button>}
              {d.status === 'COUNTING' && <button className="primary" disabled={act.busy} onClick={() => void run('post')}>Post differences</button>}
              {['PLANNED', 'COUNTING'].includes(String(d.status)) && <button disabled={act.busy} onClick={() => void run('cancel')}>Cancel</button>}
            </div>
          )}
        </>
      )}
    </Card>
  )
}
