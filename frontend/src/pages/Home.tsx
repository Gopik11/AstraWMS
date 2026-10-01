import { Link } from 'react-router-dom'
import { get, type ReceiptSummary, type Row, type Task } from '../api'
import { useAuth } from '../auth'
import { Card, ErrorBox, Page, useLoad, useSite } from '../ui'

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

/** Site overview (§G.6 control tower, first cut): documents and work by status. */
export default function Home() {
  const site = useSite()
  const { session } = useAuth()
  const receipts = useLoad(() => get<ReceiptSummary[]>(`/api/v1/sites/${site}/receipts`), [site])
  const orders = useLoad(() => get<Row[]>(`/api/v1/sites/${site}/outbound/orders`), [site])
  const tasks = useLoad(() => get<Task[]>(`/api/v1/sites/${site}/tasks`), [site])

  return (
    <Page title={`Overview · ${site}`}>
      <p className="muted">
        Signed in as <strong>{session.userName}</strong> (tenant {session.tenant}) · roles: {session.roles.join(', ') || 'none'}
      </p>
      <div className="grid">
        <Card title="Receipts"><ErrorBox error={receipts.error} /><Tiles counts={countBy(receipts.data, (r) => r.status)} link="/receipts" /></Card>
        <Card title="Outbound orders"><ErrorBox error={orders.error} /><Tiles counts={countBy(orders.data, (r) => String(r.status))} link="/orders" /></Card>
        <Card title="Tasks"><ErrorBox error={tasks.error} /><Tiles counts={countBy(tasks.data, (t) => t.status)} link="/tasks" /></Card>
      </div>
    </Page>
  )
}
