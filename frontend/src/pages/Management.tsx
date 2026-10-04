import { useState } from 'react'
import { get, type Row } from '../api'
import { Card, ErrorBox, Page, Table, fmtQty, useLoad } from '../ui'

/**
 * Management view (ADR-0025), from the same services, no separate BI product: inventory value at standard cost per
 * site and owner, fill rate (units shipped of units ordered, and lines shipped complete) and transfer on-time
 * (shipped by the planned ship time) over a period.
 */
const pct = (a: number, b: number) => (b > 0 ? `${((100 * a) / b).toFixed(1)} %` : '—')
const money = (v: number) => v.toLocaleString(undefined, { maximumFractionDigits: 0 })

export default function Management() {
  const [days, setDays] = useState(30)
  const value = useLoad(() => get<Row[]>('/api/v1/network/value'), [])
  const metrics = useLoad(() => get<Row[]>(`/api/v1/network/outbound/metrics?days=${days}`), [days])
  const totalValue = (value.data ?? []).reduce((n, r) => n + Number(r.value ?? 0), 0)
  const sum = (k: string) => (metrics.data ?? []).reduce((n, r) => n + Number(r[k] ?? 0), 0)
  const bySite = new Map<string, number>()
  for (const r of value.data ?? []) bySite.set(String(r.site_id), (bySite.get(String(r.site_id)) ?? 0) + Number(r.value ?? 0))
  return (
    <Page title="Management" actions={
      <select value={days} onChange={(e) => setDays(Number(e.target.value))}>
        {[7, 30, 90, 365].map((d) => <option key={d} value={d}>Last {d} days</option>)}
      </select>
    }>
      <ErrorBox error={value.error ?? metrics.error} />
      <div className="tiles">
        <div className="tile"><span className="tile-n">{money(totalValue)}</span><span className="tile-l">Inventory value</span>
          <span className="tile-d">at standard cost, all sites</span></div>
        <div className="tile"><span className="tile-n">{pct(sum('units_shipped'), sum('units_ordered'))}</span><span className="tile-l">Fill rate (units)</span>
          <span className="tile-d">{fmtQty(sum('units_shipped'))} of {fmtQty(sum('units_ordered'))} on {sum('orders_shipped')} order(s)</span></div>
        <div className="tile"><span className="tile-n">{pct(sum('lines_complete'), sum('lines'))}</span><span className="tile-l">Lines shipped complete</span></div>
        <div className="tile"><span className="tile-n">{pct(sum('transfers_on_time'), sum('transfers_shipped'))}</span><span className="tile-l">Transfers on time</span>
          <span className="tile-d">{sum('transfers_on_time')} of {sum('transfers_shipped')} shipped by the planned time</span></div>
      </div>
      <div className="grid2">
        <Card title="Service by site">
          <Table rows={metrics.data} empty="Nothing shipped in this period" columns={[
            { header: 'Site', cell: (r) => <strong>{String(r.site_id)}</strong> },
            { header: 'Orders', cell: (r) => String(r.orders_shipped), align: 'right' },
            { header: 'Fill rate', cell: (r) => pct(Number(r.units_shipped ?? 0), Number(r.units_ordered ?? 0)), align: 'right' },
            { header: 'Lines complete', cell: (r) => pct(Number(r.lines_complete), Number(r.lines)), align: 'right' },
            { header: 'Transfers', cell: (r) => String(r.transfers_shipped), align: 'right' },
            { header: 'On time', cell: (r) => pct(Number(r.transfers_on_time), Number(r.transfers_shipped)), align: 'right' },
          ]} />
        </Card>
        <Card title="Inventory value by site">
          <Table rows={[...bySite.entries()].map(([site, v]) => ({ site, v }))} empty="No stock" columns={[
            { header: 'Site', cell: (r) => <strong>{r.site}</strong> },
            { header: 'Value', cell: (r) => money(r.v), align: 'right' },
            { header: 'Share', cell: (r) => pct(r.v, totalValue), align: 'right' },
          ]} />
        </Card>
      </div>
      <Card title="Value by site and owner">
        <Table rows={value.data} empty="No stock" columns={[
          { header: 'Site', cell: (r) => String(r.site_id) },
          { header: 'Owner', cell: (r) => String(r.owner_id) },
          { header: 'Ownership', cell: (r) => String(r.ownership_type).toLowerCase().replace('_', '-') },
          { header: 'Units', cell: (r) => fmtQty(r.qty), align: 'right' },
          { header: 'Value', cell: (r) => money(Number(r.value ?? 0)), align: 'right' },
          { header: 'Items without cost', cell: (r) => (Number(r.items_without_cost) ? <span className="text-late">{String(r.items_without_cost)}</span> : ''), align: 'right' },
        ]} />
        <p className="muted">Consignment and customer- or supplier-owned stock is listed but is not the company's asset.</p>
      </Card>
    </Page>
  )
}
