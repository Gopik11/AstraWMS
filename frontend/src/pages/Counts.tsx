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
