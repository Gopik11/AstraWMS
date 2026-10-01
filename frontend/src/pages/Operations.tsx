import { useState } from 'react'
import { get, post } from '../api'
import { Badge, Card, ErrorBox, Page, Success, Table, fmtDate, useAction, useLoad } from '../ui'

const SERVICES = ['master-data-service', 'inventory-service', 'inbound-service', 'task-service', 'outbound-service', 'sap-adapter']

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
  error: string
  replayedBy?: string | null
  replayedAt?: string | null
}

interface Listing { service: string; messages: DeadLetter[]; unattributed: number }
interface Outbox { pending: number; oldestPendingSeconds: number | null }
interface ServiceState { name: string; health: string; outbox?: Outbox; dlq?: Listing; error?: string }

async function health(svc: string): Promise<string> {
  try {
    const r = await fetch(`/health/${svc}`, { cache: 'no-store' })
    return ((await r.json()) as { status?: string }).status ?? `HTTP ${r.status}`
  } catch {
    return 'DOWN'
  }
}

async function load(): Promise<ServiceState[]> {
  return Promise.all(SERVICES.map(async (name) => {
    const state: ServiceState = { name, health: await health(name) }
    try {
      const [outbox, dlq] = await Promise.all([
        get<Outbox>(`/api/v1/ops/${name}/outbox`),
        get<Listing>(`/api/v1/ops/${name}/dlq`),
      ])
      Object.assign(state, { outbox, dlq })
    } catch (e) {
      state.error = e instanceof Error ? e.message : String(e)
    }
    return state
  }))
}

/** Platform operations for this tenant (ADR-0018): service health, outbox backlog, dead letters with replay. */
export default function Operations() {
  const states = useLoad(load, [])
  const [replayed, setReplayed] = useState<string>()
  const replay = useAction((svc: string, m: DeadLetter) =>
    post<DeadLetter>(`/api/v1/ops/${svc}/dlq/replay`, { topic: m.dlqTopic, partition: m.partition, offset: m.offset }))
  const dead = (states.data ?? []).flatMap((s) => (s.dlq?.messages ?? []).map((m) => ({ ...m, service: s.name })))

  return (
    <Page title="Operations" actions={<button onClick={() => states.reload()}>Refresh</button>}>
      <Card title="Services">
        <ErrorBox error={states.error} />
        <Table rows={states.data} empty="Loading…" columns={[
          { header: 'Service', cell: (s) => s.name },
          { header: 'Health', cell: (s) => <Badge value={s.health} /> },
          { header: 'Outbox backlog', cell: (s) => (s.outbox ? String(s.outbox.pending) : ''), align: 'right' },
          { header: 'Oldest pending', cell: (s) => (s.outbox?.oldestPendingSeconds != null ? `${s.outbox.oldestPendingSeconds} s` : '') },
          { header: 'Dead letters', cell: (s) => (s.dlq ? String(s.dlq.messages.filter((m) => !m.replayedBy).length) : ''), align: 'right' },
          { header: 'Note', cell: (s) => s.error ?? (s.dlq?.unattributed ? `${s.dlq.unattributed} unreadable message(s) without tenant` : '') },
        ]} />
      </Card>
      <Card title="Dead-lettered messages">
        <p className="muted">Messages a service could not process after retries. Fix the cause (master data, configuration, a
          deployed bug fix), then replay: the message goes back only to the consumer that failed it.</p>
        <Table rows={dead} empty="No dead letters" columns={[
          { header: 'When', cell: (m) => fmtDate(m.deadLetteredAt) },
          { header: 'Service', cell: (m) => m.service },
          { header: 'Message', cell: (m) => `${m.messageType ?? '?'} · ${m.businessKey ?? m.messageId ?? ''}` },
          { header: 'Topic', cell: (m) => `${m.originalTopic} (${m.partition}@${m.offset})` },
          { header: 'Error', cell: (m) => <span className="error-text">{m.error}</span> },
          {
            header: 'Replay', cell: (m) => (m.replayedBy
              ? `by ${m.replayedBy} · ${fmtDate(m.replayedAt)}`
              : <button disabled={replay.busy} onClick={async () => {
                  if (await replay.run(m.service, m)) { setReplayed(`${m.messageType ?? 'Message'} replayed`); states.reload() }
                }}>Replay</button>),
          },
        ]} />
        <ErrorBox error={replay.error} />
        <Success>{replayed}</Success>
      </Card>
    </Page>
  )
}
