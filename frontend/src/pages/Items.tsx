import { useNavigate, useParams } from 'react-router-dom'
import { get, query, type Row } from '../api'
import { Badge, Card, ErrorBox, Page, SearchBox, Table, fmtDate, fmtQty, useLoad } from '../ui'

/**
 * Enterprise item balance (ADR-0025): one item across every site of the user's scope, from the one ledger. On hand,
 * available, allocated, picked, packed, in transit (in and out), quarantine, blocked, damaged, expired, returned and
 * available for transfer, with the owner and its ownership type. In transit is stock a transfer issued at one site
 * that the other has not received yet: the network total is on hand everywhere plus in transit.
 */
export default function Items() {
  const { ownerId, itemNo } = useParams()
  return ownerId && itemNo ? <ItemBalance ownerId={ownerId} itemNo={itemNo} /> : <ItemSearch />
}

function ItemSearch() {
  const navigate = useNavigate()
  const params = new URLSearchParams(window.location.search)
  const q = params.get('q') ?? ''
  const items = useLoad(() => get<Row[]>(`/api/v1/network/items${query({ q })}`), [q])
  return (
    <Page title="Items across sites">
      <Card>
        <SearchBox value={q} onSearch={(v) => navigate(`/items${query({ q: v })}`)} placeholder="Item number" />
      </Card>
      <ErrorBox error={items.error} />
      <Card>
        <Table rows={items.data} empty="No items" onRow={(r) => navigate(`/items/${String(r.owner_id)}/${encodeURIComponent(String(r.item_no))}`)}
               columns={[
                 { header: 'Owner', cell: (r) => String(r.owner_id) },
                 { header: 'Item', cell: (r) => <strong>{String(r.item_no)}</strong> },
                 { header: 'On hand (network)', cell: (r) => fmtQty(r.on_hand), align: 'right' },
                 { header: 'In transit', cell: (r) => fmtQty(r.in_transit), align: 'right' },
               ]} />
      </Card>
    </Page>
  )
}

interface Balance {
  ownerId: string
  itemNo: string
  ownershipType: string
  sites: Row[]
  inTransit: Row[]
  totalOnHand: number
  totalInTransit: number
  networkTotal: number
}

const BUCKETS: [string, string][] = [
  ['on_hand', 'On hand'], ['available', 'Available'], ['allocated', 'Allocated'], ['picked', 'Picked'],
  ['packed', 'Packed'], ['in_transit_in', 'In transit in'], ['in_transit_out', 'In transit out'],
  ['quarantine', 'Quarantine'], ['blocked', 'Blocked'], ['damaged', 'Damaged'], ['expired', 'Expired'],
  ['returned', 'Returned'], ['available_for_transfer', 'Available for transfer'], ['open_demand', 'Open demand'],
]

const OWNERSHIP: Record<string, string> = {
  OWN: 'Own stock', CONSIGNMENT: 'Consignment (supplier-owned until consumed)', CUSTOMER_OWNED: 'Customer-owned',
  SUPPLIER_OWNED: 'Supplier-owned',
}

function ItemBalance({ ownerId, itemNo }: { ownerId: string; itemNo: string }) {
  const navigate = useNavigate()
  const balance = useLoad(() => get<Balance>(`/api/v1/network/items/${ownerId}/${encodeURIComponent(itemNo)}`), [ownerId, itemNo])
  const outbound = useLoad(() => get<Row[]>(`/api/v1/network/outbound/items/${encodeURIComponent(itemNo)}`), [itemNo])
  const b = balance.data
  const out = new Map((outbound.data ?? []).map((r) => [String(r.site_id), r]))
  const rows: Row[] = (b?.sites ?? []).map((s): Row => ({ ...s, packed: out.get(String(s.site_id))?.packed ?? 0,
    open_demand: out.get(String(s.site_id))?.open_demand ?? 0 }))
  const total = (k: string) => rows.reduce((n, r) => n + Number(r[k] ?? 0), 0)
  return (
    <Page title={`${itemNo} · ${ownerId}`} actions={<button onClick={() => navigate('/items')}>All items</button>}>
      <ErrorBox error={balance.error ?? outbound.error} />
      {b && (
        <div className="tiles">
          <div className="tile"><span className="tile-n">{fmtQty(b.totalOnHand)}</span><span className="tile-l">On hand at all sites</span></div>
          <div className="tile"><span className="tile-n">{fmtQty(b.totalInTransit)}</span><span className="tile-l">In transit between sites</span></div>
          <div className="tile"><span className="tile-n">{fmtQty(b.networkTotal)}</span><span className="tile-l">Network total</span>
            <span className="tile-d">on hand + in transit</span></div>
          <div className="tile"><span className="tile-l">Owner {b.ownerId}</span><span className="tile-d">{OWNERSHIP[b.ownershipType] ?? b.ownershipType}</span></div>
        </div>
      )}
      <Card title="By site">
        <Table rows={b ? rows : undefined} empty="No stock of this item at your sites"
               columns={[
                 { header: 'Site', cell: (r) => <strong>{String(r.site_id)}</strong> },
                 ...BUCKETS.map(([k, label]) => ({ header: label, align: 'right' as const,
                   cell: (r: Row) => (Number(r[k] ?? 0) === 0 ? <span className="muted">0</span> : fmtQty(r[k])) })),
                 { header: 'PI', cell: (r) => (r.frozen ? <Badge value="FROZEN" /> : null) },
               ]} />
        {b && rows.length > 0 && (
          <p className="muted">Totals: {BUCKETS.map(([k, label]) => `${label.toLowerCase()} ${fmtQty(total(k))}`).join(' · ')}</p>
        )}
        <p className="muted">Available = available status, not allocated, not expired, not frozen, not staged for shipping.
          Picked = at outbound staging. Available for transfer = available less the store's safety stock.</p>
      </Card>
      <Card title="In transit">
        <Table rows={b?.inTransit} empty="Nothing in transit" columns={[
          { header: 'Transfer', cell: (t) => String(t.transfer_no) },
          { header: 'From', cell: (t) => String(t.from_site) },
          { header: 'To', cell: (t) => String(t.to_site) },
          { header: 'Lot', cell: (t) => String(t.lot_no || '') },
          { header: 'Open qty', cell: (t) => fmtQty(t.open_qty), align: 'right' },
          { header: 'Shipped', cell: (t) => fmtDate(t.shipped_at) },
        ]} />
      </Card>
    </Page>
  )
}
