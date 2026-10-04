import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { get, post, put, type Row } from '../api'
import { useAuth } from '../auth'
import { Badge, Card, ErrorBox, Field, Page, Table, fmtDate, fmtQty, useAction, useLoad, useSite } from '../ui'

interface OrderDetailView extends Row {
  status: string
  lines: Row[]
  allocations: Row[]
}

/** Release policy of one order (ADR-0021): priority, and ship complete while nothing is released to picking. */
function OrderPolicy({ base, order, onDone }: { base: string; order: OrderDetailView; onDone: () => void }) {
  const [priority, setPriority] = useState(String(order.priority ?? 50))
  const [complete, setComplete] = useState(order.ship_complete === true)
  const save = useAction(() => put(`${base}/policy`, { priority: Number(priority), shipComplete: complete }))
  return (
    <div className="row">
      <Field label="Priority" hint="0–100, higher first"><input type="number" min={0} max={100} value={priority}
                                                              onChange={(e) => setPriority(e.target.value)} size={4} /></Field>
      <label className="check"><input type="checkbox" checked={complete} disabled={order.status === 'RELEASED'}
                                      onChange={(e) => setComplete(e.target.checked)} /> Ship complete (no partial shipment)</label>
      <button disabled={save.busy} onClick={async () => { if (await save.run()) onDone() }}>Save</button>
      <ErrorBox error={save.error} />
    </div>
  )
}

/** One outbound delivery: lines, allocations and picks; ship and repost (IF-OB-003). */
export default function OrderDetail() {
  const { doc = '' } = useParams()
  const site = useSite()
  const { hasRole } = useAuth()
  const base = `/api/v1/sites/${site}/outbound/orders/${doc}`
  const detail = useLoad(() => get<OrderDetailView>(base), [base])
  const d = detail.data

  return (
    <Page title={`Order ${doc}`} actions={<Link to="/orders">All orders</Link>}>
      <ErrorBox error={detail.error} />
      {d && (
        <>
          <Card>
            <div className="facts">
              <span>Status <Badge value={d.status} /></span>
              <span>Type {String(d.order_type)}</span>
              <span>Carrier {String(d.carrier_scac ?? '—')}</span>
              {d.ship_to_name != null && <span>Customer {String(d.ship_to_name)}</span>}
              {d.wave_no != null && <span>Wave {String(d.wave_no)}</span>}
              {d.cutoff_at != null && <span>Cutoff {fmtDate(d.cutoff_at)}</span>}
              <span>Priority {String(d.priority ?? 50)}</span>
              {d.criticality != null && d.criticality !== 'NORMAL' && <span>Criticality {String(d.criticality).toLowerCase()}</span>}
              {d.ship_complete === true && <span><Badge value="SHIP COMPLETE" /></span>}
              <span>Pick LPN {String(d.pick_lpn)} at {String(d.staging_location)}</span>
              {d.tracking_no != null && <span>Tracking {String(d.tracking_no)}</span>}
              {d.erp_document != null && <span>ERP document {String(d.erp_document)}</span>}
            </div>
            {d.erp_error_text != null && <div className="alert error">ERP: {String(d.erp_error_text)}</div>}
            {hasRole('SUPERVISOR') && ['POOLED', 'BACKORDERED', 'RELEASED'].includes(d.status) && (
              <OrderPolicy base={base} order={d} onDone={detail.reload} />
            )}
          </Card>
          <Card title="Lines">
            <Table rows={d.lines} columns={[
              { header: 'Line', cell: (l) => String(l.erp_line_ref) },
              { header: 'Item', cell: (l) => String(l.item_no) },
              { header: 'Requested', cell: (l) => `${fmtQty(l.qty_requested)} ${String(l.uom)}`, align: 'right' },
              { header: 'Allocated', cell: (l) => fmtQty(l.qty_allocated), align: 'right' },
              { header: 'Picked', cell: (l) => fmtQty(l.qty_picked), align: 'right' },
              { header: 'Short', cell: (l) => fmtQty(l.qty_short), align: 'right' },
              { header: 'Short (picks)', cell: (l) => fmtQty(l.qty_short_pick), align: 'right' },
              { header: 'Why short', cell: (l) => (Number(l.qty_short) > 0 && l.short_reason
                  ? <span title={String(l.short_detail ?? '')}>{String(l.short_reason).toLowerCase().replace(/_/g, ' ')}: {String(l.short_detail ?? '')}</span> : '') },
              { header: 'Allocation rule', cell: (l) => <span className="muted small">{String(l.allocation_rule ?? '')}</span> },
              { header: 'Short state', cell: (l) => Number(l.qty_short) <= 0 ? ''
                  : l.short_hold ? 'waiting for stock' : Number(l.qty_short_closed) >= Number(l.qty_short) ? 'ships short' : 'recoverable' },
              { header: 'From', cell: (l) => d.allocations.filter((a) => a.erp_line_ref === l.erp_line_ref && a.status !== 'CANCELLED')
                  .map((a) => `${String(a.location_id)}${a.lpn_id ? ` / ${String(a.lpn_id)}` : ''}`).join(', ') },
            ]} />
          </Card>
          {(d.recoveries as Row[] | undefined)?.length ? (
            <Card title="Recovered shorts">
              <Table rows={d.recoveries as Row[]} columns={[
                { header: 'When', cell: (r) => fmtDate(r.recovered_at) },
                { header: 'Line', cell: (r) => String(r.erp_line_ref) },
                { header: 'Qty', cell: (r) => fmtQty(r.qty), align: 'right' },
                { header: 'Freed by', cell: (r) => (r.trigger === 'MANUAL' ? `supervisor reallocation (${String(r.recovered_by)})`
                    : `${String(r.txn_type ?? 'stock')}${r.lpn_id ? ` of ${String(r.lpn_id)}` : ''} at ${String(r.location_id ?? '?')}`) },
              ]} />
            </Card>
          ) : null}
          <Card title="Allocations and picks">
            <Table rows={d.allocations} empty="Not allocated (pooled or backordered)" columns={[
              { header: 'Line', cell: (a) => String(a.erp_line_ref) },
              { header: 'Location', cell: (a) => String(a.location_id) },
              { header: 'LPN', cell: (a) => String(a.lpn_id ?? '') },
              { header: 'Lot', cell: (a) => String(a.lot_no ?? '') },
              { header: 'Qty', cell: (a) => fmtQty(a.qty), align: 'right' },
              { header: 'Picked', cell: (a) => fmtQty(a.qty_picked), align: 'right' },
              { header: 'Status', cell: (a) => <Badge value={String(a.status)} /> },
              { header: 'Short', cell: (a) => (a.short_reason ? `${String(a.short_reason)} → ${String(a.short_action ?? 'REALLOCATE')}` : '') },
              { header: '', cell: (a) => (a.replaces ? 're-allocation' : '') },
            ]} />
          </Card>
          {hasRole('SUPERVISOR') && ['BACKORDERED', 'RELEASED', 'PICKED'].includes(d.status) && !d.loaded
            && d.lines.some((l) => Number(l.qty_short) > 0) && <Reallocate base={base} onDone={detail.reload} />}
          {hasRole('SUPERVISOR') && d.status === 'PICKED' && <Ship base={base} onDone={detail.reload} />}
          {hasRole('SUPERVISOR') && d.status === 'SHIP_ERROR' && <Repost base={base} onDone={detail.reload} />}
        </>
      )}
    </Page>
  )
}

/** Supervisor: allocate the order's short lines from stock available now (ADR-0019); recovered lines get pick tasks. */
function Reallocate({ base, onDone }: { base: string; onDone: () => void }) {
  const run = useAction(() => post<Row>(`${base}/reallocate`))
  const close = useAction(() => post<Row>(`${base}/close-shorts`))
  return (
    <Card title="Short lines">
      <p className="muted">Stock arriving in storage recovers backorders automatically. Reallocate now after a manual fix
        (e.g. a stock correction), or to recover a picked order.</p>
      <ErrorBox error={run.error ?? close.error} />
      {run.result && <div className="alert ok">Recovered {fmtQty(run.result.recoveredQty)}</div>}
      <div className="row">
        <button className="primary" disabled={run.busy} onClick={async () => { if (await run.run()) onDone() }}>Reallocate shorts</button>
        <button disabled={close.busy} title="What is still short ships short; backordered shorts stop waiting"
                onClick={async () => { if (await close.run()) onDone() }}>Close shorts (ship short)</button>
      </div>
    </Card>
  )
}

function Ship({ base, onDone }: { base: string; onDone: () => void }) {
  const [form, setForm] = useState({ carrierScac: '', trackingNo: '', billOfLading: '' })
  const ship = useAction(() => post(`${base}/ship`, {
    carrierScac: form.carrierScac || null, trackingNo: form.trackingNo || null, billOfLading: form.billOfLading || null,
  }))
  const set = (k: keyof typeof form) => (e: { target: { value: string } }) => setForm({ ...form, [k]: e.target.value })
  return (
    <Card title="Ship">
      <p className="muted">Issues the picked stock and sends the shipment confirmation (goods issue) to the ERP.</p>
      <div className="row">
        <Field label="Carrier (SCAC)"><input value={form.carrierScac} onChange={set('carrierScac')} size={6} /></Field>
        <Field label="Tracking no."><input value={form.trackingNo} onChange={set('trackingNo')} /></Field>
        <Field label="Bill of lading"><input value={form.billOfLading} onChange={set('billOfLading')} /></Field>
      </div>
      <ErrorBox error={ship.error} />
      <button className="primary" disabled={ship.busy} onClick={async () => (await ship.run()) && onDone()}>Ship order</button>
    </Card>
  )
}

function Repost({ base, onDone }: { base: string; onDone: () => void }) {
  const repost = useAction(() => post(`${base}/repost`))
  return (
    <Card title="Goods issue failed in the ERP">
      <p className="muted">Fix the cause in the ERP, then repost with the same WMS transaction ID.</p>
      <ErrorBox error={repost.error} />
      <button className="primary" disabled={repost.busy} onClick={async () => (await repost.run()) && onDone()}>Repost</button>
    </Card>
  )
}
