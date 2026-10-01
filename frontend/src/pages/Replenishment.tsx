import { useState } from 'react'
import { get, post, put, query, type Row } from '../api'
import { useAuth } from '../auth'
import { Badge, Card, ErrorBox, Field, Page, Success, Table, fmtDate, fmtQty, useAction, useLoad, useSite } from '../ui'

/** Replenishment (§7): min/max rules per forward location and item, open and completed replenishments. */
export default function Replenishment() {
  const site = useSite()
  const { hasRole } = useAuth()
  const base = `/api/v1/sites/${site}/inventory`
  const rules = useLoad(() => get<Row[]>(`${base}/replenishment-rules`), [base])
  const [status, setStatus] = useState('OPEN')
  const list = useLoad(() => get<Row[]>(`${base}/replenishments${query({ status })}`), [base, status])
  const [f, setF] = useState({ locationId: '', ownerId: '', itemNo: '', minQty: '', maxQty: '' })
  const save = useAction(() => put(`${base}/replenishment-rules/${f.locationId}/${f.ownerId}/${f.itemNo}`,
    { minQty: Number(f.minQty), maxQty: Number(f.maxQty), active: true }))
  const evaluate = useAction(() => post<{ created: number }>(`${base}/replenishments/evaluate`))
  const set = (k: keyof typeof f) => (e: { target: { value: string } }) => setF({ ...f, [k]: e.target.value.toUpperCase() })
  const refresh = () => { rules.reload(); list.reload() }

  return (
    <Page title="Replenishment" actions={hasRole('SUPERVISOR', 'INV_MANAGER', 'SOLUTION_ADMIN') && (
      <button disabled={evaluate.busy} onClick={async () => { if (await evaluate.run()) refresh() }}>Run top-off now</button>
    )}>
      <ErrorBox error={evaluate.error} />
      <Success>{evaluate.result && `${evaluate.result.created} replenishment(s) created`}</Success>
      {hasRole('SOLUTION_ADMIN', 'INV_MANAGER') && (
        <Card title="Min/max rule">
          <div className="row">
            <Field label="Forward location"><input value={f.locationId} onChange={set('locationId')} size={10} /></Field>
            <Field label="Owner"><input value={f.ownerId} onChange={set('ownerId')} size={8} /></Field>
            <Field label="Item"><input value={f.itemNo} onChange={set('itemNo')} size={10} /></Field>
            <Field label="Min (base unit)"><input type="number" min={0} value={f.minQty} onChange={set('minQty')} size={5} /></Field>
            <Field label="Max"><input type="number" min={1} value={f.maxQty} onChange={set('maxQty')} size={5} /></Field>
            <button className="primary" disabled={save.busy} onClick={async () => { await save.run(); refresh() }}>Save rule</button>
          </div>
          <ErrorBox error={save.error} />
          <Success>{save.done && 'Rule saved; the location is replenished now if it is at or below the minimum'}</Success>
        </Card>
      )}
      <Card title="Rules">
        <ErrorBox error={rules.error} />
        <Table rows={rules.data} empty="No rules: forward locations are not replenished automatically" columns={[
          { header: 'Location', cell: (r) => String(r.location_id) },
          { header: 'Item', cell: (r) => `${String(r.owner_id)} / ${String(r.item_no)}` },
          { header: 'Min', cell: (r) => fmtQty(r.min_qty), align: 'right' },
          { header: 'Max', cell: (r) => fmtQty(r.max_qty), align: 'right' },
          { header: 'On hand', cell: (r) => fmtQty(r.on_hand), align: 'right' },
          { header: 'Incoming', cell: (r) => fmtQty(r.incoming), align: 'right' },
          { header: 'Active', cell: (r) => (r.active ? 'yes' : 'no') },
        ]} />
      </Card>
      <Card title="Replenishments" actions={
        <select value={status} onChange={(e) => setStatus(e.target.value)}>
          {['', 'OPEN', 'DONE', 'CANCELLED'].map((s) => <option key={s} value={s}>{s || 'All'}</option>)}
        </select>
      }>
        <ErrorBox error={list.error} />
        <Table rows={list.data} empty="None" columns={[
          { header: 'To', cell: (r) => String(r.location_id) },
          { header: 'Item', cell: (r) => `${String(r.owner_id)} / ${String(r.item_no)}${r.lot_no ? ` · ${String(r.lot_no)}` : ''}` },
          { header: 'Qty', cell: (r) => fmtQty(r.qty), align: 'right' },
          { header: 'From', cell: (r) => `${String(r.source_location)}${r.source_lpn ? ` · ${String(r.source_lpn)}` : ''}` },
          { header: 'Trigger', cell: (r) => String(r.trigger) },
          { header: 'Status', cell: (r) => <Badge value={String(r.status)} /> },
          { header: 'Created', cell: (r) => fmtDate(r.created_at) },
        ]} />
      </Card>
    </Page>
  )
}
