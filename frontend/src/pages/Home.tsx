import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { get, post, type ReceiptSummary, type Row, type Task } from '../api'
import { useAuth } from '../auth'
import { offlineTasks, useOffline } from '../offline'
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

/** Minutes until a timestamp (negative when past). */
function minutesUntil(at: unknown): number {
  return at ? (new Date(String(at)).getTime() - Date.now()) / 60000 : Number.POSITIVE_INFINITY
}

function age(min: number): string {
  return min < 60 ? `${Math.round(min)} min` : min < 2880 ? `${Math.round(min / 60)} h` : `${Math.round(min / 1440)} d`
}

function oldestOf(times: unknown[]): number | undefined {
  return times.length ? Math.max(...times.map(minutesSince)) : undefined
}

function endOfToday(): number {
  const d = new Date()
  d.setHours(23, 59, 59, 999)
  return d.getTime()
}

/** Control-tower thresholds (minutes): past them a tile turns red. */
const LIMITS = { receiptNotStarted: 30, dockStock: 15, pickAssigned: 15, postingFailed: 30, trailerDwell: 120, cutoffSoon: 120 }

/** Task types each RF role works (same routing as the task service, ADR-0019). */
const TYPES_BY_ROLE: Record<string, string[]> = {
  RECEIVER: ['RECEIVE', 'PUTAWAY', 'RETURN', 'REPLEN', 'MOVE'],
  PICKER: ['PICK', 'RETURN', 'REPLEN', 'COUNT', 'MOVE'],
  INV_ANALYST: ['COUNT', 'REPLEN', 'MOVE'],
  SUPERVISOR: ['RECEIVE', 'PUTAWAY', 'PICK', 'RETURN', 'REPLEN', 'COUNT', 'MOVE'],
}

function Section({ title, hint, children }: { title: string; hint?: string; children: ReactNode }) {
  return (
    <Card title={title}>
      {hint && <p className="muted">{hint}</p>}
      {children}
    </Card>
  )
}

/**
 * Site overview by role (§G.6 control tower): every user sees the work and the exceptions of their own roles —
 * their tasks on RF, receiving, picking, inventory control, the supervisor's control tower, and integration health
 * for administrators. A user with several roles sees each of their sections once.
 */
export default function Home() {
  const site = useSite()
  const { session, hasRole } = useAuth()
  const supervisor = hasRole('SUPERVISOR')
  const rf = hasRole('RECEIVER', 'PICKER', 'INV_ANALYST', 'SUPERVISOR')
  const sections = [
    rf && <MyWork key="me" site={site} />,
    hasRole('RECEIVER') && !supervisor && <Receiving key="rcv" site={site} />,
    hasRole('PICKER') && !supervisor && <Picking key="pick" site={site} />,
    hasRole('INV_ANALYST', 'INV_MANAGER') && <InventoryControl key="inv" site={site} />,
    supervisor && <ControlTower key="sup" site={site} />,
    hasRole('SOLUTION_ADMIN') && <Integration key="adm" site={site} />,
  ].filter(Boolean)
  return (
    <Page title={`Overview · ${site}`}>
      <p className="muted">
        Signed in as <strong>{session.userName}</strong> (tenant {session.tenant}) · roles: {session.roles.join(', ') || 'none'}
      </p>
      {sections.length > 0 ? sections : <Documents site={site} />}
    </Page>
  )
}

// ------------------------------------------------------------------ everyone on RF

/** My work: my assigned task, work waiting for my roles, and what this device holds offline. */
function MyWork({ site }: { site: string }) {
  const { session } = useAuth()
  const tasks = useLoad(() => get<Task[]>(`/api/v1/sites/${site}/tasks`), [site])
  const { online, pending, failed } = useOffline()
  const mine = (tasks.data ?? []).filter((t) => t.status === 'ASSIGNED' && t.assignedTo === session.userName)
  const types = new Set(session.roles.flatMap((r) => TYPES_BY_ROLE[r] ?? []))
  const waiting = (tasks.data ?? []).filter((t) => t.status === 'RELEASED' && types.has(t.taskType))
  const byType = countBy(waiting, (t) => t.taskType)
  const onDevice = offlineTasks(site).length
  return (
    <Section title="My work">
      <ErrorBox error={tasks.error} />
      <div className="tiles">
        <Link className={`tile ${mine.length ? 'tile-warn' : ''}`} to="/rf">
          <span className="tile-n">{tasks.data ? mine.length : '…'}</span>
          <span className="tile-l">{mine.length ? 'Assigned to me: continue on RF' : 'Nothing assigned: get the next task'}</span>
          {mine[0] && <span className="tile-d">{mine[0].taskType} {mine[0].fromLocation ?? ''}{mine[0].targetLocation ? ` → ${mine[0].targetLocation}` : ''}</span>}
        </Link>
        {Object.entries(byType).map(([type, n]) => (
          <Link key={type} className="tile" to={`/tasks?type=${type}&status=RELEASED`}>
            <span className="tile-n">{n}</span><span className="tile-l">{type.toLowerCase()} tasks waiting</span>
          </Link>
        ))}
        {(onDevice > 0 || pending.length > 0 || failed.length > 0 || !online) && (
          <Link className={`tile ${failed.length ? 'tile-late' : pending.length ? 'tile-warn' : ''}`} to="/rf">
            <span className="tile-n">{pending.length}</span>
            <span className="tile-l">{online ? 'scans waiting to sync' : 'offline: scans kept on this device'}</span>
            <span className="tile-d">{onDevice} task(s) on this device{failed.length ? ` · ${failed.length} need attention` : ''}</span>
          </Link>
        )}
      </div>
    </Section>
  )
}

// ------------------------------------------------------------------ receiver

function Receiving({ site }: { site: string }) {
  const receipts = useLoad(() => get<ReceiptSummary[]>(`/api/v1/sites/${site}/receipts`), [site])
  const returns = useLoad(() => get<Row[]>(`/api/v1/sites/${site}/returns`), [site])
  const dock = useLoad(() => get<Row[]>(`/api/v1/sites/${site}/inventory/inbound-staging`), [site])
  const yard = useLoad(() => get<{ inYard: Row[]; doors: { door: string; current?: Row; next?: Row }[] }>(`/api/v1/sites/${site}/yard/summary`), [site])
  const due = (receipts.data ?? []).filter((r) => r.status === 'NOT_STARTED'
    && (!r.expectedArrivalUtc || new Date(r.expectedArrivalUtc).getTime() <= endOfToday()))
  const inProgress = (receipts.data ?? []).filter((r) => r.status === 'IN_PROGRESS')
  const rmas = (returns.data ?? []).filter((r) => r.status === 'EXPECTED' || r.status === 'RECEIVING')
  const dockLpns = dock.data ? new Set(dock.data.filter((b) => b.stock_status === 'AVAILABLE').map((b) => `${String(b.location_id)}/${String(b.lpn_id)}`)).size : undefined
  const atDoors = yard.data?.doors.filter((d) => d.current).length
  const nextAppts = (yard.data?.doors ?? []).filter((d) => d.next && minutesUntil(d.next.scheduled_start) < 240).length
  return (
    <Section title="Receiving" hint="Deliveries, returns and the dock: receive on RF; pallets left on the dock get putaway tasks.">
      <ErrorBox error={receipts.error ?? returns.error ?? dock.error} />
      <div className="tiles">
        <Attention n={receipts.data ? due.length : undefined} label="Receipts due today, not started" to="/receipts?status=NOT_STARTED"
                   oldest={oldestOf(due.map((r) => r.createdAt))} limitMin={LIMITS.receiptNotStarted} />
        <Attention n={receipts.data ? inProgress.length : undefined} label="Receipts in progress" to="/receipts?status=IN_PROGRESS" />
        <Attention n={returns.data ? rmas.length : undefined} label="Returns to receive" to="/returns" />
        <Attention n={dockLpns} label="Pallets waiting on dock" to="/tasks?type=PUTAWAY"
                   oldest={oldestOf((dock.data ?? []).map((b) => b.receipt_date))} limitMin={LIMITS.dockStock} />
        <Attention n={yard.data?.inYard.length} label="Trailers in the yard" to="/yard"
                   detail={yard.data ? `${atDoors} at a door${nextAppts ? ` · ${nextAppts} due in 4 h` : ''}` : undefined}
                   oldest={oldestOf((yard.data?.inYard ?? []).map((a) => a.checked_in_at))} limitMin={LIMITS.trailerDwell} />
      </div>
    </Section>
  )
}

// ------------------------------------------------------------------ picker

function Picking({ site }: { site: string }) {
  const tasks = useLoad(() => get<Task[]>(`/api/v1/sites/${site}/tasks`), [site])
  const orders = useLoad(() => get<Row[]>(`/api/v1/sites/${site}/outbound/orders?status=RELEASED`), [site])
  const picks = (tasks.data ?? []).filter((t) => t.taskType === 'PICK' && t.status === 'RELEASED')
  const replens = (tasks.data ?? []).filter((t) => t.taskType === 'REPLEN' && t.status === 'RELEASED')
  const urgent = picks.filter((t) => t.priority >= 65)
  const dueSoon = (orders.data ?? []).filter((o) => minutesUntil(o.cutoff_at ?? o.planned_gi_utc) < LIMITS.cutoffSoon)
  return (
    <Section title="Picking" hint="Replenishments feed the pick faces first; picks rise in priority as their carrier cutoff nears.">
      <ErrorBox error={tasks.error ?? orders.error} />
      <div className="tiles">
        <Attention n={tasks.data ? picks.length : undefined} label="Picks waiting" to="/tasks?type=PICK&status=RELEASED"
                   detail={urgent.length ? `${urgent.length} urgent (cutoff near)` : undefined} />
        <Attention n={tasks.data ? replens.length : undefined} label="Replenishments waiting" to="/tasks?type=REPLEN&status=RELEASED" />
        <Attention n={orders.data ? dueSoon.length : undefined} label="Orders due within 2 h" to="/orders?status=RELEASED" />
      </div>
    </Section>
  )
}

// ------------------------------------------------------------------ inventory analyst / manager

function InventoryControl({ site }: { site: string }) {
  const { hasRole } = useAuth()
  const base = `/api/v1/sites/${site}/inventory`
  const counts = useLoad(() => get<Row[]>(`${base}/counts`), [base])
  const pis = useLoad(() => get<Row[]>(`${base}/physical-inventories`), [base])
  const replens = useLoad(() => get<Row[]>(`${base}/replenishments?status=OPEN`), [base])
  const held = useLoad(() => Promise.all(['QI', 'BLOCKED'].map((s) => get<{ items: Row[] }>(`${base}/balances?status=${s}&limit=200`))), [base])
  const issues = useLoad(() => get<Row[]>(`${base}/material-issues`), [base])
  const slotting = useLoad(() => get<Row[]>(`${base}/slotting`), [base])
  const byStatus = countBy(counts.data, (c) => String(c.status))
  const openPi = (pis.data ?? []).find((p) => p.status === 'COUNTING' || p.status === 'PLANNED')
  const heldRows = held.data?.flatMap((p) => p.items)
  const toApprove = (issues.data ?? []).filter((i) => i.status === 'REQUESTED').length
  const toIssue = (issues.data ?? []).filter((i) => i.status === 'APPROVED' || i.status === 'PARTIALLY_ISSUED').length
  const suggestions = (slotting.data ?? []).filter((s) => s.suggested_face).length
  return (
    <Section title="Inventory control" hint="Counts and variances, replenishment, held stock, material issues and slotting.">
      <ErrorBox error={counts.error ?? replens.error ?? issues.error} />
      <div className="tiles">
        <Attention n={counts.data ? (byStatus.PENDING_APPROVAL ?? 0) : undefined} label="Count variances to approve" to="/counts"
                   detail={hasRole('INV_MANAGER', 'SUPERVISOR') ? undefined : 'an inventory manager or supervisor approves'} />
        <Attention n={counts.data ? (byStatus.OPEN ?? 0) + (byStatus.RECOUNT ?? 0) : undefined} label="Counts and recounts open" to="/counts" />
        <Attention n={pis.data ? (openPi ? 1 : 0) : undefined} label={openPi ? `Physical inventory ${String(openPi.pi_no)} ${String(openPi.status).toLowerCase()}` : 'Physical inventories open'}
                   to="/counts" detail={openPi ? `${String(openPi.counted)} of ${String(openPi.locations)} counted` : undefined} />
        <Attention n={replens.data?.length} label="Replenishments open" to="/replenishment" />
        <Attention n={heldRows?.length} label="Balances in QC or blocked" to="/inventory"
                   detail={heldRows ? `${fmtQty(heldRows.reduce((n, b) => n + Number(b.qty), 0))} units` : undefined} />
        <Attention n={issues.data ? toApprove : undefined} label="Material issues to approve" to="/material-issues?status=REQUESTED" />
        <Attention n={issues.data ? toIssue : undefined} label="Material issues to issue" to="/material-issues?status=APPROVED" />
        <Attention n={slotting.data ? suggestions : undefined} label="Slotting suggestions" to="/slotting" />
      </div>
    </Section>
  )
}

// ------------------------------------------------------------------ supervisor

function ControlTower({ site }: { site: string }) {
  const receipts = useLoad(() => get<ReceiptSummary[]>(`/api/v1/sites/${site}/receipts`), [site])
  const orders = useLoad(() => get<Row[]>(`/api/v1/sites/${site}/outbound/orders`), [site])
  const tasks = useLoad(() => get<Task[]>(`/api/v1/sites/${site}/tasks`), [site])
  const returns = useLoad(() => get<Row[]>(`/api/v1/sites/${site}/returns`), [site])
  const dock = useLoad(() => get<Row[]>(`/api/v1/sites/${site}/inventory/inbound-staging`), [site])
  const yard = useLoad(() => get<{ inYard: Row[]; late: Row[] }>(`/api/v1/sites/${site}/yard/summary`), [site])
  const labor = useLoad(() => get<{ activeOperators: number; operators: { current?: { overStandard: boolean } }[]; backlog: { standardHours: number }[] }>(`/api/v1/sites/${site}/tasks/labor?hours=8`), [site])
  const waves = useLoad(() => get<Row[]>(`/api/v1/sites/${site}/outbound/waves`), [site])
  const counts = useLoad(() => get<Row[]>(`/api/v1/sites/${site}/inventory/counts?status=PENDING_APPROVAL`), [site])
  const issues = useLoad(() => get<Row[]>(`/api/v1/sites/${site}/inventory/material-issues?status=REQUESTED`), [site])

  const notStartedRows = receipts.data?.filter((r) => r.status === 'NOT_STARTED') ?? []
  const failedTimes = [...(receipts.data ?? []).filter((r) => r.status === 'POSTING_FAILED').map((r) => r.updatedAt),
    ...(returns.data ?? []).filter((r) => r.status === 'POSTING_FAILED').map((r) => r.updated_at)]
  const pickRows = (tasks.data ?? []).filter((t) => t.taskType === 'PICK' && t.status === 'ASSIGNED')
  const pickStuck = tasks.data ? pickRows.filter((t) => minutesSince(t.assignedAt) > LIMITS.pickAssigned).length : undefined
  const openOrders = orders.data?.filter((o) => !['SHIPPED', 'CONFIRMED', 'CANCELLED', 'SHIP_ERROR'].includes(String(o.status)))
  const backorderedLines = openOrders?.reduce((n, o) => n + Number(o.lines_short ?? 0), 0)
  const dueSoon = openOrders?.filter((o) => ['RELEASED', 'POOLED', 'BACKORDERED'].includes(String(o.status))
    && minutesUntil(o.cutoff_at ?? o.planned_gi_utc) < LIMITS.cutoffSoon)
  const postingFailed = receipts.data && returns.data ? failedTimes.length : undefined
  const shipErrors = orders.data?.filter((o) => o.status === 'SHIP_ERROR').length
  const dockAvailable = dock.data?.filter((b) => b.stock_status === 'AVAILABLE')
  const dockQty = dockAvailable?.reduce((n, b) => n + Number(b.qty), 0)
  const dockLpns = dockAvailable ? new Set(dockAvailable.map((b) => `${String(b.location_id)}/${String(b.lpn_id)}`)).size : undefined
  const overStandard = labor.data?.operators.filter((o) => o.current?.overStandard).length
  const backlogHours = labor.data?.backlog.reduce((n, b) => n + b.standardHours, 0)
  const openWaves = (waves.data ?? []).filter((w) => w.status === 'PLANNED' || w.status === 'HELD')
  const approvals = counts.data && issues.data ? counts.data.length + issues.data.length : undefined
  // Dock sweep (ADR-0020): putaways for everything still at the dock; loose stock is put on an LPN first.
  const sweep = useAction(() => post<{ putawaysCreated: number; lpnsCreated: number; failed: string[] }>(`/api/v1/sites/${site}/tasks/sweep-dock`))

  return (
    <>
      <Section title="Control tower" hint="What needs attention now; a tile turns red when its oldest item is past its limit.">
        <ErrorBox error={receipts.error ?? orders.error ?? returns.error ?? dock.error} />
        <div className="tiles">
          <Attention n={receipts.data ? notStartedRows.length : undefined} label="Receipts not started" to="/receipts?status=NOT_STARTED"
                     oldest={oldestOf(notStartedRows.map((r) => r.createdAt))} limitMin={LIMITS.receiptNotStarted} />
          <Attention n={backorderedLines} label="Order lines short" to="/orders?status=BACKORDERED"
                     detail="Unallocated quantity on open orders; recovered when stock is put away" />
          <Attention n={dueSoon?.length} label="Orders due within 2 h" to="/orders?status=RELEASED"
                     detail="Carrier cutoff or planned goods issue" />
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
        {(dockLpns ?? 0) > 0 && (
          <div className="row">
            <button disabled={sweep.busy} onClick={async () => { if (await sweep.run()) setTimeout(() => dock.reload(), 3000) }}>
              Create putaways for dock stock
            </button>
          </div>
        )}
        <ErrorBox error={sweep.error} />
        <Success>{sweep.result && `${sweep.result.putawaysCreated} putaway task(s) created, ${sweep.result.lpnsCreated} loose quantity(ies) put on an LPN (their putaway follows)${sweep.result.failed.length ? `; not done: ${sweep.result.failed.join(', ')}` : ''}`}</Success>
      </Section>
      <Section title="People, waves and approvals">
        <div className="tiles">
          <Attention n={labor.data?.activeOperators} label="Operators active" to="/labor"
                     detail={backlogHours === undefined ? undefined : `${backlogHours.toFixed(1)} h of work waiting (standard)`} />
          <Attention n={overStandard} label="Tasks over their standard" to="/labor" />
          <Attention n={waves.data ? openWaves.length : undefined} label="Waves planned or held" to="/waves"
                     detail={openWaves.some((w) => w.status === 'HELD') ? `${openWaves.filter((w) => w.status === 'HELD').length} held` : undefined} />
          <Attention n={approvals} label="Approvals waiting" to="/counts"
                     detail={counts.data && issues.data ? `${counts.data.length} count variance(s), ${issues.data.length} material issue(s)` : undefined} />
        </div>
      </Section>
      <div className="grid">
        <Card title="Receipts"><ErrorBox error={receipts.error} /><Tiles counts={countBy(receipts.data, (r) => r.status)} link="/receipts" /></Card>
        <Card title="Outbound orders"><ErrorBox error={orders.error} /><Tiles counts={countBy(orders.data, (r) => String(r.status))} link="/orders" /></Card>
        <Card title="Tasks"><ErrorBox error={tasks.error} /><Tiles counts={countBy(tasks.data, (t) => t.status)} link="/tasks" /></Card>
      </div>
    </>
  )
}

// ------------------------------------------------------------------ solution admin

const SERVICES = ['master-data-service', 'inventory-service', 'inbound-service', 'task-service', 'outbound-service', 'sap-adapter']

function Integration({ site }: { site: string }) {
  const ops = useLoad(() => Promise.all(SERVICES.map(async (svc) => {
    let health = 'DOWN'
    try {
      const r = await fetch(`/health/${svc}`, { cache: 'no-store' })
      health = ((await r.json()) as { status?: string }).status ?? `HTTP ${r.status}`
    } catch { /* down */ }
    const [outbox, dlq] = await Promise.all([
      get<{ pending: number; oldestPendingSeconds: number | null }>(`/api/v1/ops/${svc}/outbox`).catch(() => undefined),
      get<{ messages: unknown[] }>(`/api/v1/ops/${svc}/dlq`).catch(() => undefined),
    ])
    return { svc, health, outbox, dlq }
  })), [])
  const receipts = useLoad(() => get<ReceiptSummary[]>(`/api/v1/sites/${site}/receipts?status=POSTING_FAILED`), [site])
  const orders = useLoad(() => get<Row[]>(`/api/v1/sites/${site}/outbound/orders?status=SHIP_ERROR`), [site])
  const returns = useLoad(() => get<Row[]>(`/api/v1/sites/${site}/returns`), [site])
  const down = ops.data?.filter((s) => s.health !== 'UP')
  const dead = ops.data?.reduce((n, s) => n + (s.dlq?.messages.length ?? 0), 0)
  const backlog = ops.data?.reduce((n, s) => n + (s.outbox?.pending ?? 0), 0)
  const oldest = ops.data ? Math.max(0, ...ops.data.map((s) => s.outbox?.oldestPendingSeconds ?? 0)) / 60 : undefined
  const erpFailed = receipts.data && orders.data && returns.data
    ? receipts.data.length + orders.data.length + returns.data.filter((r) => r.status === 'POSTING_FAILED').length : undefined
  return (
    <Section title="Integration and platform" hint="Service health, messages not delivered, and ERP postings that failed.">
      <div className="tiles">
        <Attention n={down?.length} label="Services not healthy" to="/operations"
                   detail={down?.length ? down.map((s) => s.svc).join(', ') : 'all six up'} />
        <Attention n={dead} label="Dead letters" to="/operations" detail="Messages a service could not process; replay after the fix" />
        <Attention n={backlog} label="Outbox backlog" to="/operations" oldest={backlog ? oldest : undefined} limitMin={5} />
        <Attention n={erpFailed} label="ERP postings failed" to="/receipts?status=POSTING_FAILED"
                   detail="Receipts, returns and goods issues the ERP rejected" />
      </div>
    </Section>
  )
}

// ------------------------------------------------------------------ other roles (QA, ERP integration)

function Documents({ site }: { site: string }) {
  const receipts = useLoad(() => get<ReceiptSummary[]>(`/api/v1/sites/${site}/receipts`), [site])
  const orders = useLoad(() => get<Row[]>(`/api/v1/sites/${site}/outbound/orders`), [site])
  return (
    <div className="grid">
      <Card title="Receipts"><ErrorBox error={receipts.error} /><Tiles counts={countBy(receipts.data, (r) => r.status)} link="/receipts" /></Card>
      <Card title="Outbound orders"><ErrorBox error={orders.error} /><Tiles counts={countBy(orders.data, (r) => String(r.status))} link="/orders" /></Card>
    </div>
  )
}
