import { useState } from 'react'
import { Link } from 'react-router-dom'
import { del, get, post, put, type Row } from '../api'
import { useAuth } from '../auth'
import { Badge, Card, ErrorBox, Field, Page, Success, Table, fmtDate, useAction, useLoad, useSite } from '../ui'

interface Plan {
  orders: {
    erpDocNo: string; orderType: string; carrierScac?: string; plannedGoodsIssueUtc?: string; lines: number
    cutoffAt?: string; priority: number; shipComplete: boolean
  }[]
  orderCount: number
  lineCount: number
}

/**
 * Wave planning (§C.4, ADV-030): preview a wave from criteria, create it, release it. ADR-0021: the pool is taken by
 * carrier cutoff then priority; waves can be held; "release by cutoff" releases what is due in the next N minutes.
 */
export default function Waves() {
  const site = useSite()
  const base = `/api/v1/sites/${site}/outbound`
  const config = useLoad(() => get<{ releaseMode: string; timezone: string }>(`${base}/config`), [base])
  const waves = useLoad(() => get<Row[]>(`${base}/waves`), [base])
  const [criteria, setCriteria] = useState({
    carrierScac: '', orderType: '', goodsIssueBefore: '', cutoffWithinMinutes: '', maxOrders: '', maxLines: '',
  })
  const body = () => ({
    carrierScac: criteria.carrierScac || null,
    orderType: criteria.orderType || null,
    goodsIssueBefore: criteria.goodsIssueBefore ? new Date(criteria.goodsIssueBefore).toISOString() : null,
    cutoffWithinMinutes: criteria.cutoffWithinMinutes ? Number(criteria.cutoffWithinMinutes) : null,
    maxOrders: criteria.maxOrders ? Number(criteria.maxOrders) : null,
    maxLines: criteria.maxLines ? Number(criteria.maxLines) : null,
  })
  const plan = useAction(() => post<Plan>(`${base}/waves/plan`, body()))
  const create = useAction(() => post<Row>(`${base}/waves`, body()))
  const byCutoff = useAction(() => post<Row>(`${base}/waves/release-by-cutoff`, {
    carrierScac: criteria.carrierScac || null, withinMinutes: Number(criteria.cutoffWithinMinutes || 120),
  }))
  const release = useAction((waveNo: string) => post<Row>(`${base}/waves/${waveNo}/release`))
  const hold = useAction((waveNo: string, reason: string) => post<Row>(`${base}/waves/${waveNo}/hold`, { reason }))
  const unhold = useAction((waveNo: string) => post<Row>(`${base}/waves/${waveNo}/unhold`))
  const set = (k: keyof typeof criteria) => (e: { target: { value: string } }) => setCriteria({ ...criteria, [k]: e.target.value })

  return (
    <Page title="Waves">
      {config.data && config.data.releaseMode !== 'WAVE' && (
        <div className="alert">Site {site} releases orders on receipt (WAVELESS). Orders only wait for waves in WAVE
          mode; a solution administrator sets it under Master data. Pick priority still rises as carrier cutoffs near.</div>
      )}
      <Card title="Plan a wave">
        <div className="row">
          <Field label="Carrier"><input value={criteria.carrierScac} onChange={set('carrierScac')} size={6} /></Field>
          <Field label="Order type"><input value={criteria.orderType} onChange={set('orderType')} size={10} /></Field>
          <Field label="Goods issue before"><input type="datetime-local" value={criteria.goodsIssueBefore} onChange={set('goodsIssueBefore')} /></Field>
          <Field label="Cutoff within (min)" hint="Carrier cutoff in the next N minutes">
            <input type="number" min={1} value={criteria.cutoffWithinMinutes} onChange={set('cutoffWithinMinutes')} size={5} /></Field>
          <Field label="Max orders"><input type="number" min={1} value={criteria.maxOrders} onChange={set('maxOrders')} size={5} /></Field>
          <Field label="Max lines"><input type="number" min={1} value={criteria.maxLines} onChange={set('maxLines')} size={5} /></Field>
        </div>
        <div className="actions">
          <button onClick={() => void plan.run()} disabled={plan.busy}>Preview</button>
          <button className="primary" disabled={create.busy}
                  onClick={async () => { if (await create.run()) { plan.clear(); waves.reload() } }}>Create wave</button>
          <button disabled={byCutoff.busy} title="Plan and release at once every pooled order due within the minutes given (default 120)"
                  onClick={async () => { if (await byCutoff.run()) { plan.clear(); waves.reload() } }}>
            Release by cutoff ({criteria.cutoffWithinMinutes || 120} min)</button>
        </div>
        <ErrorBox error={plan.error ?? create.error ?? byCutoff.error} />
        <Success>{create.result ? `Wave ${String(create.result.wave_no)} created`
          : byCutoff.result ? `Wave ${String(byCutoff.result.wave_no)} released (${(byCutoff.result.orders as unknown[]).length} orders)` : null}</Success>
        {plan.result && (
          <>
            <p><strong>{plan.result.orderCount}</strong> orders, <strong>{plan.result.lineCount}</strong> lines (preview — nothing changed)</p>
            <Table rows={plan.result.orders} columns={[
              { header: 'Delivery', cell: (o) => <Link to={`/orders/${o.erpDocNo}`}>{o.erpDocNo}</Link> },
              { header: 'Type', cell: (o) => o.orderType },
              { header: 'Carrier', cell: (o) => o.carrierScac },
              { header: 'Cutoff', cell: (o) => fmtDate(o.cutoffAt) },
              { header: 'Goods issue', cell: (o) => fmtDate(o.plannedGoodsIssueUtc) },
              { header: 'Priority', cell: (o) => o.priority, align: 'right' },
              { header: 'Ship complete', cell: (o) => (o.shipComplete ? 'yes' : '') },
              { header: 'Lines', cell: (o) => o.lines, align: 'right' },
            ]} />
          </>
        )}
      </Card>
      <Card title="Waves">
        <ErrorBox error={waves.error ?? release.error ?? hold.error ?? unhold.error} />
        <Table rows={waves.data} empty="No waves yet" columns={[
          { header: 'Wave', cell: (w) => String(w.wave_no) },
          { header: 'Status', cell: (w) => <span title={String(w.hold_reason ?? '')}><Badge value={String(w.status)} />
              {w.hold_reason ? ` ${String(w.hold_reason)} (${String(w.held_by)})` : ''}</span> },
          { header: 'Orders', cell: (w) => String(w.orders), align: 'right' },
          { header: 'Earliest cutoff', cell: (w) => fmtDate(w.earliest_cutoff) },
          { header: 'Created', cell: (w) => `${fmtDate(w.created_at)} · ${String(w.created_by)}` },
          { header: 'Released', cell: (w) => (w.released_at ? `${fmtDate(w.released_at)} · ${String(w.released_by)}` : '') },
          {
            header: '',
            cell: (w) => (
              <div className="row">
                {w.status === 'PLANNED' && <>
                  <button className="primary small" disabled={release.busy}
                          onClick={async () => (await release.run(String(w.wave_no))) && waves.reload()}>Release</button>
                  <button className="small" disabled={hold.busy} onClick={async () => {
                    const reason = window.prompt(`Why is wave ${String(w.wave_no)} held?`)
                    if (reason && (await hold.run(String(w.wave_no), reason))) waves.reload()
                  }}>Hold</button>
                </>}
                {w.status === 'HELD' && (
                  <button className="small" disabled={unhold.busy}
                          onClick={async () => (await unhold.run(String(w.wave_no))) && waves.reload()}>Lift hold</button>
                )}
              </div>
            ),
          },
        ]} />
      </Card>
      <CarrierCutoffs base={base} timezone={config.data?.timezone} />
    </Page>
  )
}

/** Carrier cutoffs (ADR-0021): when each carrier's trailer leaves, in site time. */
function CarrierCutoffs({ base, timezone }: { base: string; timezone?: string }) {
  const { hasRole } = useAuth()
  const list = useLoad(() => get<Row[]>(`${base}/carrier-cutoffs`), [base])
  const [f, setF] = useState({ scac: '', time: '' })
  const save = useAction(() => put(`${base}/carrier-cutoffs/${f.scac}`, { cutoffTime: f.time }))
  const remove = useAction((scac: string) => del(`${base}/carrier-cutoffs/${scac}`))
  const canEdit = hasRole('SOLUTION_ADMIN', 'SUPERVISOR')
  return (
    <Card title={`Carrier cutoffs${timezone ? ` (${timezone})` : ''}`}>
      <p className="muted">An order's cutoff is its carrier's cutoff on the goods-issue date. Waves and backorder
        recovery take the earliest cutoff first; picks rise in priority four hours and one hour before.</p>
      <Table rows={list.data} empty="No carrier cutoffs" columns={[
        { header: 'Carrier', cell: (c) => String(c.carrier_scac) },
        { header: 'Cutoff', cell: (c) => String(c.cutoff_time) },
        { header: 'Open orders', cell: (c) => String(c.open_orders), align: 'right' },
        { header: 'Set by', cell: (c) => String(c.updated_by) },
        { header: '', cell: (c) => canEdit && (
          <button className="small" disabled={remove.busy}
                  onClick={async () => (await remove.run(String(c.carrier_scac))) && list.reload()}>Remove</button>
        ) },
      ]} />
      {canEdit && (
        <div className="row">
          <Field label="Carrier (SCAC)"><input value={f.scac} onChange={(e) => setF({ ...f, scac: e.target.value.toUpperCase() })} size={6} /></Field>
          <Field label="Cutoff"><input type="time" value={f.time} onChange={(e) => setF({ ...f, time: e.target.value })} /></Field>
          <button className="primary" disabled={save.busy || !f.scac || !f.time}
                  onClick={async () => { if (await save.run()) list.reload() }}>Save cutoff</button>
        </div>
      )}
      <ErrorBox error={list.error ?? save.error ?? remove.error} />
    </Card>
  )
}
