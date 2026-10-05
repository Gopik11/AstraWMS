import { useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { get, post, put, query, type Row } from '../api'
import { useAuth } from '../auth'
import { acceptRecommendation, type Recommendation } from '../replenish'
import { Badge, Card, ConfirmButton, ErrorBox, Field, Page, Success, Table, fmtQty, useAction, useLoad, useSiteContext, useToast } from '../ui'

/** An item's pick faces at the source (ADR-0028): free, capacity, open replenishment; and its reserve pallets. */
interface Faces {
  faces: { location_id: string; capacity: number; on_hand: number; allocated: number; free: number;
    open_replenishments: { id: string; qty: number; source_location: string; source_lpn: string }[] }[]
  reserve: { location_id: string; lpn_id: string; qty: number; allocated_qty: number; free: number }[]
}

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
  const shortageOnly = params.get('shortage') === '1'
  // The refusal of an Accept stays on its row (ADR-0026), e.g. a pick face with nothing free and no pallet to split.
  const [refused, setRefused] = useState<Record<string, string>>({})
  const rowKey = (r: Recommendation) => `${r.siteId}/${r.ownerId}/${r.itemNo}`
  const recs = useLoad(() => get<Recommendation[]>(`/api/v1/network/replenishment${query({ siteId: scope === 'site' ? site : undefined })}`), [site, scope])
  const [done, setDone] = useState<string>()
  const [toast, showToast] = useToast()
  // Pick faces of each source/item on the list: Accept is allocated from the face unless a whole pallet covers it.
  const faceKeys = [...new Set((recs.data ?? []).filter((r) => r.recommended && r.sourceSite)
    .map((r) => `${String(r.sourceSite)}|${r.ownerId}|${r.itemNo}`))].sort()
  const faces = useLoad(async () => {
    const out: Record<string, Faces> = {}
    for (const k of faceKeys) {
      const [src, owner, item] = k.split('|')
      out[k] = await get<Faces>(`/api/v1/sites/${src}/inventory/faces/${owner}/${encodeURIComponent(item)}`)
    }
    return out
  }, [faceKeys.join(',')])
  const facesOf = (r: Recommendation) => faces.data?.[`${String(r.sourceSite)}|${r.ownerId}|${r.itemNo}`]
  const accept = useAction(async (r: Recommendation) => {
    let no: string
    try {
      no = await acceptRecommendation(r)
    } catch (e) {
      const text = e instanceof Error ? e.message : String(e)
      setRefused((m) => ({ ...m, [rowKey(r)]: text }))
      showToast('error', `Accept refused for ${r.siteId} ${r.itemNo}: ${text}`)
      faces.reload()
      return undefined
    }
    setRefused((m) => { const n = { ...m }; delete n[rowKey(r)]; return n })
    const msg = `${no} created and allocated: ${fmtQty(r.qty)} ${r.itemNo} from ${String(r.sourceSite)} to ${r.siteId}, needed by ${String(r.requiredDate)}`
    setDone(msg)
    showToast('ok', msg)
    recs.reload()
    faces.reload()
    return no
  })
  // "Create replen to P-01": the face's open replenishment if there is one, else one from a reserve pallet with free stock.
  const replen = useAction(async (r: Recommendation) => {
    const res = await post<{ location: string; created: number; alreadyOpen: boolean; open: { qty: number; source_location: string; source_lpn: string }[] }[]>(
      `/api/v1/sites/${String(r.sourceSite)}/inventory/faces/${r.ownerId}/${encodeURIComponent(r.itemNo)}/replenish`)
    const text = res.map((f) => (f.open.length
      ? `${f.alreadyOpen ? 'Replenishment already open' : 'Replenishment created'} to ${f.location}: ${f.open.map((o) => `${fmtQty(o.qty)} from ${o.source_location} (LPN ${o.source_lpn})`).join(', ')}. Confirm the REPLEN task on RF, then Accept.`
      : `${f.location} is at capacity: nothing to replenish`)).join(' ')
    showToast('ok', text)
    faces.reload()
    return text
  })
  const canAccept = hasRole('SUPERVISOR', 'INV_MANAGER')
  const recommended = (recs.data ?? []).filter((r) => (shortageOnly ? r.shortage || r.recommended : r.recommended)
    && (!source || r.sourceSite === source) && (!shortageOnly || r.shortage))
  const unknown = recs.data?.some((r) => !r.commitmentsKnown)
  return (
    <Page title="Store replenishment" actions={
      <select value={scope} onChange={(e) => setScope(e.target.value as 'all' | 'site')}>
        <option value="all">All stores</option><option value="site">{site} only</option>
      </select>
    }>
      {toast}
      <ErrorBox error={recs.error ?? accept.error ?? replen.error ?? faces.error} />
      {unknown && <div className="alert error">Open orders and transfers could not be read from outbound: source availability is
        on hand minus allocated only, and every recommendation is LOW confidence until it answers.</div>}
      {source && <p className="muted">Recommendations sent from {source} <button className="link" onClick={() => setParams({})}>show all</button></p>}
      {shortageOnly && <p className="muted">Store shortages: CRITICAL or HIGH stockout risk, or below min + safety without usage history
        <button className="link" onClick={() => setParams({})}>show all</button></p>}
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
          { header: 'Pick face', cell: (r) => {
            const f = facesOf(r)
            if (!f) return faces.data ? <span className="muted">no pick face</span> : '…'
            if (f.faces.length === 0) return <span className="muted">no pick face</span>
            return f.faces.map((x) => (
              <div key={x.location_id} className="small">
                <strong>{x.location_id}</strong>: <span className={Number(x.free) < Number(r.qty) ? 'text-late' : ''}>{fmtQty(x.free)} free</span>
                {' '}of capacity {fmtQty(x.capacity)}
                {x.open_replenishments.length > 0 && <div className="muted">replen {x.open_replenishments.map((o) => `${fmtQty(o.qty)} from ${o.source_location}`).join(', ')} open: confirm on RF</div>}
              </div>
            ))
          } },
          { header: 'Needed by', cell: (r) => r.requiredDate },
          { header: 'Stockout', cell: (r) => r.stockoutDate ?? <span className="muted">no history</span> },
          { header: 'Confidence', cell: (r) => <Badge value={r.confidence === 'MEDIUM' ? 'MED' : r.confidence} /> },
          { header: 'Stockout risk', cell: (r) => <span className={r.stockoutRisk ? 'text-late' : 'muted'}>{r.stockoutRiskText}</span> },
          { header: 'Transit', cell: (r) => <span title={`from the ${r.transitDaysFrom}`}>{r.transitDays} d</span> },
          { header: 'Why', cell: (r) => (
            <span className="muted small">{r.reason}{r.qtyBasis ? <><br />Quantity: {r.qtyBasis}</> : null}
              {refused[rowKey(r)] && <span className="text-late"><br />Accept refused: {refused[rowKey(r)]}</span>}</span>) },
          { header: '', cell: (r) => {
            if (!canAccept) return null
            const f = facesOf(r)
            const face = f?.faces[0]
            const faceShort = face !== undefined && Number(face.free) < Number(r.qty)
            const replenOpen = (face?.open_replenishments.length ?? 0) > 0
            return (
              <div className="row tight">
                <ConfirmButton label="Accept" className="small primary"
                               disabled={accept.busy || !r.source || Number(r.source.free) < Number(r.qty)}
                               title={r.source && Number(r.source.free) < Number(r.qty) ? `${r.source.site} has only ${fmtQty(r.source.free)} free` : undefined}
                               question={`Transfer ${fmtQty(r.qty)} ${r.itemNo} ${String(r.sourceSite)} → ${r.siteId}, allocated now?`}
                               onConfirm={() => void accept.run(r)} />
                {face && (faceShort || refused[rowKey(r)]) && (replenOpen
                  ? <Link className="button small" to={`/tasks?type=REPLEN&q=${encodeURIComponent(face.location_id)}`}>Replen to {face.location_id} open: confirm on RF</Link>
                  : <ConfirmButton label={`Create replen to ${face.location_id}`} disabled={replen.busy}
                                   question={`Replenish ${face.location_id} from reserve now?`} onConfirm={() => void replen.run(r)} />)}
              </div>
            )
          } },
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
          { header: 'Stockout', cell: (r) => r.stockoutDate ?? <span className="muted">no history</span> },
          { header: 'Risk', cell: (r) => <span className={r.stockoutRisk ? 'text-late' : 'muted'}>{r.risk === 'NO_HISTORY' ? 'no history' : r.risk === 'NONE' ? 'none' : r.risk}</span> },
          { header: 'Confidence', cell: (r) => (r.recommended ? <Badge value={r.confidence === 'MEDIUM' ? 'MED' : r.confidence} /> : '') },
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
