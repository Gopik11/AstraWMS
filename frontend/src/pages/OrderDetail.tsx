import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { get, post, type Row } from '../api'
import { useAuth } from '../auth'
import { Badge, Card, ErrorBox, Field, Page, Table, fmtQty, useAction, useLoad, useSite } from '../ui'

interface OrderDetailView extends Row {
  status: string
  lines: Row[]
  allocations: Row[]
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
              {d.wave_no != null && <span>Wave {String(d.wave_no)}</span>}
              <span>Pick LPN {String(d.pick_lpn)} at {String(d.staging_location)}</span>
              {d.tracking_no != null && <span>Tracking {String(d.tracking_no)}</span>}
              {d.erp_document != null && <span>ERP document {String(d.erp_document)}</span>}
            </div>
            {d.erp_error_text != null && <div className="alert error">ERP: {String(d.erp_error_text)}</div>}
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
            ]} />
          </Card>
          <Card title="Allocations and picks">
            <Table rows={d.allocations} empty="Not allocated (pooled or backordered)" columns={[
              { header: 'Line', cell: (a) => String(a.erp_line_ref) },
              { header: 'Location', cell: (a) => String(a.location_id) },
              { header: 'LPN', cell: (a) => String(a.lpn_id ?? '') },
              { header: 'Lot', cell: (a) => String(a.lot_no ?? '') },
              { header: 'Qty', cell: (a) => fmtQty(a.qty), align: 'right' },
              { header: 'Picked', cell: (a) => fmtQty(a.qty_picked), align: 'right' },
              { header: 'Status', cell: (a) => <Badge value={String(a.status)} /> },
              { header: '', cell: (a) => (a.replaces ? 're-allocation' : '') },
            ]} />
          </Card>
          {hasRole('SUPERVISOR') && d.status === 'PICKED' && <Ship base={base} onDone={detail.reload} />}
          {hasRole('SUPERVISOR') && d.status === 'SHIP_ERROR' && <Repost base={base} onDone={detail.reload} />}
        </>
      )}
    </Page>
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
