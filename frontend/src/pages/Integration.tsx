import { useState } from 'react'
import { del, get, post, type Row } from '../api'
import { Badge, Card, ErrorBox, Field, Page, Success, Table, fmtDate, useAction, useLoad } from '../ui'

/**
 * Integration (ADR-0025): webhooks for partners beside the SAP IDoc interface. Each delivery is a signed JSON POST
 * (X-AstraWMS-Signature: sha256=HMAC(secret, timestamp + "." + body)), retried with backoff. HTTPS only, no private
 * addresses. The secret is shown once, when the subscription is created.
 */
interface Subs { events: string[]; subscriptions: Row[] }

export default function Integration() {
  const subs = useLoad(() => get<Subs>('/api/v1/integration/webhooks'), [])
  const [f, setF] = useState({ name: '', url: '', events: [] as string[] })
  const create = useAction(() => post<Row>('/api/v1/integration/webhooks', f))
  const remove = useAction((id: string) => del(`/api/v1/integration/webhooks/${id}`))
  const ping = useAction((id: string) => post<Row>(`/api/v1/integration/webhooks/${id}/ping`))
  const [open, setOpen] = useState<string>()
  const events = subs.data?.events ?? []
  return (
    <Page title="Integration">
      <ErrorBox error={subs.error ?? create.error ?? remove.error ?? ping.error} />
      <Card title="Webhooks">
        <Table rows={subs.data?.subscriptions} empty="No webhooks" onRow={(r) => setOpen(String(r.id))} columns={[
          { header: 'Name', cell: (r) => <strong>{String(r.name)}</strong> },
          { header: 'URL', cell: (r) => <code>{String(r.url)}</code> },
          { header: 'Events', cell: (r) => (r.events as string[]).join(', ') },
          { header: 'Pending / failed', cell: (r) => `${String(r.pending)} / ${String(r.failed)}` },
          { header: 'Last delivered', cell: (r) => fmtDate(r.last_delivered_at) },
          { header: '', cell: (r) => (
            <div className="row tight" onClick={(e) => e.stopPropagation()}>
              <button className="small" onClick={async () => { if (await ping.run(String(r.id))) subs.reload() }}>Send test</button>
              <button className="small" onClick={async () => {
                if (window.confirm(`Delete webhook ${String(r.name)}? Pending deliveries are dropped.`) && await remove.run(String(r.id))) subs.reload()
              }}>Delete</button>
            </div>) },
        ]} />
        <Success>{ping.result && 'Test event queued; it is sent within seconds'}</Success>
      </Card>
      {open && <Deliveries id={open} />}
      <Card title="Add a webhook">
        <div className="row">
          <Field label="Name"><input value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} size={16} /></Field>
          <Field label="HTTPS URL"><input value={f.url} onChange={(e) => setF({ ...f, url: e.target.value })} size={40} placeholder="https://partner.example.com/hooks/wms" /></Field>
        </div>
        <div className="row">
          {events.map((ev) => (
            <label key={ev} className="check">
              <input type="checkbox" checked={f.events.includes(ev)}
                     onChange={(e) => setF({ ...f, events: e.target.checked ? [...f.events, ev] : f.events.filter((x) => x !== ev) })} /> {ev}
            </label>
          ))}
        </div>
        <button className="primary" disabled={create.busy || !f.name || !f.url || f.events.length === 0}
                onClick={async () => { if (await create.run()) { subs.reload(); setF({ name: '', url: '', events: [] }) } }}>Add webhook</button>
        {create.result && (
          <div className="alert">Signing secret (shown once, store it with the receiver): <code>{String(create.result.secret)}</code></div>
        )}
      </Card>
      <Card title="Task API for devices">
        <p className="muted">AMRs, voice, pick-to-light, scales and printers use the same task API as RF, not new workflows:
          see <code>docs/integration/task-api.md</code> in the repository.</p>
      </Card>
    </Page>
  )
}

function Deliveries({ id }: { id: string }) {
  const list = useLoad(() => get<Row[]>(`/api/v1/integration/webhooks/${id}/deliveries`), [id])
  return (
    <Card title="Recent deliveries" actions={<button className="small" onClick={list.reload}>Refresh</button>}>
      <ErrorBox error={list.error} />
      <Table rows={list.data} empty="No deliveries yet" columns={[
        { header: 'When', cell: (d) => fmtDate(d.created_at) },
        { header: 'Event', cell: (d) => String(d.event) },
        { header: 'Status', cell: (d) => <Badge value={String(d.status)} /> },
        { header: 'Attempts', cell: (d) => String(d.attempts), align: 'right' },
        { header: 'Last answer', cell: (d) => String(d.last_status ?? d.last_error ?? '') },
        { header: 'Delivered', cell: (d) => fmtDate(d.delivered_at) },
      ]} />
    </Card>
  )
}
