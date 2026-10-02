import { Link } from 'react-router-dom'
import { get, post, type ReceiptSummary, type Row, type Task } from '../api'
import { useAuth } from '../auth'
import { Card, ErrorBox, Page, Success, fmtQty, useAction, useLoad, useSite } from '../ui'

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

/** One number that needs attention; highlighted when not zero, red when its oldest item is past the threshold. */
function Attention({ n, label, to, detail, oldest, limitMin }: {
  n: number | undefined; label: string; to: string; detail?: string; oldest?: number; limitMin?: number
}) {
  const late = oldest !== undefined && limitMin !== undefined && oldest > limitMin
  return (
    <Link className={`tile ${late ? 'tile-late' : n ? 'tile-warn' : ''}`} to={to} title={detail}>
      <span className="tile-n">{n ?? '…'}</span>
      <span className="tile-l">{label}</span>
      {oldest !== undefined && n ? <span className="tile-d">oldest {age(oldest)}{limitMin !== undefined ? ` (limit ${age(limitMin)})` : ''}</span> : null}
      {detail && <span className="tile-d">{detail}</span>}
    </Link>
  )
}

/** Minutes since a timestamp. */
function minutesSince(at: unknown): number {
  return at ? Math.max(0, (Date.now() - new Date(String(at)).getTime()) / 60000) : 0
}

function age(min: number): string {
  return min < 60 ? `${Math.round(min)} min` : min < 2880 ? `${Math.round(min / 60)} h` : `${Math.round(min / 1440)} d`
}

function oldestOf(times: unknown[]): number | undefined {
  return times.length ? Math.max(...times.map(minutesSince)) : undefined
}

/** Control-tower thresholds (minutes): past them a tile turns red. */
const LIMITS = { receiptNotStarted: 30, dockStock: 15, pickAssigned: 15, postingFailed: 30, trailerDwell: 120 }

/** Site overview (§G.6 control tower, first cut): what needs attention, then documents and work by status. */
export default function Home() {
  const site = useSite()
  const { session, hasRole } = useAuth()
  const receipts = useLoad(() => get<ReceiptSummary[]>(`/api/v1/sites/${site}/receipts`), [site])
  const orders = useLoad(() => get<Row[]>(`/api/v1/sites/${site}/outbound/orders`), [site])
  const tasks = useLoad(() => get<Task[]>(`/api/v1/sites/${site}/tasks`), [site])
  const returns = useLoad(() => get<Row[]>(`/api/v1/sites/${site}/returns`), [site])
  const dock = useLoad(() => get<Row[]>(`/api/v1/sites/${site}/inventory/inbound-staging`), [site])
  const yard = useLoad(() => get<{ inYard: Row[]; late: Row[] }>(`/api/v1/sites/${site}/yard/summary`), [site])

  const notStartedRows = receipts.data?.filter((r) => r.status === 'NOT_STARTED') ?? []
  const notStarted = receipts.data ? notStartedRows.length : undefined
  const failedTimes = [...(receipts.data ?? []).filter((r) => r.status === 'POSTING_FAILED').map((r) => r.updatedAt),
    ...(returns.data ?? []).filter((r) => r.status === 'POSTING_FAILED').map((r) => r.updated_at)]
  const pickRows = (tasks.data ?? []).filter((t) => t.taskType === 'PICK' && t.status === 'ASSIGNED')
  const pickStuck = tasks.data ? pickRows.filter((t) => minutesSince(t.assignedAt) > LIMITS.pickAssigned).length : undefined
  const openOrders = orders.data?.filter((o) => !['SHIPPED', 'CONFIRMED', 'CANCELLED', 'SHIP_ERROR'].includes(String(o.status)))
  const backorderedLines = openOrders?.reduce((n, o) => n + Number(o.lines_short ?? 0), 0)
  const postingFailed = receipts.data && returns.data
    ? receipts.data.filter((r) => r.status === 'POSTING_FAILED').length + returns.data.filter((r) => r.status === 'POSTING_FAILED').length
    : undefined
  const shipErrors = orders.data?.filter((o) => o.status === 'SHIP_ERROR').length
  const dockAvailable = dock.data?.filter((b) => b.stock_status === 'AVAILABLE')
  const dockQty = dockAvailable?.reduce((n, b) => n + Number(b.qty), 0)
  const dockLpns = dockAvailable ? new Set(dockAvailable.map((b) => `${String(b.location_id)}/${String(b.lpn_id)}`)).size : undefined
  // Dock sweep (ADR-0020): putaways for everything still at the dock; loose stock is put on an LPN first.
  const sweep = useAction(() => post<{ putawaysCreated: number; lpnsCreated: number; failed: string[] }>(`/api/v1/sites/${site}/tasks/sweep-dock`))

  return (
    <Page title={`Overview · ${site}`}>
      <p className="muted">
        Signed in as <strong>{session.userName}</strong> (tenant {session.tenant}) · roles: {session.roles.join(', ') || 'none'}
      </p>
      <Card title="Needs attention">
        <ErrorBox error={receipts.error ?? orders.error ?? returns.error ?? dock.error} />
        <div className="tiles">
          <Attention n={notStarted} label="Receipts not started" to="/receipts?status=NOT_STARTED"
                     oldest={oldestOf(notStartedRows.map((r) => r.createdAt))} limitMin={LIMITS.receiptNotStarted} />
          <Attention n={backorderedLines} label="Order lines short" to="/orders?status=BACKORDERED"
                     detail="Unallocated quantity on open orders; recovered when stock is put away" />
          <Attention n={postingFailed} label="ERP posting failed" to="/receipts?status=POSTING_FAILED"
                     detail="Receipts and returns the ERP rejected; repost after the fix"
                     oldest={oldestOf(failedTimes)} limitMin={LIMITS.postingFailed} />
          <Attention n={shipErrors} label="Goods issue failed" to="/orders?status=SHIP_ERROR"
                     oldest={oldestOf((orders.data ?? []).filter((o) => o.status === 'SHIP_ERROR').map((o) => o.updated_at))}
                     limitMin={LIMITS.postingFailed} />
          <Attention n={pickStuck} label={`Picks assigned > ${LIMITS.pickAssigned} min`} to="/tasks?type=PICK&status=ASSIGNED"
                     detail="Assigned to an operator but not confirmed"
                     oldest={oldestOf(pickRows.map((t) => t.assignedAt))} limitMin={LIMITS.pickAssigned} />
          <Attention n={dockLpns} label="Pallets waiting on dock" to="/tasks?type=PUTAWAY"
                     detail={dockQty === undefined ? undefined : `${fmtQty(dockQty)} units available at dock / receiving, not yet allocable`}
                     oldest={oldestOf((dockAvailable ?? []).map((b) => b.receipt_date))} limitMin={LIMITS.dockStock} />
          <Attention n={yard.data?.inYard.length} label="Trailers in the yard" to="/yard"
                     detail={yard.data?.late.length ? `${yard.data.late.length} appointment(s) late` : 'Dwell since gate check-in'}
                     oldest={oldestOf((yard.data?.inYard ?? []).map((a) => a.checked_in_at))} limitMin={LIMITS.trailerDwell} />
        </div>
        {hasRole('SUPERVISOR') && (dockLpns ?? 0) > 0 && (
          <div className="row">
            <button disabled={sweep.busy} onClick={async () => { if (await sweep.run()) setTimeout(() => dock.reload(), 3000) }}>
              Create putaways for dock stock
            </button>
          </div>
        )}
        <ErrorBox error={sweep.error} />
        <Success>{sweep.result && `${sweep.result.putawaysCreated} putaway task(s) created, ${sweep.result.lpnsCreated} loose quantity(ies) put on an LPN (their putaway follows)${sweep.result.failed.length ? `; not done: ${sweep.result.failed.join(', ')}` : ''}`}</Success>
      </Card>
      <div className="grid">
        <Card title="Receipts"><ErrorBox error={receipts.error} /><Tiles counts={countBy(receipts.data, (r) => r.status)} link="/receipts" /></Card>
        <Card title="Outbound orders"><ErrorBox error={orders.error} /><Tiles counts={countBy(orders.data, (r) => String(r.status))} link="/orders" /></Card>
        <Card title="Tasks"><ErrorBox error={tasks.error} /><Tiles counts={countBy(tasks.data, (t) => t.status)} link="/tasks" /></Card>
      </div>
    </Page>
  )
}
