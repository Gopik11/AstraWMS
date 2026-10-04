import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { get, post, type ReceiptSummary, type Row, type Task } from '../api'
import { useAuth } from '../auth'
import { offlineTasks, useOffline } from '../offline'
import { AskBox } from './Ask'
import { cutoffForecast } from '../forecast'
import { acceptRecommendation, type Recommendation } from '../replenish'
import { Card, ErrorBox, Page, Table, fmtQty, useAction, useLoad, useSiteContext } from '../ui'

function countBy<T>(rows: T[] | undefined, key: (r: T) => string): Record<string, number> {
  const out: Record<string, number> = {}
  ;(rows ?? []).forEach((r) => (out[key(r)] = (out[key(r)] ?? 0) + 1))
  return out
}

/** Counts per status of one list, each linking to that list filtered the same way (ADR-0024: tile = list). */
function Tiles({ rows, status, link }: { rows: { data?: unknown[]; error?: unknown }; status: (r: never) => string; link: string }) {
  if (rows.error) {
    return null   // the card's ErrorBox says what failed; no "none" for data that did not load
  }
  if (!rows.data) {
    return <p className="muted">Loading…</p>
  }
  const entries = Object.entries(countBy(rows.data as never[], status))
  if (entries.length === 0) {
    return <p className="muted">None</p>
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
function Attention({ n, label, one, to, detail, oldest, limitMin, action }: {
  n: number | undefined; label: string; one?: string; to: string; detail?: string; oldest?: number; limitMin?: number
  action?: TileAction
}) {
  const late = oldest !== undefined && limitMin !== undefined && oldest > limitMin
  return (
    <div className={`tile ${late ? 'tile-late' : n ? 'tile-warn' : ''}`} title={detail}>
      <Link className="tile-link" to={to}>
        <span className="tile-n">{n ?? '…'}</span>
        <span className="tile-l">{n === 1 && one ? one : label}</span>
        {oldest !== undefined && n ? <span className="tile-d">oldest {age(oldest)}{limitMin !== undefined ? ` (limit ${age(limitMin)})` : ''}</span> : null}
        {detail && <span className="tile-d">{detail}</span>}
      </Link>
      {action && n ? <TileButton action={action} /> : null}
    </div>
  )
}

/** The supervisor's one-click fix for a tile (ADR-0024): runs on the tile, after a confirmation. */
interface TileAction { label: string; confirm: string; run: () => Promise<string> }

function TileButton({ action }: { action: TileAction }) {
  const act = useAction(action.run)
  return (
    <div className="tile-action">
      <button className="small" disabled={act.busy} onClick={() => { if (window.confirm(action.confirm)) void act.run() }}>
        {action.label}</button>
      {act.result && <span className="tile-d">{act.result}</span>}
      {act.error ? <span className="tile-d text-late">{act.error instanceof Error ? act.error.message : String(act.error)}</span> : null}
    </div>
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

/** "1 pallet", "3 pallets" */
function plural(n: number | undefined, one: string, many = `${one}s`): string {
  return n === 1 ? one : many
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
const LIMITS = { receiptNotStarted: 30, dockStock: 15, taskAssigned: 30, postingFailed: 30, trailerDwell: 120, cutoffSoon: 120 }

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
  const { site, isStore } = useSiteContext()
  const { session, hasRole } = useAuth()
  const supervisor = hasRole('SUPERVISOR')
  const rf = hasRole('RECEIVER', 'PICKER', 'INV_ANALYST', 'SUPERVISOR')
  // ADR-0024: at a satellite store the overview is the store's work only; waves, yard, labor and billing are DC work.
  // ADR-0026 store home: my work, transfers coming in, issues, inventory below min, counts, sync status.
  const sections = isStore ? [
    rf && <MyWork key="me" site={site} />,
    <StoreInbound key="in" site={site} />,
    <StoreIssues key="issues" site={site} />,
    <StoreBelowMin key="min" site={site} />,
    <StoreCounts key="counts" site={site} />,
    <StoreSync key="sync" site={site} />,
  ].filter(Boolean) : [
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

// ------------------------------------------------------------------ satellite store (ADR-0024)

/** Transfers and deliveries coming to the store: what to receive on RF, and what is still on its way. */
function StoreInbound({ site }: { site: string }) {
  const receipts = useLoad(() => get<ReceiptSummary[]>(`/api/v1/sites/${site}/receipts`), [site])
  const incoming = useLoad(() => get<Row[]>(`/api/v1/sites/${site}/outbound/transfers?direction=IN`), [site])
  const toReceive = (receipts.data ?? []).filter((r) => r.status === 'NOT_STARTED' || r.status === 'IN_PROGRESS')
  const onTheWay = (incoming.data ?? []).filter((t) => !['SHIPPED', 'CONFIRMED', 'CANCELLED'].includes(String(t.status)))
  const shipped = (incoming.data ?? []).filter((t) => ['SHIPPED', 'CONFIRMED'].includes(String(t.status)))
  return (
    <Section title="Inbound" hint="Transfers to this store: received on RF; a shipped transfer is an expected receipt.">
      <ErrorBox error={receipts.error ?? incoming.error} />
      <div className="tiles">
        <Attention n={receipts.data ? toReceive.length : undefined} label="Deliveries to receive" one="Delivery to receive"
                   to="/receipts?status=NOT_STARTED" detail={shipped.length ? `${shipped.length} transfer(s) shipped here` : 'Receive on RF'}
                   oldest={oldestOf(toReceive.map((r) => r.createdAt))} limitMin={24 * 60} />
        <Attention n={incoming.data ? onTheWay.length : undefined} label="Transfers being prepared" one="Transfer being prepared"
                   to="/transfers" detail={onTheWay.slice(0, 3).map((t) => `${String(t.erp_doc_no)} from ${String(t.from_site)}`).join(', ') || 'Not shipped yet'} />
      </div>
    </Section>
  )
}

/** Material issues of the store: to approve (supervisors) and to scan out on RF. */
function StoreIssues({ site }: { site: string }) {
  const { hasRole } = useAuth()
  const issues = useLoad(() => get<Row[]>(`/api/v1/sites/${site}/inventory/material-issues`), [site])
  const byStatus = countBy(issues.data, (r) => String(r.status))
  return (
    <Section title="Issues" hint="Issue to cost centres, WBS elements and orders; scan out on RF material issue.">
      <ErrorBox error={issues.error} />
      <div className="tiles">
        <Attention n={issues.data ? (byStatus.APPROVED ?? 0) + (byStatus.PARTIALLY_ISSUED ?? 0) : undefined}
                   label="Material issues to issue" one="Material issue to issue" to="/material-issues?status=APPROVED,PARTIALLY_ISSUED" />
        {hasRole('SUPERVISOR', 'INV_MANAGER') && (
          <Attention n={issues.data ? byStatus.REQUESTED ?? 0 : undefined} label="Material issues to approve" one="Material issue to approve"
                     to="/material-issues?status=REQUESTED" />
        )}
      </div>
    </Section>
  )
}

/**
 * Store items below min + safety stock, or at stockout risk (ADR-0026), with the recommended transfer. Without usage
 * history the risk is "no history", never "none".
 */
function StoreBelowMin({ site }: { site: string }) {
  const recs = useLoad(() => get<Recommendation[]>(`/api/v1/network/replenishment?siteId=${site}`), [site])
  const short = (recs.data ?? []).filter((r) => r.recommended || r.shortage)
  return (
    <Section title="Inventory below min" hint="Available + pipeline against min + safety stock; risk from usage over the last 28 days.">
      <ErrorBox error={recs.error} />
      {recs.data && short.length === 0 && <p className="muted">Every item with a policy is covered.</p>}
      {!recs.data && !recs.error && <p className="muted">Loading…</p>}
      {short.length > 0 && (
        <Table rows={short} columns={[
          { header: 'Item', cell: (r) => `${r.itemNo} (${r.ownerId})` },
          { header: 'Available', cell: (r) => fmtQty(r.available), align: 'right' },
          { header: 'Min + safety', cell: (r) => fmtQty(Number(r.min) + Number(r.safety)), align: 'right' },
          { header: 'Pipeline', cell: (r) => fmtQty(Number(r.inTransit) + Number(r.openTransferQty)), align: 'right' },
          { header: 'Stockout', cell: (r) => r.stockoutDate ?? <span className="muted">no history</span> },
          { header: 'Risk', cell: (r) => <span className={r.stockoutRisk ? 'text-late' : 'muted'}>{r.stockoutRiskText}</span> },
          { header: 'Recommended', cell: (r) => (r.recommended ? `${fmtQty(r.qty)} from ${String(r.sourceSite)} by ${String(r.requiredDate)}` : '') },
        ]} />
      )}
    </Section>
  )
}

function StoreCounts({ site }: { site: string }) {
  const counts = useLoad(() => get<Row[]>(`/api/v1/sites/${site}/inventory/counts`), [site])
  const open = (counts.data ?? []).filter((c) => ['OPEN', 'RECOUNT'].includes(String(c.status)))
  const approval = (counts.data ?? []).filter((c) => c.status === 'PENDING_APPROVAL')
  return (
    <Section title="Counts" hint="Blind counts on RF; variances go to a supervisor.">
      <ErrorBox error={counts.error} />
      <div className="tiles">
        <Attention n={counts.data ? open.length : undefined} label="Counts open" one="Count open" to="/counts" detail="Count on RF" />
        <Attention n={counts.data ? approval.length : undefined} label="Variances to approve" one="Variance to approve" to="/counts" />
      </div>
    </Section>
  )
}

/**
 * Sync status. Offline work ships (ADR-0023): RF receiving, material issue and counting keep working without network,
 * scans wait on this device and are sent in order; the server wins on a stock conflict. Lists and approvals are online
 * only. This says which is the case now.
 */
function StoreSync({ site }: { site: string }) {
  const { online, pending, failed } = useOffline()
  const onDevice = offlineTasks(site).length
  return (
    <Section title="Sync status">
      <div className="tiles">
        <div className={`tile ${online ? '' : 'tile-warn'}`}><span className="tile-n">{online ? 'Online' : 'Offline'}</span>
          <span className="tile-l">{online ? 'Connected' : 'Scans are kept on this device'}</span></div>
        <Link className={`tile ${pending.length ? 'tile-warn' : ''}`} to="/rf"><span className="tile-n">{pending.length}</span>
          <span className="tile-l">{pending.length === 1 ? 'scan waiting to send' : 'scans waiting to send'}</span></Link>
        <Link className={`tile ${failed.length ? 'tile-late' : ''}`} to="/rf"><span className="tile-n">{failed.length}</span>
          <span className="tile-l">need attention</span><span className="tile-d">refused when sent: the server's stock stands</span></Link>
        <div className="tile"><span className="tile-n">{onDevice}</span><span className="tile-l">tasks on this device</span></div>
      </div>
      <p className="muted small">Offline: RF receiving, material issue and counting (download your work on RF first). Online only:
        lists, approvals, transfers and replenishment.</p>
    </Section>
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
        <Attention n={receipts.data ? due.length : undefined} label="Receipts due today, not started" one="Receipt due today, not started" to="/receipts?status=NOT_STARTED"
                   oldest={oldestOf(due.map((r) => r.createdAt))} limitMin={LIMITS.receiptNotStarted} />
        <Attention n={receipts.data ? inProgress.length : undefined} label="Receipts in progress" one="Receipt in progress" to="/receipts?status=IN_PROGRESS" />
        <Attention n={returns.data ? rmas.length : undefined} label="Returns to receive" one="Return to receive" to="/returns" />
        <Attention n={dockLpns} label={`${plural(dockLpns, 'Pallet')} or loose stock waiting on dock`} to="/tasks?type=PUTAWAY"
                   oldest={oldestOf((dock.data ?? []).map((b) => b.receipt_date))} limitMin={LIMITS.dockStock} />
        <Attention n={yard.data?.inYard.length} label="Trailers in the yard" one="Trailer in the yard" to="/yard"
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
        <Attention n={tasks.data ? picks.length : undefined} label="Picks waiting" one="Pick waiting" to="/tasks?type=PICK&status=RELEASED"
                   detail={urgent.length ? `${urgent.length} urgent (cutoff near)` : undefined} />
        <Attention n={tasks.data ? replens.length : undefined} label="Replenishments waiting" one="Replenishment waiting" to="/tasks?type=REPLEN&status=RELEASED" />
        <Attention n={orders.data ? dueSoon.length : undefined} label="Orders due within 2 h" one="Order due within 2 h" to="/orders?status=RELEASED" />
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
        <Attention n={counts.data ? (byStatus.PENDING_APPROVAL ?? 0) : undefined} label="Count variances to approve" one="Count variance to approve" to="/counts"
                   detail={hasRole('INV_MANAGER', 'SUPERVISOR') ? undefined : 'an inventory manager or supervisor approves'} />
        <Attention n={counts.data ? (byStatus.OPEN ?? 0) + (byStatus.RECOUNT ?? 0) : undefined} label="Counts and recounts open" one="Count or recount open" to="/counts" />
        <Attention n={pis.data ? (openPi ? 1 : 0) : undefined} label={openPi ? `Physical inventory ${String(openPi.pi_no)} ${String(openPi.status).toLowerCase()}` : 'Physical inventories open'}
                   to="/counts" detail={openPi ? `${String(openPi.counted)} of ${String(openPi.locations)} counted` : undefined} />
        <Attention n={replens.data?.length} label="Replenishments open" one="Replenishment open" to="/replenishment" />
        <Attention n={heldRows?.length} label="Balances in QC or blocked" one="Balance in QC or blocked" to="/inventory"
                   detail={heldRows ? (() => { const u = heldRows.reduce((n, b) => n + Number(b.qty), 0); return `${fmtQty(u)} ${plural(u, 'unit')}` })() : undefined} />
        <Attention n={issues.data ? toApprove : undefined} label="Material issues to approve" one="Material issue to approve" to="/material-issues?status=REQUESTED" />
        <Attention n={issues.data ? toIssue : undefined} label="Material issues to issue" one="Material issue to issue" to="/material-issues?status=APPROVED,PARTIALLY_ISSUED" />
        <Attention n={slotting.data ? suggestions : undefined} label="Slotting suggestions" one="Slotting suggestion" to="/slotting" />
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
  const labor = useLoad(() => get<{ activeOperators: number; operators: { current?: { overStandard: boolean } }[]; backlog: { standardHours: number }[]
    pickWorkByOrder?: { orderRef: string; openPicks: number; standardMinutes: number }[] }>(`/api/v1/sites/${site}/tasks/labor?hours=8`), [site])
  const waves = useLoad(() => get<Row[]>(`/api/v1/sites/${site}/outbound/waves`), [site])
  const counts = useLoad(() => get<Row[]>(`/api/v1/sites/${site}/inventory/counts?status=PENDING_APPROVAL`), [site])
  const issues = useLoad(() => get<Row[]>(`/api/v1/sites/${site}/inventory/material-issues?status=REQUESTED`), [site])

  const notStartedRows = receipts.data?.filter((r) => r.status === 'NOT_STARTED') ?? []
  const failedTimes = [...(receipts.data ?? []).filter((r) => r.status === 'POSTING_FAILED').map((r) => r.updatedAt),
    ...(returns.data ?? []).filter((r) => r.status === 'POSTING_FAILED').map((r) => r.updated_at)]
  const staleRows = (tasks.data ?? []).filter((t) => t.status === 'ASSIGNED' && minutesSince(t.assignedAt) > LIMITS.taskAssigned)
  const stale = tasks.data ? staleRows.length : undefined
  const staleDetail = Object.entries(countBy(staleRows, (t) => `${t.taskType.toLowerCase()} (${t.assignedTo ?? '?'})`))
    .map(([k, n]) => (n > 1 ? `${n} × ${k}` : k)).join(', ')
  const openOrders = orders.data?.filter((o) => !['SHIPPED', 'CONFIRMED', 'CANCELLED', 'SHIP_ERROR'].includes(String(o.status)))
  const backorderedLines = openOrders?.reduce((n, o) => n + Number(o.lines_short ?? 0), 0)
  const shortOrders = (openOrders ?? []).filter((o) => Number(o.lines_short ?? 0) > 0)
  const lateAppts = yard.data?.late ?? []
  const dueSoon = openOrders?.filter((o) => ['RELEASED', 'POOLED', 'BACKORDERED'].includes(String(o.status))
    && minutesUntil(o.cutoff_at ?? o.planned_gi_utc) < LIMITS.cutoffSoon)
  const postingFailed = receipts.data && returns.data ? failedTimes.length : undefined
  const shipErrors = orders.data?.filter((o) => o.status === 'SHIP_ERROR').length
  const dockAvailable = dock.data?.filter((b) => b.stock_status === 'AVAILABLE')
  const dockQty = dockAvailable?.reduce((n, b) => n + Number(b.qty), 0)
  const dockLpns = dockAvailable ? new Set(dockAvailable.map((b) => `${String(b.location_id)}/${String(b.lpn_id)}`)).size : undefined
  // ADR-0025: stock that already has a putaway waits for RF; "Create putaways" never makes a second task.
  const putawayLpns = new Set((tasks.data ?? []).filter((t) => t.taskType === 'PUTAWAY' && ['RELEASED', 'ASSIGNED', 'EXCEPTION'].includes(t.status))
    .map((t) => t.lpnId))
  const dockWithTask = dockAvailable ? new Set(dockAvailable.filter((b) => b.lpn_id && putawayLpns.has(String(b.lpn_id))).map((b) => String(b.lpn_id))).size : 0
  const dockWithoutTask = (dockLpns ?? 0) - dockWithTask
  const overStandard = labor.data?.operators.filter((o) => o.current?.overStandard).length
  const atRisk = labor.data && orders.data
    ? cutoffForecast(orders.data, labor.data.pickWorkByOrder ?? [], labor.data.activeOperators).filter((c) => c.atRisk) : undefined
  const backlogHours = labor.data?.backlog.reduce((n, b) => n + b.standardHours, 0)
  const openWaves = (waves.data ?? []).filter((w) => w.status === 'PLANNED' || w.status === 'HELD')
  const approvals = counts.data && issues.data ? counts.data.length + issues.data.length : undefined
  const later = (...loads: { reload: () => void }[]) => setTimeout(() => loads.forEach((l) => l.reload()), 2500)
  // ADR-0025: store replenishment this site should send.
  const recs = useLoad(() => get<Recommendation[]>('/api/v1/network/replenishment'), [site])
  const toSend = (recs.data ?? []).filter((r) => r.recommended && r.sourceSite === site)
  // ADR-0026: stores at CRITICAL or HIGH stockout risk, or below min + safety without usage history.
  const shortages = (recs.data ?? []).filter((r) => r.shortage)
  const critical = shortages.filter((r) => r.risk === 'CRITICAL').length
  const acceptAll: TileAction = {
    label: 'Accept', confirm: `Create ${toSend.length} transfer(s) from ${site}: ${toSend.map((r) => `${r.itemNo} × ${fmtQty(r.qty)} → ${r.siteId}`).join(', ')}?`,
    run: async () => {
      const made: string[] = []
      const refused: string[] = []
      for (const r of toSend) {
        try {
          made.push(await acceptRecommendation(r))
        } catch (e) {
          refused.push(`${r.siteId} ${r.itemNo}: ${e instanceof Error ? e.message : String(e)}`)
        }
      }
      recs.reload()
      return `${made.length} transfer(s) created${made.length ? `: ${made.join(', ')}` : ''}${refused.length ? `; refused: ${refused.join('; ')}` : ''}`
    },
  }

  // One supervisor action per tile (ADR-0024); each confirms first and reports what it did.
  const sweepDock: TileAction = {
    label: 'Create putaways', confirm: 'Create a putaway task for every pallet and loose quantity at the dock?',
    run: async () => {
      // Dock sweep (ADR-0020): loose stock is put on an LPN first; its putaway follows.
      const r = await post<{ putawaysCreated: number; lpnsCreated: number; failed: string[] }>(`/api/v1/sites/${site}/tasks/sweep-dock`)
      later(dock, tasks)
      return `${r.putawaysCreated} ${plural(r.putawaysCreated, 'putaway')} created, ${r.lpnsCreated} loose ${plural(r.lpnsCreated, 'quantity', 'quantities')} put on an LPN`
        + (r.failed.length ? `; not done: ${r.failed.join(', ')}` : '')
    },
  }
  const unassignStale: TileAction = {
    label: 'Unassign', confirm: `Put the ${plural(stale ?? 0, 'task')} assigned over ${LIMITS.taskAssigned} min back in the queue (${staleDetail})?`,
    run: async () => {
      const r = await post<Task[]>(`/api/v1/sites/${site}/tasks/unassign-stale?minutes=${LIMITS.taskAssigned}`)
      tasks.reload()
      return `${r.length} ${plural(r.length, 'task')} back in the queue`
    },
  }
  const reallocate: TileAction = {
    label: 'Reallocate', confirm: `Try again now to allocate the short lines of ${shortOrders.length} ${plural(shortOrders.length, 'order')}?`,
    run: async () => {
      let qty = 0
      const failed: string[] = []
      for (const o of shortOrders) {
        try {
          qty += Number((await post<Row>(`/api/v1/sites/${site}/outbound/orders/${String(o.erp_doc_no)}/reallocate`)).recoveredQty ?? 0)
        } catch (e) {
          failed.push(`${String(o.erp_doc_no)}: ${e instanceof Error ? e.message : String(e)}`)
        }
      }
      orders.reload()
      return `${fmtQty(qty)} ${plural(qty, 'unit')} allocated` + (failed.length ? `; not done: ${failed.join('; ')}` : '')
    },
  }
  const dwelling = (yard.data?.inYard ?? []).filter((a) => minutesSince(a.checked_in_at) > LIMITS.trailerDwell)
  const checkOut: TileAction = {
    label: 'Check out', confirm: `Check out ${dwelling.map((a) => `${String(a.appt_no)} (${String(a.trailer_no ?? '')})`).join(', ')}: in the yard over ${LIMITS.trailerDwell / 60} h?`,
    run: async () => {
      for (const a of dwelling) await post(`/api/v1/sites/${site}/yard/appointments/${String(a.appt_no)}/check-out`, {})
      yard.reload()
      return `${dwelling.length} ${plural(dwelling.length, 'trailer')} checked out`
    },
  }
  const noShow: TileAction = {
    label: 'Mark no-show', confirm: `Close ${lateAppts.map((a) => String(a.appt_no)).join(', ')} as no-show and free their doors?`,
    run: async () => {
      for (const a of lateAppts) await post(`/api/v1/sites/${site}/yard/appointments/${String(a.appt_no)}/no-show`)
      yard.reload()
      return `${lateAppts.length} ${plural(lateAppts.length, 'appointment')} closed as no-show`
    },
  }

  return (
    <>
      <Section title="Control tower" hint="What needs attention now; a tile turns red when its oldest item is past its limit.">
        <AskBox site={site} />
        <ErrorBox error={receipts.error ?? orders.error ?? returns.error ?? dock.error ?? tasks.error ?? recs.error} />
        <div className="tiles">
          <Attention n={receipts.data ? notStartedRows.length : undefined} label="Receipts not started" one="Receipt not started" to="/receipts?status=NOT_STARTED"
                     oldest={oldestOf(notStartedRows.map((r) => r.createdAt))} limitMin={LIMITS.receiptNotStarted} />
          <Attention n={backorderedLines} label="Order lines short" one="Order line short" to="/orders?status=BACKORDERED"
                     detail="Unallocated quantity on open orders; recovered when stock is put away" action={reallocate} />
          <Attention n={dueSoon?.length} label="Orders due within 2 h" one="Order due within 2 h" to="/orders?status=RELEASED"
                     detail="Carrier cutoff or planned goods issue" />
          <Attention n={postingFailed} label="ERP posting failed" one="ERP posting failed" to="/receipts?status=POSTING_FAILED"
                     detail="Receipts and returns the ERP rejected; repost after the fix"
                     oldest={oldestOf(failedTimes)} limitMin={LIMITS.postingFailed} />
          <Attention n={shipErrors} label="Goods issue failed" to="/orders?status=SHIP_ERROR"
                     oldest={oldestOf((orders.data ?? []).filter((o) => o.status === 'SHIP_ERROR').map((o) => o.updated_at))}
                     limitMin={LIMITS.postingFailed} />
          <Attention n={stale} label={`Tasks assigned > ${LIMITS.taskAssigned} min`} one={`Task assigned > ${LIMITS.taskAssigned} min`} to="/tasks?status=ASSIGNED"
                     detail={staleDetail || 'Assigned to an operator but not confirmed'}
                     oldest={oldestOf(staleRows.map((t) => t.assignedAt))} limitMin={LIMITS.taskAssigned} action={unassignStale} />
          <Attention n={dockLpns} label={`${plural(dockLpns, 'Pallet')} or loose stock waiting on dock`} to="/tasks?type=PUTAWAY"
                     detail={dockQty === undefined ? undefined : `${fmtQty(dockQty)} ${plural(dockQty, 'unit')} at dock / receiving, not yet allocable`
                       + (dockWithTask ? `; ${dockWithTask} already ${dockWithTask === 1 ? 'has a putaway' : 'have putaways'}: confirm on RF` : '')}
                     oldest={oldestOf((dockAvailable ?? []).map((b) => b.receipt_date))} limitMin={LIMITS.dockStock} action={dockWithoutTask > 0 ? sweepDock : undefined} />
          <Attention n={yard.data?.inYard.length} label="Trailers in the yard" one="Trailer in the yard" to="/yard"
                     detail="Dwell since gate check-in"
                     oldest={oldestOf((yard.data?.inYard ?? []).map((a) => a.checked_in_at))} limitMin={LIMITS.trailerDwell}
                     action={dwelling.length ? checkOut : undefined} />
          <Attention n={recs.data ? shortages.length : undefined} label="Store shortages" one="Store shortage"
                     to="/store-replenishment?shortage=1"
                     oldest={critical ? 1e9 : undefined} limitMin={critical ? 0 : undefined}
                     detail={shortages.length ? `${critical} critical; ` + shortages.slice(0, 3).map((r) => `${r.siteId} ${r.itemNo} (${r.stockoutRiskText.split(':')[0]})`).join(', ')
                       : 'No store at CRITICAL or HIGH stockout risk'} />
          <Attention n={recs.data ? toSend.length : undefined} label="Store replenishments to send" one="Store replenishment to send"
                     to={`/store-replenishment?source=${site}`} action={acceptAll}
                     detail={toSend.length ? toSend.slice(0, 3).map((r) => `${r.siteId}: ${r.itemNo} × ${fmtQty(r.qty)} by ${String(r.requiredDate)}`).join('; ')
                       : 'Recommended transfers from this site to its stores'} />
          <Attention n={yard.data ? lateAppts.length : undefined} label="Appointments late" one="Appointment late" to="/yard"
                     detail={lateAppts.length ? lateAppts.map((a) => String(a.appt_no)).join(', ') : 'Scheduled, not arrived 15 min after the start'}
                     oldest={oldestOf(lateAppts.map((a) => a.scheduled_start))} limitMin={15} action={noShow} />
        </div>
      </Section>
      <Section title="People, waves and approvals">
        <div className="tiles">
          <Attention n={labor.data?.activeOperators} label="Operators active" one="Operator active" to="/labor"
                     detail={backlogHours === undefined ? undefined : `${backlogHours.toFixed(1)} h of work waiting (standard)`} />
          <Attention n={overStandard} label="Tasks over their standard" one="Task over its standard" to="/labor" />
          <Attention n={atRisk?.length} label="Cutoffs at risk" one="Cutoff at risk" to="/labor"
                     detail={atRisk?.length ? `${atRisk[0].orders.join(', ')}: needs ${Number.isFinite(atRisk[0].operatorsNeeded) ? atRisk[0].operatorsNeeded : 'more'} operator(s)` : 'Open pick work vs. the people on the floor'} />
          <Attention n={waves.data ? openWaves.length : undefined} label="Waves planned or held" one="Wave planned or held" to="/waves"
                     detail={openWaves.some((w) => w.status === 'HELD') ? `${openWaves.filter((w) => w.status === 'HELD').length} held` : undefined} />
          <Attention n={approvals} label="Approvals waiting" one="Approval waiting" to="/counts"
                     detail={counts.data && issues.data ? `${counts.data.length} count variance(s), ${issues.data.length} material issue(s)` : undefined} />
        </div>
      </Section>
      <div className="grid">
        <Card title="Receipts"><ErrorBox error={receipts.error} /><Tiles rows={receipts} status={(r: ReceiptSummary) => r.status} link="/receipts" /></Card>
        <Card title="Outbound orders"><ErrorBox error={orders.error} /><Tiles rows={orders} status={(r: Row) => String(r.status)} link="/orders" /></Card>
        <Card title="Tasks"><ErrorBox error={tasks.error} /><Tiles rows={tasks} status={(t: Task) => t.status} link="/tasks" /></Card>
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
  // ADR-0024: billing events without a rate this month are shown as unrated, never as a silent 0.00.
  const month = new Date(Date.UTC(new Date().getUTCFullYear(), new Date().getUTCMonth(), 1)).toISOString()
  const billing = useLoad(() => get<Row[]>(`/api/v1/sites/${site}/inventory/billing/summary?from=${month}&to=${new Date(Date.now() + 86_400_000).toISOString()}`), [site])
  const unrated = billing.data?.reduce((n, r) => n + Number(r.unpriced ?? 0), 0)
  const down = ops.data?.filter((s) => s.health !== 'UP')
  const dead = ops.data?.reduce((n, s) => n + (s.dlq?.messages.length ?? 0), 0)
  const backlog = ops.data?.reduce((n, s) => n + (s.outbox?.pending ?? 0), 0)
  const oldest = ops.data ? Math.max(0, ...ops.data.map((s) => s.outbox?.oldestPendingSeconds ?? 0)) / 60 : undefined
  const erpFailed = receipts.data && orders.data && returns.data
    ? receipts.data.length + orders.data.length + returns.data.filter((r) => r.status === 'POSTING_FAILED').length : undefined
  return (
    <Section title="Integration and platform" hint="Service health, messages not delivered, and ERP postings that failed.">
      <div className="tiles">
        <Attention n={down?.length} label="Services not healthy" one="Service not healthy" to="/operations"
                   detail={down?.length ? down.map((s) => s.svc).join(', ') : 'all six up'} />
        <Attention n={dead} label="Dead letters" one="Dead letter" to="/operations" detail="Messages a service could not process; replay after the fix" />
        <Attention n={backlog} label="Outbox backlog" to="/operations" oldest={backlog ? oldest : undefined} limitMin={5} />
        <Attention n={erpFailed} label="ERP postings failed" one="ERP posting failed" to="/receipts?status=POSTING_FAILED"
                   detail="Receipts, returns and goods issues the ERP rejected" />
        <Attention n={unrated} label="Billing events unrated" one="Billing event unrated" to="/billing"
                   detail="This month, no rate for their owner and type: not in the totals until a rate is set" />
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
      <Card title="Receipts"><ErrorBox error={receipts.error} /><Tiles rows={receipts} status={(r: ReceiptSummary) => r.status} link="/receipts" /></Card>
      <Card title="Outbound orders"><ErrorBox error={orders.error} /><Tiles rows={orders} status={(r: Row) => String(r.status)} link="/orders" /></Card>
    </div>
  )
}
