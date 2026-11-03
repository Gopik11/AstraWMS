import { useCallback, useEffect, useRef, useState } from 'react'
import { ApiError, get, post } from '../api'
import { age, describe, overall, runCheck, type CheckState } from '../opsState'
import { Badge, Card, ErrorBox, Page, Success, Table, fmtDate, useAction } from '../ui'

const SERVICES = ['master-data-service', 'inventory-service', 'inbound-service', 'task-service', 'outbound-service', 'sap-adapter']
const HEALTH_TIMEOUT = 5_000
const STATUS_TIMEOUT = 8_000
/** The service reads dead letters for at most 6 s; a little more for the answer to arrive. */
const DLQ_TIMEOUT = 12_000
const AUTO_REFRESH = 30_000

interface DeadLetter {
  dlqTopic: string
  partition: number
  offset: number
  originalTopic: string
  originalGroup: string
  messageType?: string
  messageId?: string
  businessKey?: string
  deadLetteredAt: string
  createdAt?: string | null
  error: string
  replayedBy?: string | null
  replayedAt?: string | null
  replay?: 'SAFE' | 'REPLAYED' | 'ALREADY_PROCESSED' | 'TOO_OLD'
}

interface Listing { service: string; messages: DeadLetter[]; unattributed: number; complete: boolean; readMillis: number }
interface Status { pending: number; oldestPendingSeconds: number | null; pausedTopics: Record<string, string>; at: string }
interface ServiceChecks { health?: CheckState<string>; status?: CheckState<Status>; dlq?: CheckState<Listing> }

async function health(svc: string): Promise<string> {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), HEALTH_TIMEOUT)
  try {
    const r = await fetch(`/health/${svc}`, { cache: 'no-store', signal: controller.signal })
    const body = (await r.json().catch(() => ({}))) as { status?: string }
    return body.status ?? `HTTP ${r.status}`
  } catch {
    if (controller.signal.aborted) throw new ApiError(0, { code: 'TIMEOUT', detail: `No answer within ${HEALTH_TIMEOUT / 1000} s` })
    return 'DOWN'
  } finally {
    clearTimeout(timer)
  }
}

/**
 * Platform operations for this tenant (ADR-0018, ADR-0029): health, outbox backlog and paused topics, dead letters
 * with replay. Each service and each check loads on its own with a timeout, so one slow service never blanks the page.
 */
export default function Operations() {
  const [checks, setChecks] = useState<Record<string, ServiceChecks>>({})
  const [refreshedAt, setRefreshedAt] = useState<number>()
  const [auto, setAuto] = useState(true)
  const [, setTick] = useState(0)
  const generation = useRef(0)

  const refresh = useCallback(() => {
    const gen = ++generation.current
    const started = Date.now()
    setRefreshedAt(started)
    const set = (svc: string, part: keyof ServiceChecks, value: CheckState<never> | CheckState<unknown>) => {
      if (gen === generation.current) setChecks((c) => ({ ...c, [svc]: { ...c[svc], [part]: value } }))
    }
    for (const svc of SERVICES) {
      set(svc, 'health', { kind: 'loading', startedAt: started })
      set(svc, 'status', { kind: 'loading', startedAt: started })
      set(svc, 'dlq', { kind: 'loading', startedAt: started })
      void runCheck(() => health(svc)).then((r) => set(svc, 'health', r))
      void runCheck(() => get<Status>(`/api/v1/ops/${svc}/status`, { timeoutMs: STATUS_TIMEOUT })).then((r) => set(svc, 'status', r))
      void runCheck(() => get<Listing>(`/api/v1/ops/${svc}/dlq`, { timeoutMs: DLQ_TIMEOUT })).then((r) => set(svc, 'dlq', r))
    }
  }, [])

  useEffect(() => { refresh() }, [refresh])
  useEffect(() => {
    if (!auto) return undefined
    const t = setInterval(refresh, AUTO_REFRESH)
    return () => clearInterval(t)
  }, [auto, refresh])
  useEffect(() => {          // keeps "x s ago" current
    const t = setInterval(() => setTick((n) => n + 1), 5_000)
    return () => clearInterval(t)
  }, [])

  const [replayed, setReplayed] = useState<string>()
  const replay = useAction((svc: string, m: DeadLetter, force: boolean, reason?: string) =>
    post<DeadLetter>(`/api/v1/ops/${svc}/dlq/replay`, { topic: m.dlqTopic, partition: m.partition, offset: m.offset, force, reason }))
  const doReplay = async (svc: string, m: DeadLetter) => {
    let reason: string | undefined
    if (m.replay === 'TOO_OLD') {
      reason = window.prompt('This message is older than the inbox retention: its record of being processed may be gone, so '
        + 'a replay could apply it twice. Check the business document first. Reason for replaying anyway:') ?? undefined
      if (!reason?.trim()) return
    }
    if (await replay.run(svc, m, m.replay === 'TOO_OLD', reason)) {
      setReplayed(`${m.messageType ?? 'Message'} replayed`)
      refresh()
    }
  }

  const rows = SERVICES.map((name) => ({ name, ...checks[name] }))
  const dead = rows.flatMap((s) => (s.dlq?.kind === 'ok' ? s.dlq.data.messages : []).map((m) => ({ ...m, service: s.name })))
  const incomplete = rows.filter((s) => s.dlq?.kind === 'ok' && !s.dlq.data.complete).map((s) => s.name)
  const dlqProblems = rows.filter((s) => s.dlq && s.dlq.kind !== 'ok' && s.dlq.kind !== 'loading')
  const checking = rows.some((s) => [s.health, s.status, s.dlq].some((c) => !c || c.kind === 'loading'))

  return (
    <Page title="Operations" actions={
      <>
        <label className="check"><input type="checkbox" checked={auto} onChange={(e) => setAuto(e.target.checked)} /> Refresh every 30 s</label>
        <button onClick={refresh}>Refresh</button>
      </>}>
      <Card title="Services">
        <p className="muted">{refreshedAt ? `Checked ${age(refreshedAt)}` : ''}{checking ? ' · checking…' : ''}</p>
        <Table rows={rows} columns={[
          { header: 'Service', cell: (s) => s.name },
          { header: 'State', cell: (s) => <Badge value={overall(s.health, s.status)} /> },
          { header: 'Health', cell: (s) => describe(s.health, (h) => h) + (s.health?.kind === 'ok' ? ` · ${s.health.ms} ms` : '') },
          { header: 'Outbox backlog', align: 'right', cell: (s) => describe(s.status, (st) =>
              `${st.pending}${st.oldestPendingSeconds != null ? ` (oldest ${st.oldestPendingSeconds} s)` : ''}`) },
          { header: 'Paused topics', cell: (s) => describe(s.status, (st) => Object.keys(st.pausedTopics ?? {}).join(', ') || 'none') },
          { header: 'Dead letters', align: 'right', cell: (s) => describe(s.dlq, (l) =>
              `${l.messages.filter((m) => !m.replayedBy).length}${l.complete ? '' : ' (partial)'} · ${l.readMillis} ms`) },
          { header: 'Note', cell: (s) => (s.dlq?.kind === 'ok' && s.dlq.data.unattributed
              ? `${s.dlq.data.unattributed} unreadable message(s) without tenant` : '') },
        ]} />
      </Card>
      <Card title="Dead-lettered messages">
        <p className="muted">Messages a service could not process after retries. Fix the cause (master data, configuration, a
          deployed bug fix), then replay: the message goes back only to the consumer that failed it.</p>
        {incomplete.length > 0 && <p className="error-text">Not every dead letter could be read in time for {incomplete.join(', ')}: the list may be incomplete. Refresh to read again.</p>}
        {dlqProblems.map((s) => <p key={s.name} className="error-text">{s.name}: dead letters {describe(s.dlq, () => '')}</p>)}
        <Table rows={dead} empty={checking ? 'Checking…' : 'No dead letters'} columns={[
          { header: 'When', cell: (m) => fmtDate(m.deadLetteredAt) },
          { header: 'Service', cell: (m) => m.service },
          { header: 'Message', cell: (m) => `${m.messageType ?? '?'} · ${m.businessKey ?? m.messageId ?? ''}` },
          { header: 'Topic', cell: (m) => `${m.originalTopic} (${m.partition}@${m.offset})` },
          { header: 'Error', cell: (m) => <span className="error-text">{m.error}</span> },
          {
            header: 'Replay', cell: (m) => {
              if (m.replayedBy) return `by ${m.replayedBy} · ${fmtDate(m.replayedAt)}`
              if (m.replay === 'ALREADY_PROCESSED') return 'processed since: nothing to replay'
              return <button disabled={replay.busy} onClick={() => void doReplay(m.service, m)}
                             title={m.replay === 'TOO_OLD' ? 'Older than the inbox retention: needs a reason' : undefined}>
                {m.replay === 'TOO_OLD' ? 'Replay (needs reason)' : 'Replay'}</button>
            },
          },
        ]} />
        <ErrorBox error={replay.error} />
        <Success>{replayed}</Success>
      </Card>
    </Page>
  )
}
