import { useState } from 'react'
import { Link } from 'react-router-dom'
import { get, post, type Row } from '../api'
import { Badge, Card, ErrorBox, Field, Page, Success, Table, fmtDate, useAction, useLoad, useSite } from '../ui'

interface Plan {
  orders: { erpDocNo: string; orderType: string; carrierScac?: string; plannedGoodsIssueUtc?: string; lines: number }[]
  orderCount: number
  lineCount: number
}

/** Wave planning (§C.4, ADV-030): preview a wave from criteria, create it, release it. */
export default function Waves() {
  const site = useSite()
  const base = `/api/v1/sites/${site}/outbound`
  const config = useLoad(() => get<{ releaseMode: string }>(`${base}/config`), [base])
  const waves = useLoad(() => get<Row[]>(`${base}/waves`), [base])
  const [criteria, setCriteria] = useState({ carrierScac: '', orderType: '', goodsIssueBefore: '', maxOrders: '', maxLines: '' })
  const body = () => ({
    carrierScac: criteria.carrierScac || null,
    orderType: criteria.orderType || null,
    goodsIssueBefore: criteria.goodsIssueBefore ? new Date(criteria.goodsIssueBefore).toISOString() : null,
    maxOrders: criteria.maxOrders ? Number(criteria.maxOrders) : null,
    maxLines: criteria.maxLines ? Number(criteria.maxLines) : null,
  })
  const plan = useAction(() => post<Plan>(`${base}/waves/plan`, body()))
  const create = useAction(() => post<Row>(`${base}/waves`, body()))
  const release = useAction((waveNo: string) => post<Row>(`${base}/waves/${waveNo}/release`))
  const set = (k: keyof typeof criteria) => (e: { target: { value: string } }) => setCriteria({ ...criteria, [k]: e.target.value })

  return (
    <Page title="Waves">
      {config.data && config.data.releaseMode !== 'WAVE' && (
        <div className="alert">Site {site} releases orders on receipt (WAVELESS). Orders only wait for waves in WAVE
          mode; a solution administrator sets it under Master data.</div>
      )}
      <Card title="Plan a wave">
        <div className="row">
          <Field label="Carrier"><input value={criteria.carrierScac} onChange={set('carrierScac')} size={6} /></Field>
          <Field label="Order type"><input value={criteria.orderType} onChange={set('orderType')} size={10} /></Field>
          <Field label="Goods issue before"><input type="datetime-local" value={criteria.goodsIssueBefore} onChange={set('goodsIssueBefore')} /></Field>
          <Field label="Max orders"><input type="number" min={1} value={criteria.maxOrders} onChange={set('maxOrders')} size={5} /></Field>
          <Field label="Max lines"><input type="number" min={1} value={criteria.maxLines} onChange={set('maxLines')} size={5} /></Field>
        </div>
        <div className="actions">
          <button onClick={() => void plan.run()} disabled={plan.busy}>Preview</button>
          <button className="primary" disabled={create.busy}
                  onClick={async () => { if (await create.run()) { plan.clear(); waves.reload() } }}>Create wave</button>
        </div>
        <ErrorBox error={plan.error ?? create.error} />
        <Success>{create.result ? `Wave ${String(create.result.wave_no)} created` : null}</Success>
        {plan.result && (
          <>
            <p><strong>{plan.result.orderCount}</strong> orders, <strong>{plan.result.lineCount}</strong> lines (preview — nothing changed)</p>
            <Table rows={plan.result.orders} columns={[
              { header: 'Delivery', cell: (o) => <Link to={`/orders/${o.erpDocNo}`}>{o.erpDocNo}</Link> },
              { header: 'Type', cell: (o) => o.orderType },
              { header: 'Carrier', cell: (o) => o.carrierScac },
              { header: 'Goods issue', cell: (o) => fmtDate(o.plannedGoodsIssueUtc) },
              { header: 'Lines', cell: (o) => o.lines, align: 'right' },
            ]} />
          </>
        )}
      </Card>
      <Card title="Waves">
        <ErrorBox error={waves.error ?? release.error} />
        <Table rows={waves.data} empty="No waves yet" columns={[
          { header: 'Wave', cell: (w) => String(w.wave_no) },
          { header: 'Status', cell: (w) => <Badge value={String(w.status)} /> },
          { header: 'Orders', cell: (w) => String(w.orders), align: 'right' },
          { header: 'Created', cell: (w) => `${fmtDate(w.created_at)} · ${String(w.created_by)}` },
          { header: 'Released', cell: (w) => (w.released_at ? `${fmtDate(w.released_at)} · ${String(w.released_by)}` : '') },
          {
            header: '',
            cell: (w) => w.status === 'PLANNED' && (
              <button className="primary small" disabled={release.busy}
                      onClick={async () => (await release.run(String(w.wave_no))) && waves.reload()}>Release</button>
            ),
          },
        ]} />
      </Card>
    </Page>
  )
}
