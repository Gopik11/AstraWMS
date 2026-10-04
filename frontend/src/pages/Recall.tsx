import { useState } from 'react'
import { get, query, type Row } from '../api'
import { Badge, Card, ErrorBox, Field, Page, Table, fmtDate, fmtQty, useAction } from '../ui'

/**
 * Recall (ADR-0025): every location, LPN and owner holding an item lot (or serial) at any site of the user's scope,
 * and what of it is in transit between sites. Use Adjust / status to block what is found (QC hold blocks allocation).
 */
export default function Recall() {
  const [f, setF] = useState({ itemNo: '', lotNo: '', serialNo: '' })
  const find = useAction(() => get<{ balances: Row[]; inTransit: Row[]; sites: number }>(`/api/v1/network/recall${query(f)}`))
  const r = find.result
  const qty = (r?.balances ?? []).reduce((n, b) => n + Number(b.qty ?? 0), 0)
  return (
    <Page title="Recall by lot or serial">
      <Card>
        <form className="row" onSubmit={(e) => { e.preventDefault(); void find.run() }}>
          <Field label="Item"><input value={f.itemNo} onChange={(e) => setF({ ...f, itemNo: e.target.value })} required size={12} /></Field>
          <Field label="Lot"><input value={f.lotNo} onChange={(e) => setF({ ...f, lotNo: e.target.value })} size={12} /></Field>
          <Field label="or serial"><input value={f.serialNo} onChange={(e) => setF({ ...f, serialNo: e.target.value })} size={14} /></Field>
          <button className="primary" disabled={find.busy || !f.itemNo || (!f.lotNo && !f.serialNo)}>Find everywhere</button>
        </form>
      </Card>
      <ErrorBox error={find.error} />
      {r && (
        <>
          <Card title={`${fmtQty(qty)} unit(s) at ${r.sites} site(s), ${r.inTransit.length} transfer line(s) in transit`}>
            <Table rows={r.balances} empty="None in stock" columns={[
              { header: 'Site', cell: (b) => <strong>{String(b.site_id)}</strong> },
              { header: 'Location', cell: (b) => String(b.location_id) },
              { header: 'LPN', cell: (b) => String(b.lpn_id || '') },
              { header: 'Owner', cell: (b) => `${String(b.owner_id)} (${String(b.ownership_type ?? 'OWN').toLowerCase().replace('_', '-')})` },
              { header: 'Lot', cell: (b) => String(b.lot_no || '') },
              { header: 'Status', cell: (b) => <Badge value={String(b.stock_status)} /> },
              { header: 'Qty', cell: (b) => fmtQty(b.qty), align: 'right' },
              { header: 'Allocated', cell: (b) => fmtQty(b.allocated_qty ?? 0), align: 'right' },
              { header: 'Expiry', cell: (b) => String(b.expiry_date ?? '') },
            ]} />
          </Card>
          <Card title="In transit">
            <Table rows={r.inTransit} empty="Nothing in transit" columns={[
              { header: 'Transfer', cell: (t) => String(t.transfer_no) },
              { header: 'From → to', cell: (t) => `${String(t.from_site)} → ${String(t.to_site)}` },
              { header: 'Owner', cell: (t) => String(t.owner_id) },
              { header: 'Lot', cell: (t) => String(t.lot_no || '') },
              { header: 'Open qty', cell: (t) => fmtQty(t.open_qty), align: 'right' },
              { header: 'Shipped', cell: (t) => fmtDate(t.shipped_at) },
            ]} />
          </Card>
        </>
      )}
    </Page>
  )
}
