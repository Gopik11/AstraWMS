import { useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { get, put, query, type Row } from '../api'
import { useAuth } from '../auth'
import { acceptRecommendation, type Recommendation } from '../replenish'
import { Badge, Card, ErrorBox, Field, Page, Success, Table, fmtQty, useAction, useLoad, useSiteContext } from '../ui'

/**
 * Predictive store replenishment (ADR-0025): for each store item with a policy, what to send, from where, by when,
 * why, and how sure. Accept creates the transfer; the store's stock, what is in transit and the accepted transfers
 * are counted, so a need is recommended once.
 */
export default function StoreReplenishment() {
  const { site, sites, isStore } = useSiteContext()
  const { hasRole } = useAuth()
  const [scope, setScope] = useState<'all' | 'site'>(isStore ? 'site' : 'all')
  // The tower tile opens this page with ?source=DC1: the same recommendations it counts.
  const [params, setParams] = useSearchParams()
  const source = params.get('source') ?? ''
  const recs = useLoad(() => get<Recommendation[]>(`/api/v1/network/replenishment${query({ siteId: scope === 'site' ? site : undefined })}`), [site, scope])
  const [done, setDone] = useState<string>()
  const accept = useAction(async (r: Recommendation) => {
    const no = await acceptRecommendation(r)
    setDone(`${no} created: ${fmtQty(r.qty)} ${r.itemNo} from ${String(r.sourceSite)} to ${r.siteId}, needed by ${String(r.requiredDate)}`)
    recs.reload()
    return no
  })
  const canAccept = hasRole('SUPERVISOR', 'INV_MANAGER')
  const recommended = (recs.data ?? []).filter((r) => r.recommended && (!source || r.sourceSite === source))
  const unknown = recs.data?.some((r) => !r.commitmentsKnown)
  return (
    <Page title="Store replenishment" actions={
      <select value={scope} onChange={(e) => setScope(e.target.value as 'all' | 'site')}>
        <option value="all">All stores</option><option value="site">{site} only</option>
      </select>
    }>
      <ErrorBox error={recs.error ?? accept.error} />
      {unknown && <div className="alert error">Open orders and transfers could not be read from outbound: source availability is
        on hand minus allocated only, and every recommendation is LOW confidence until it answers.</div>}
      {source && <p className="muted">Recommendations sent from {source} <button className="link" onClick={() => setParams({})}>show all</button></p>}
      <Success>{done}</Success>
      <Card title={`${recommended.length} transfer(s) recommended`}>
        <Table rows={recs.data ? recommended : undefined} empty="Every store item is covered" columns={[
          { header: 'Store', cell: (r) => <strong>{r.siteId}</strong> },
          { header: 'Item', cell: (r) => `${r.itemNo} (${r.ownerId})` },
          { header: 'Send', cell: (r) => fmtQty(r.qty), align: 'right' },
          { header: 'From', cell: (r) => (r.source ? (
            <span title={r.whySource}>
              <strong>{r.source.site}</strong>
              <span className="muted small"> on hand {fmtQty(r.source.onHand)} · allocated to orders {fmtQty(r.source.allocatedOrders)}
                · to transfers {fmtQty(r.source.allocatedTransfers)} · waiting {fmtQty(r.source.waitingOnOpenDocuments)} ·
                <strong> free {fmtQty(r.source.free)}</strong></span>
              <div className="muted small">{r.whySource}{r.alternatives?.length ? `; also: ${r.alternatives.map((a) => `${a.site} (${fmtQty(a.transferable)})`).join(', ')}` : ''}</div>
            </span>) : '—') },
          { header: 'Needed by', cell: (r) => r.requiredDate },
          { header: 'Confidence', cell: (r) => <Badge value={r.confidence} /> },
          { header: 'Stockout risk', cell: (r) => <span className={r.stockoutRisk ? 'text-late' : 'muted'}>{r.stockoutRiskText}</span> },
          { header: 'Transit', cell: (r) => <span title={`from the ${r.transitDaysFrom}`}>{r.transitDays} d</span> },
          { header: 'Why', cell: (r) => <span className="muted small">{r.reason}</span> },
          { header: '', cell: (r) => (canAccept ? (
            <button className="small primary" disabled={accept.busy || !r.source || Number(r.source.free) < Number(r.qty)}
                    title={r.source && Number(r.source.free) < Number(r.qty) ? `${r.source.site} has only ${fmtQty(r.source.free)} free` : undefined}
                    onClick={() => { if (window.confirm(`Create a transfer of ${fmtQty(r.qty)} ${r.itemNo} from ${String(r.sourceSite)} to ${r.siteId}? It is allocated at ${String(r.sourceSite)} now.`)) void accept.run(r) }}>
              Accept
            </button>) : null) },
        ]} />
      </Card>
      <Card title="All policies">
        <Table rows={recs.data} empty="No store has a replenishment policy yet" columns={[
          { header: 'Store', cell: (r) => r.siteId },
          { header: 'Item', cell: (r) => `${r.itemNo} (${r.ownerId})` },
          { header: 'Available', cell: (r) => fmtQty(r.available), align: 'right' },
          { header: 'In transit + open transfers', cell: (r) => <span title={r.openTransfers ?? ''}>{fmtQty(r.inTransit)} + {fmtQty(r.openTransferQty)}</span>, align: 'right' },
          { header: 'Use / day', cell: (r) => (r.history === 'NONE' ? <span className="muted">no history</span> : fmtQty(r.dailyUsage)), align: 'right' },
          { header: 'Days of cover', cell: (r) => (r.daysOfCover == null ? '—' : String(r.daysOfCover)), align: 'right' },
          { header: 'Min / max / safety', cell: (r) => `${fmtQty(r.min)} / ${fmtQty(r.max)} / ${fmtQty(r.safety)}` },
          { header: 'Status', cell: (r) => (r.recommended ? <Badge value="REPLENISH" /> : <span className="muted">covered</span>) },
        ]} />
      </Card>
      {hasRole('SUPERVISOR', 'INV_MANAGER', 'SOLUTION_ADMIN') && sites.some((s) => s.siteId === site && s.siteType === 'STORE') && (
        <Policies site={site} onSaved={recs.reload} />
      )}
    </Page>
  )
}

function Policies({ site, onSaved }: { site: string; onSaved: () => void }) {
  const list = useLoad(() => get<Row[]>(`/api/v1/sites/${site}/inventory/store-policies`), [site])
  const [f, setF] = useState({ ownerId: '', itemNo: '', minQty: '', maxQty: '', safetyQty: '0', transitDays: '1' })
  const save = useAction(() => put<Row[]>(`/api/v1/sites/${site}/inventory/store-policies`, {
    ownerId: f.ownerId, itemNo: f.itemNo, minQty: Number(f.minQty), maxQty: Number(f.maxQty),
    safetyQty: Number(f.safetyQty || 0), transitDays: Number(f.transitDays || 1) }))
  return (
    <Card title={`Policies at ${site}`}>
      <Table rows={list.data} empty="No policy at this store" columns={[
        { header: 'Item', cell: (r) => `${String(r.item_no)} (${String(r.owner_id)})` },
        { header: 'Min', cell: (r) => fmtQty(r.min_qty), align: 'right' },
        { header: 'Max', cell: (r) => fmtQty(r.max_qty), align: 'right' },
        { header: 'Safety', cell: (r) => fmtQty(r.safety_qty), align: 'right' },
        { header: 'Transit days', cell: (r) => String(r.transit_days), align: 'right' },
      ]} />
      <div className="row">
        <Field label="Owner"><input value={f.ownerId} onChange={(e) => setF({ ...f, ownerId: e.target.value.toUpperCase() })} size={7} /></Field>
        <Field label="Item"><input value={f.itemNo} onChange={(e) => setF({ ...f, itemNo: e.target.value })} size={10} /></Field>
        <Field label="Min"><input type="number" min={0} value={f.minQty} onChange={(e) => setF({ ...f, minQty: e.target.value })} size={5} /></Field>
        <Field label="Max"><input type="number" min={0} value={f.maxQty} onChange={(e) => setF({ ...f, maxQty: e.target.value })} size={5} /></Field>
        <Field label="Safety"><input type="number" min={0} value={f.safetyQty} onChange={(e) => setF({ ...f, safetyQty: e.target.value })} size={5} /></Field>
        <Field label="Transit days"><input type="number" min={0} max={60} value={f.transitDays} onChange={(e) => setF({ ...f, transitDays: e.target.value })} size={3} /></Field>
        <button className="primary" disabled={save.busy || !f.ownerId || !f.itemNo || f.minQty === '' || f.maxQty === ''}
                onClick={async () => { if (await save.run()) { list.reload(); onSaved() } }}>Save policy</button>
      </div>
      <ErrorBox error={save.error} />
    </Card>
  )
}
