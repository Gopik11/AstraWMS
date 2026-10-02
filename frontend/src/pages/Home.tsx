import { Link } from 'react-router-dom'
import { get, type ReceiptSummary, type Row, type Task } from '../api'
import { useAuth } from '../auth'
import { Card, ErrorBox, Page, fmtQty, useLoad, useSite } from '../ui'

function countBy<T>(rows: T[] | undefined, key: (r: T) => string): Record<string, number> {
  const out: Record<string, number> = {}
  ;(rows ?? []).forEach((r) => (out[key(r)] = (out[key(r)] ?? 0) + 1))
  return out
}

function Tiles({ counts, link }: { counts: Record<string, number>; link: string }) {
  const entries = Object.entries(counts)
  if (entries.length === 0) {
    return <p className="muted">None yet</p>
  }
  return (
    <div className="tiles">
      {entries.map(([status, n]) => (
        <Link key={status} className="tile" to={`${link}?status=${status}`}>
          <span className="tile-n">{n}</span>
          <span className="tile-l">{status}</span>
        </Link>
      ))}
    </div>
  )
}

/** One number that needs attention; highlighted when not zero. */
function Attention({ n, label, to, detail }: { n: number | undefined; label: string; to: string; detail?: string }) {
  return (
    <Link className={`tile ${n ? 'tile-warn' : ''}`} to={to} title={detail}>
      <span className="tile-n">{n ?? '…'}</span>
      <span className="tile-l">{label}</span>
      {detail && <span className="tile-d">{detail}</span>}
    </Link>
  )
}

/** Site overview (§G.6 control tower, first cut): what needs attention, then documents and work by status. */
export default function Home() {
  const site = useSite()
  const { session } = useAuth()
  const receipts = useLoad(() => get<ReceiptSummary[]>(`/api/v1/sites/${site}/receipts`), [site])
  const orders = useLoad(() => get<Row[]>(`/api/v1/sites/${site}/outbound/orders`), [site])
  const tasks = useLoad(() => get<Task[]>(`/api/v1/sites/${site}/tasks`), [site])
  const returns = useLoad(() => get<Row[]>(`/api/v1/sites/${site}/returns`), [site])
  const dock = useLoad(() => get<Row[]>(`/api/v1/sites/${site}/inventory/inbound-staging`), [site])

  const notStarted = receipts.data?.filter((r) => r.status === 'NOT_STARTED').length
  const openOrders = orders.data?.filter((o) => !['SHIPPED', 'CONFIRMED', 'CANCELLED', 'SHIP_ERROR'].includes(String(o.status)))
  const backorderedLines = openOrders?.reduce((n, o) => n + Number(o.lines_short ?? 0), 0)
  const postingFailed = receipts.data && returns.data
    ? receipts.data.filter((r) => r.status === 'POSTING_FAILED').length + returns.data.filter((r) => r.status === 'POSTING_FAILED').length
    : undefined
  const shipErrors = orders.data?.filter((o) => o.status === 'SHIP_ERROR').length
  const dockAvailable = dock.data?.filter((b) => b.stock_status === 'AVAILABLE')
  const dockQty = dockAvailable?.reduce((n, b) => n + Number(b.qty), 0)
  const dockLpns = dockAvailable ? new Set(dockAvailable.map((b) => `${String(b.location_id)}/${String(b.lpn_id)}`)).size : undefined

  return (
    <Page title={`Overview · ${site}`}>
      <p className="muted">
        Signed in as <strong>{session.userName}</strong> (tenant {session.tenant}) · roles: {session.roles.join(', ') || 'none'}
      </p>
      <Card title="Needs attention">
        <ErrorBox error={receipts.error ?? orders.error ?? returns.error ?? dock.error} />
        <div className="tiles">
          <Attention n={notStarted} label="Receipts not started" to="/receipts?status=NOT_STARTED" />
          <Attention n={backorderedLines} label="Order lines short" to="/orders?status=BACKORDERED"
                     detail="Unallocated quantity on open orders; recovered when stock is put away" />
          <Attention n={postingFailed} label="ERP posting failed" to="/receipts?status=POSTING_FAILED"
                     detail="Receipts and returns the ERP rejected; repost after the fix" />
          <Attention n={shipErrors} label="Goods issue failed" to="/orders?status=SHIP_ERROR" />
          <Attention n={dockLpns} label="Pallets waiting on dock" to="/tasks?type=PUTAWAY"
                     detail={dockQty === undefined ? undefined : `${fmtQty(dockQty)} units available at dock / receiving, not yet allocable`} />
        </div>
      </Card>
      <div className="grid">
        <Card title="Receipts"><ErrorBox error={receipts.error} /><Tiles counts={countBy(receipts.data, (r) => r.status)} link="/receipts" /></Card>
        <Card title="Outbound orders"><ErrorBox error={orders.error} /><Tiles counts={countBy(orders.data, (r) => String(r.status))} link="/orders" /></Card>
        <Card title="Tasks"><ErrorBox error={tasks.error} /><Tiles counts={countBy(tasks.data, (t) => t.status)} link="/tasks" /></Card>
      </div>
    </Page>
  )
}
