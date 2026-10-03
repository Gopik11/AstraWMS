import { useState } from 'react'
import { get, post, put, type Row } from '../api'
import { useAuth } from '../auth'
import { Card, ErrorBox, Field, Page, Success, Table, fmtDate, fmtQty, useAction, useLoad, useSite } from '../ui'

function monthStart(offset = 0): string {
  const d = new Date()
  return new Date(d.getFullYear(), d.getMonth() + offset, 1).toISOString().slice(0, 10)
}

/** An amount; null is an event without a rate: "unrated", never a silent 0.00 (ADR-0024). */
const money = (v: unknown, cur?: unknown) => (v == null ? 'unrated' : `${Number(v).toFixed(2)}${cur ? ` ${String(cur)}` : ''}`)

/**
 * 3PL billing (ADR-0021): billable events from the inventory ledger (receipts, picks, returns), storage days and
 * value-added services, priced by owner rates. "Capture now" brings the ledger up to date first.
 */
export default function Billing() {
  const site = useSite()
  const { hasRole } = useAuth()
  const base = `/api/v1/sites/${site}/inventory/billing`
  const [period, setPeriod] = useState({ from: monthStart(), to: monthStart(1), owner: '' })
  const range = `from=${new Date(period.from).toISOString()}&to=${new Date(period.to).toISOString()}${period.owner ? `&ownerId=${period.owner}` : ''}`
  const summary = useLoad(() => get<Row[]>(`${base}/summary?${range}`), [base, range])
  const events = useLoad(() => get<Row[]>(`${base}/events?${range}`), [base, range])
  const capture = useAction(() => post<Row>(`${base}/capture`))
  const total = (summary.data ?? []).reduce((n, r) => n + Number(r.amount ?? 0), 0)
  const unrated = (summary.data ?? []).reduce((n, r) => n + Number(r.unpriced ?? 0), 0)

  return (
    <Page title="Billing" actions={
      <button className="primary" disabled={capture.busy}
              onClick={async () => { if (await capture.run()) { summary.reload(); events.reload() } }}>Capture now</button>
    }>
      <ErrorBox error={capture.error ?? summary.error} />
      <Success>{capture.result && `${String(capture.result.operationEvents)} operation event(s), ${String(capture.result.storageEvents)} storage day(s) captured`}</Success>
      <Card title="Period">
        <div className="row">
          <Field label="From"><input type="date" value={period.from} onChange={(e) => setPeriod({ ...period, from: e.target.value })} /></Field>
          <Field label="To (exclusive)"><input type="date" value={period.to} onChange={(e) => setPeriod({ ...period, to: e.target.value })} /></Field>
          <Field label="Owner"><input value={period.owner} onChange={(e) => setPeriod({ ...period, owner: e.target.value.toUpperCase() })} size={8} /></Field>
        </div>
      </Card>
      <Card title={`Totals: ${total.toFixed(2)}${unrated ? ` + ${unrated} unrated event(s)` : ''}`}>
        {unrated > 0 && <p className="text-late">{unrated} event(s) have no rate and are not in the total: set a rate below
          (it prices events captured from then on).</p>}
        <Table rows={summary.data} empty="No billable events in this period (capture first?)" columns={[
          { header: 'Owner', cell: (r) => String(r.owner_id) },
          { header: 'Event', cell: (r) => `${String(r.event_type)}${r.service ? ` · ${String(r.service)}` : ''}` },
          { header: 'Events', cell: (r) => String(r.events), align: 'right' },
          { header: 'Units', cell: (r) => fmtQty(r.units), align: 'right' },
          { header: 'Lines', cell: (r) => String(r.lines), align: 'right' },
          { header: 'LPNs', cell: (r) => String(r.lpns), align: 'right' },
          { header: 'Amount', cell: (r) => money(r.amount, r.currency), align: 'right' },
          { header: '', cell: (r) => (Number(r.unpriced) > 0 ? <span className="text-late">{String(r.unpriced)} without a rate</span> : '') },
        ]} />
        <p className="muted">Storage is the stock on hand at the end of each UTC day (unit-days or LPN-days). Events are priced
          when captured; changing a rate does not reprice history.</p>
      </Card>
      <Vas base={base} onDone={() => { summary.reload(); events.reload() }} />
      <Card title="Events">
        <Table rows={events.data} empty="No events" columns={[
          { header: 'When', cell: (e) => fmtDate(e.occurred_at) },
          { header: 'Owner', cell: (e) => String(e.owner_id) },
          { header: 'Event', cell: (e) => `${String(e.event_type)}${e.service ? ` · ${String(e.service)}` : ''}` },
          { header: 'What', cell: (e) => String(e.description ?? e.ref) },
          { header: 'Units', cell: (e) => fmtQty(e.units), align: 'right' },
          { header: 'LPNs', cell: (e) => String(e.lpns), align: 'right' },
          { header: 'Rate', cell: (e) => (e.rate == null ? '—' : `${String(e.rate)} / ${String(e.basis).toLowerCase()}`) },
          { header: 'Amount', cell: (e) => money(e.amount, e.currency), align: 'right' },
        ]} />
      </Card>
      <Rates base={base} canEdit={hasRole('SOLUTION_ADMIN')} />
    </Page>
  )
}

function Vas({ base, onDone }: { base: string; onDone: () => void }) {
  const [f, setF] = useState({ ownerId: '', service: '', qty: '', ref: '', note: '' })
  const save = useAction(() => post<Row>(`${base}/vas`, { ...f, qty: Number(f.qty), ref: f.ref || null, note: f.note || null }))
  return (
    <Card title="Value-added service">
      <div className="row">
        <Field label="Owner"><input value={f.ownerId} onChange={(e) => setF({ ...f, ownerId: e.target.value.toUpperCase() })} size={8} /></Field>
        <Field label="Service" hint="e.g. LABEL, KIT"><input value={f.service} onChange={(e) => setF({ ...f, service: e.target.value.toUpperCase() })} size={8} /></Field>
        <Field label="Qty"><input type="number" min={0} step="any" value={f.qty} onChange={(e) => setF({ ...f, qty: e.target.value })} size={5} /></Field>
        <Field label="Reference" hint="Optional; one charge per reference"><input value={f.ref} onChange={(e) => setF({ ...f, ref: e.target.value })} size={10} /></Field>
        <Field label="Note"><input value={f.note} onChange={(e) => setF({ ...f, note: e.target.value })} size={16} /></Field>
        <button disabled={save.busy || !f.ownerId || !f.service || !f.qty} onClick={async () => { if (await save.run()) onDone() }}>Record</button>
      </div>
      <ErrorBox error={save.error} />
      <Success>{save.result && `Recorded: ${money(save.result.amount, save.result.currency)}`}</Success>
    </Card>
  )
}

function Rates({ base, canEdit }: { base: string; canEdit: boolean }) {
  const rates = useLoad(() => get<Row[]>(`${base}/rates`), [base])
  const [f, setF] = useState({ ownerId: '', eventType: 'RECEIPT', service: '', basis: 'LPN', rate: '', currency: 'USD' })
  const save = useAction(() => put(`${base}/rates`, { ...f, ownerId: f.ownerId || null, rate: Number(f.rate) }))
  return (
    <Card title="Rates">
      <Table rows={rates.data} empty="No rates: events are captured at zero" columns={[
        { header: 'Owner', cell: (r) => String(r.owner_id || 'all owners') },
        { header: 'Event', cell: (r) => `${String(r.event_type)}${r.service ? ` · ${String(r.service)}` : ''}` },
        { header: 'Rate', cell: (r) => `${String(r.rate)} ${String(r.currency)} / ${String(r.basis).toLowerCase()}${r.event_type === 'STORAGE' ? '-day' : ''}` },
        { header: 'Set by', cell: (r) => String(r.updated_by) },
      ]} />
      {canEdit && (
        <div className="row">
          <Field label="Owner" hint="Blank = all"><input value={f.ownerId} onChange={(e) => setF({ ...f, ownerId: e.target.value.toUpperCase() })} size={8} /></Field>
          <Field label="Event">
            <select value={f.eventType} onChange={(e) => setF({ ...f, eventType: e.target.value })}>
              {['RECEIPT', 'PICK', 'RETURN', 'STORAGE', 'VAS'].map((t) => <option key={t}>{t}</option>)}
            </select>
          </Field>
          {f.eventType === 'VAS' && <Field label="Service"><input value={f.service} onChange={(e) => setF({ ...f, service: e.target.value.toUpperCase() })} size={8} /></Field>}
          <Field label="Per">
            <select value={f.basis} onChange={(e) => setF({ ...f, basis: e.target.value })}>
              <option value="UNIT">unit</option><option value="LPN">LPN / pallet</option>
              {f.eventType !== 'STORAGE' && <><option value="LINE">line</option><option value="EVENT">event</option></>}
            </select>
          </Field>
          <Field label="Rate"><input type="number" min={0} step="0.0001" value={f.rate} onChange={(e) => setF({ ...f, rate: e.target.value })} size={6} /></Field>
          <Field label="Currency"><input value={f.currency} onChange={(e) => setF({ ...f, currency: e.target.value.toUpperCase() })} size={4} /></Field>
          <button className="primary" disabled={save.busy || f.rate === ''} onClick={async () => { if (await save.run()) rates.reload() }}>Save rate</button>
        </div>
      )}
      <ErrorBox error={rates.error ?? save.error} />
    </Card>
  )
}
