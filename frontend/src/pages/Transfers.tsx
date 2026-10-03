import { useState } from 'react'
import { Link } from 'react-router-dom'
import { get, post, type Row } from '../api'
import { useAuth } from '../auth'
import { Badge, Card, ErrorBox, Field, Page, Success, Table, fmtDate, fmtQty, useAction, useLoad, useSite } from '../ui'

/**
 * Transfers between sites started in the WMS (ADR-0023): main warehouse to a satellite store, store to store, without
 * an SAP stock transport order. The transfer is picked and shipped like an order; the receiving site gets it as an
 * expected receipt (in transit). SAP is posted as a two-step stock transfer: 303 at shipment, 305 at receipt.
 */
export default function Transfers() {
  const site = useSite()
  const { hasRole } = useAuth()
  const base = `/api/v1/sites/${site}/outbound/transfers`
  const outgoing = useLoad(() => get<Row[]>(`${base}?direction=OUT`), [base])
  const incoming = useLoad(() => get<Row[]>(`${base}?direction=IN`), [base])
  const [f, setF] = useState({ to: '', carrier: '', note: '' })
  const [lines, setLines] = useState([{ ownerId: '', itemNo: '', qty: '', uom: 'EA', lotNo: '' }])
  const create = useAction(() => post<Row>(base, {
    toSiteId: f.to, carrierScac: f.carrier || null, note: f.note || null,
    lines: lines.filter((l) => l.itemNo && l.qty).map((l) => ({ ...l, qty: Number(l.qty), lotNo: l.lotNo || null })),
  }))
  const setLine = (i: number, k: string, v: string) => setLines(lines.map((l, n) => (n === i ? { ...l, [k]: v } : l)))
  const canCreate = hasRole('SUPERVISOR', 'INV_MANAGER')

  return (
    <Page title="Transfers between sites">
      <Card title={`Leaving ${site}`}>
        <ErrorBox error={outgoing.error} />
        <Table rows={outgoing.data} empty="No transfers from this site" columns={[
          { header: 'Transfer', cell: (r) => <Link to={`/orders/${String(r.erp_doc_no)}`}>{String(r.erp_doc_no)}</Link> },
          { header: 'To', cell: (r) => String(r.to_site) },
          { header: 'Status', cell: (r) => <Badge value={String(r.status)} /> },
          { header: 'Lines', cell: (r) => String(r.lines), align: 'right' },
          { header: 'Picked', cell: (r) => fmtQty(r.qty_picked), align: 'right' },
          { header: 'Shipped', cell: (r) => fmtDate(r.shipped_at) },
          { header: 'SAP (303)', cell: (r) => String(r.erp_document ?? '') },
          { header: 'Note', cell: (r) => String(r.note ?? '') },
        ]} />
      </Card>
      <Card title={`Coming to ${site}`}>
        <ErrorBox error={incoming.error} />
        <Table rows={incoming.data} empty="Nothing on its way" columns={[
          { header: 'Transfer', cell: (r) => <Link to={`/receipts/${String(r.erp_doc_no)}`}>{String(r.erp_doc_no)}</Link> },
          { header: 'From', cell: (r) => String(r.from_site) },
          { header: 'Status at sender', cell: (r) => <Badge value={String(r.status)} /> },
          { header: 'Shipped', cell: (r) => (r.shipped_at ? fmtDate(r.shipped_at) : 'not yet') },
          { header: 'Note', cell: (r) => String(r.note ?? '') },
        ]} />
        <p className="muted">Once shipped, a transfer is an expected receipt here (receive it on RF like any delivery).</p>
      </Card>
      {canCreate && (
        <Card title="New transfer">
          <div className="row">
            <Field label="To site"><input value={f.to} onChange={(e) => setF({ ...f, to: e.target.value.toUpperCase() })} size={8} /></Field>
            <Field label="Carrier" hint="Optional"><input value={f.carrier} onChange={(e) => setF({ ...f, carrier: e.target.value.toUpperCase() })} size={6} /></Field>
            <Field label="Note"><input value={f.note} onChange={(e) => setF({ ...f, note: e.target.value })} size={24} /></Field>
          </div>
          {lines.map((l, i) => (
            <div className="row" key={i}>
              <Field label="Owner"><input value={l.ownerId} onChange={(e) => setLine(i, 'ownerId', e.target.value.toUpperCase())} size={7} /></Field>
              <Field label={`Item ${i + 1}`}><input value={l.itemNo} onChange={(e) => setLine(i, 'itemNo', e.target.value.toUpperCase())} size={10} /></Field>
              <Field label="Qty"><input type="number" min={0} step="any" value={l.qty} onChange={(e) => setLine(i, 'qty', e.target.value)} size={5} /></Field>
              <Field label="UoM"><input value={l.uom} onChange={(e) => setLine(i, 'uom', e.target.value.toUpperCase())} size={3} /></Field>
              <Field label="Lot" hint="Optional"><input value={l.lotNo} onChange={(e) => setLine(i, 'lotNo', e.target.value)} size={8} /></Field>
            </div>
          ))}
          <div className="actions">
            <button onClick={() => setLines([...lines, { ownerId: lines[lines.length - 1]?.ownerId ?? '', itemNo: '', qty: '', uom: 'EA', lotNo: '' }])}>Add line</button>
            <button className="primary" disabled={create.busy || !f.to}
                    onClick={async () => { if (await create.run()) { outgoing.reload(); setLines([{ ownerId: '', itemNo: '', qty: '', uom: 'EA', lotNo: '' }]) } }}>
              Create transfer</button>
          </div>
          <ErrorBox error={create.error} />
          <Success>{create.result && `Transfer ${String(create.result.erp_doc_no)} created (${String(create.result.status)}): pick and ship it like an order`}</Success>
        </Card>
      )}
    </Page>
  )
}
