import { useNavigate } from 'react-router-dom'
import { get, type Row } from '../api'
import { Card, ErrorBox, Page, Table, fmtQty, useLoad, useSiteContext, type SiteInfo } from '../ui'
import { AskBox } from './Ask'

/**
 * The site network (ADR-0024): the main warehouse and its satellite stores on one page. A map of the sites coloured by
 * their aged exceptions, the numbers per site (on hand, allocated, in transit, failed postings) and the aisles of the
 * main warehouse as a digital twin. Clicking a site opens its overview; clicking an aisle opens its open work.
 */
interface SiteRow {
  site: SiteInfo
  inv?: Row
  inb?: Row
  out?: Row
  tsk?: Row
  /** Aged exceptions: failed postings, refused goods issues, task exceptions, stale tasks, aged dock stock. */
  exceptions: number
}

const n = (v: unknown) => Number(v ?? 0)
const byId = (rows: Row[] | undefined) => new Map((rows ?? []).map((r) => [String(r.site_id), r]))
const tone = (exceptions: number) => (exceptions === 0 ? 'ok' : exceptions < 3 ? 'warn' : 'late')

export default function Network() {
  const { site, setSite, sites } = useSiteContext()
  const navigate = useNavigate()
  const inv = useLoad(() => get<Row[]>('/api/v1/network/inventory'), [])
  const inb = useLoad(() => get<Row[]>('/api/v1/network/inbound'), [])
  const out = useLoad(() => get<Row[]>('/api/v1/network/outbound'), [])
  const tsk = useLoad(() => get<Row[]>('/api/v1/network/tasks'), [])

  const [i, b, o, t] = [byId(inv.data), byId(inb.data), byId(out.data), byId(tsk.data)]
  const rows: SiteRow[] = sites.map((s) => {
    const r = { site: s, inv: i.get(s.siteId), inb: b.get(s.siteId), out: o.get(s.siteId), tsk: t.get(s.siteId) }
    const exceptions = n(r.inb?.postings_failed) + n(r.out?.ship_errors) + n(r.tsk?.exceptions) + n(r.tsk?.stale_assigned)
      + n(r.inv?.dock_aged)
    return { ...r, exceptions }
  })
  const main = sites.find((s) => s.siteId === site && s.siteType === 'MAIN')
    ?? sites.find((s) => s.siteId === sites.find((x) => x.siteId === site)?.supplyingSite)
    ?? sites.find((s) => s.siteType === 'MAIN')
  const open = (id: string) => { setSite(id); navigate('/') }

  return (
    <Page title="Site network">
      <Card title="Ask">
        <AskBox site={site} />
      </Card>
      <ErrorBox error={inv.error ?? inb.error ?? out.error ?? tsk.error} />
      {sites.length === 0 ? <p className="muted">No sites in master data for your access yet.</p> : (
        <div className="grid2">
          <Card title="Network map" actions={<Legend />}>
            <NetworkMap rows={rows} current={site} onOpen={open} />
          </Card>
          {main && <Card title={`${main.siteId} aisles`} actions={<Legend />}><Twin site={main.siteId} /></Card>}
        </div>
      )}
      <Card title="Sites">
        <Table rows={rows} onRow={(r) => open(r.site.siteId)} columns={[
          { header: 'Site', cell: (r) => <><strong>{r.site.siteId}</strong> <span className="muted">{r.site.name}</span></> },
          { header: 'Type', cell: (r) => (r.site.siteType === 'STORE' ? `Store${r.site.supplyingSite ? ` (from ${r.site.supplyingSite})` : ''}` : 'Main') },
          { header: 'On hand', align: 'right', cell: (r) => fmtQty(r.inv?.on_hand ?? 0) },
          { header: 'Allocated', align: 'right', cell: (r) => fmtQty(r.inv?.allocated ?? 0) },
          { header: 'Held (QC / blocked)', align: 'right', cell: (r) => fmtQty(r.inv?.held ?? 0) },
          { header: 'Transfers in transit (in)', align: 'right', cell: (r) => n(r.inb?.transfers_in_transit) },
          { header: 'Open orders / transfers out', align: 'right', cell: (r) => `${n(r.out?.orders_open)} / ${n(r.out?.transfers_open)}` },
          { header: 'Lines short', align: 'right', cell: (r) => n(r.out?.lines_short) },
          { header: 'ERP postings failed', align: 'right', cell: (r) => n(r.inb?.postings_failed) + n(r.out?.ship_errors) },
          { header: 'Task exceptions / stale', align: 'right', cell: (r) => `${n(r.tsk?.exceptions)} / ${n(r.tsk?.stale_assigned)}` },
          { header: 'PI frozen', cell: (r) => (n(r.inv?.pi_frozen) ? 'Yes' : '') },
          { header: 'Aged exceptions', align: 'right', cell: (r) => <span className={`dot ${tone(r.exceptions)}`}>{r.exceptions}</span> },
        ]} />
      </Card>
    </Page>
  )
}

function Legend() {
  return (
    <span className="legend muted">
      <span className="dot ok" /> none <span className="dot warn" /> 1–2 <span className="dot late" /> 3+ aged exceptions
    </span>
  )
}

/** Main sites in the middle, their stores around them; a line for each supply relation. */
function NetworkMap({ rows, current, onOpen }: { rows: SiteRow[]; current: string; onOpen: (id: string) => void }) {
  const mains = rows.filter((r) => r.site.siteType === 'MAIN')
  const stores = rows.filter((r) => r.site.siteType === 'STORE')
  const W = 520
  const H = 420
  const pos = new Map<string, { x: number; y: number }>()
  mains.forEach((m, k) => pos.set(m.site.siteId, { x: W / 2 + (k - (mains.length - 1) / 2) * 90, y: H / 2 }))
  stores.forEach((s, k) => {
    const a = (2 * Math.PI * k) / Math.max(stores.length, 1) - Math.PI / 2
    const ring = stores.length > 14 && k % 2 ? 140 : 180
    pos.set(s.site.siteId, { x: W / 2 + ring * 1.15 * Math.cos(a), y: H / 2 + ring * Math.sin(a) })
  })
  return (
    <svg viewBox={`0 0 ${W} ${H}`} className="network-map" role="img" aria-label="Site network map">
      {stores.map((s) => {
        const from = pos.get(s.site.supplyingSite ?? mains[0]?.site.siteId ?? '')
        const to = pos.get(s.site.siteId)
        return from && to ? <line key={`l-${s.site.siteId}`} x1={from.x} y1={from.y} x2={to.x} y2={to.y} className="link" /> : null
      })}
      {[...mains, ...stores].map((r) => {
        const p = pos.get(r.site.siteId)!
        const big = r.site.siteType === 'MAIN'
        return (
          <g key={r.site.siteId} className={`node ${tone(r.exceptions)} ${r.site.siteId === current ? 'current' : ''}`}
             onClick={() => onOpen(r.site.siteId)} role="button" tabIndex={0}
             onKeyDown={(e) => { if (e.key === 'Enter') onOpen(r.site.siteId) }}>
            <title>{`${r.site.siteId} ${r.site.name}: ${r.exceptions} aged exception(s), ${fmtQty(r.inv?.on_hand ?? 0)} on hand, ${n(r.inb?.transfers_in_transit)} transfer(s) in transit`}</title>
            {big ? <rect x={p.x - 34} y={p.y - 22} width={68} height={44} rx={6} /> : <circle cx={p.x} cy={p.y} r={17} />}
            <text x={p.x} y={p.y + 4} textAnchor="middle">{r.site.siteId}</text>
            {r.exceptions > 0 && <text x={p.x + (big ? 30 : 15)} y={p.y - (big ? 18 : 13)} className="badge-n">{r.exceptions}</text>}
          </g>
        )
      })}
    </svg>
  )
}

/** The aisles of one site as tiles, coloured by aged exceptions (task exceptions, stale tasks, aged dock stock). */
function Twin({ site }: { site: string }) {
  const navigate = useNavigate()
  const stock = useLoad(() => get<Row[]>(`/api/v1/sites/${site}/inventory/aisles`), [site])
  const work = useLoad(() => get<Row[]>(`/api/v1/sites/${site}/tasks/aisles`), [site])
  const aisles = new Map<string, { stock?: Row; work?: Row }>()
  for (const s of stock.data ?? []) aisles.set(String(s.aisle), { stock: s })
  for (const w of work.data ?? []) aisles.set(String(w.aisle), { ...aisles.get(String(w.aisle)), work: w })
  const list = [...aisles.entries()].sort(([a], [b]) => a.localeCompare(b))
  if (!stock.data && !work.data) return <ErrorBox error={stock.error ?? work.error} />
  if (list.length === 0) return <p className="muted">No stock or open work at {site}.</p>
  return (
    <div className="twin">
      {list.map(([aisle, a]) => {
        const exceptions = n(a.work?.exceptions) + n(a.work?.stale_assigned) + n(a.stock?.dock_aged)
        return (
          <button key={aisle} className={`twin-cell ${tone(exceptions)} ${a.stock?.frozen ? 'frozen' : ''}`}
                  title={`${aisle}: ${fmtQty(a.stock?.on_hand ?? 0)} on hand, ${n(a.work?.tasks_open)} open task(s), ${exceptions} aged exception(s)${a.stock?.frozen ? ', frozen for a physical inventory' : ''}`}
                  onClick={() => navigate(`/tasks?q=${encodeURIComponent(aisle)}`)}>
            <strong>{aisle}</strong>
            <span>{fmtQty(a.stock?.on_hand ?? 0)}</span>
            {n(a.work?.tasks_open) > 0 && <span>{n(a.work?.tasks_open)} task(s)</span>}
            {exceptions > 0 && <span className="text-late">{exceptions} exc.</span>}
            {a.stock?.frozen ? <span>frozen</span> : null}
          </button>
        )
      })}
    </div>
  )
}
