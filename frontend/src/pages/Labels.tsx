import { useState } from 'react'
import { del, get, post, put, query, type Row } from '../api'
import { useAuth } from '../auth'
import { code128Svg } from '../code128'
import { Badge, Card, ErrorBox, Field, Page, SearchBox, Success, Table, fmtDate, useAction, useLoad, useSite } from '../ui'

interface Label { barcode: string; symbology: string; lines: string[]; zpl: string }
interface Labels { count: number; labels: Label[]; zpl: string; printedOn?: string | null }

/**
 * Barcode labels (ADR-0022): bins (with check digit), items (GS1-128 GTIN or item number) and pre-numbered LPN /
 * pallet labels. Print from the browser to any printer, download the ZPL for a Zebra printer, or send it to one of
 * the site's network label printers.
 */
export default function LabelsPage() {
  const site = useSite()
  const { hasRole } = useAuth()
  const base = `/api/v1/sites/${site}/labels`
  const printers = useLoad(() => get<Row[]>(`${base}/printers`), [base])
  const [printer, setPrinter] = useState('')
  const [result, setResult] = useState<Labels>()
  const target = printer || null

  const [loc, setLoc] = useState({ zoneId: '', from: '', to: '', ids: '' })
  const locations = useAction(() => post<Labels>(`${base}/locations`, {
    zoneId: loc.zoneId || null, from: loc.from || null, to: loc.to || null, printer: target,
    locationIds: loc.ids.split(/[\s,]+/).map((s) => s.trim()).filter(Boolean),
  }))
  const [item, setItem] = useState({ ownerId: '', itemNos: '', uom: '', copies: '1' })
  const items = useAction(() => post<Labels>(`${base}/items`, {
    ownerId: item.ownerId, uom: item.uom || null, copies: Number(item.copies || 1), printer: target,
    itemNos: item.itemNos.split(/[\s,]+/).map((s) => s.trim()).filter(Boolean),
  }))
  const [lpnCount, setLpnCount] = useState('10')
  const lpns = useAction(() => post<Labels>(`${base}/lpns`, { count: Number(lpnCount), printer: target }))
  const run = async (a: { run: () => Promise<Labels | undefined> }) => { const r = await a.run(); if (r) setResult(r) }
  const error = locations.error ?? items.error ?? lpns.error

  const download = () => {
    if (!result) return
    const url = URL.createObjectURL(new Blob([result.zpl], { type: 'text/plain' }))
    const a = document.createElement('a')
    a.href = url
    a.download = `labels-${site}-${Date.now()}.zpl`
    a.click()
    URL.revokeObjectURL(url)
  }

  return (
    <Page title="Labels">
      <Card title="Print to">
        <div className="row">
          <Field label="Printer" hint="Browser: preview below, then Print. A network printer gets the ZPL directly.">
            <select value={printer} onChange={(e) => setPrinter(e.target.value)}>
              <option value="">Browser preview / download</option>
              <option value="DEFAULT">Site default for the label type</option>
              {(printers.data ?? []).map((p) => <option key={String(p.name)} value={String(p.name)}>{String(p.name)} ({String(p.host)})</option>)}
            </select>
          </Field>
        </div>
      </Card>
      <div className="grid">
        <Card title="Bin / location labels">
          <div className="row">
            <Field label="Zone"><input value={loc.zoneId} onChange={(e) => setLoc({ ...loc, zoneId: e.target.value.toUpperCase() })} size={6} /></Field>
            <Field label="From"><input value={loc.from} onChange={(e) => setLoc({ ...loc, from: e.target.value.toUpperCase() })} size={9} /></Field>
            <Field label="To"><input value={loc.to} onChange={(e) => setLoc({ ...loc, to: e.target.value.toUpperCase() })} size={9} /></Field>
          </div>
          <Field label="Or these locations" hint="Comma or space separated"><input value={loc.ids} onChange={(e) => setLoc({ ...loc, ids: e.target.value.toUpperCase() })} /></Field>
          <button className="primary" disabled={locations.busy} onClick={() => void run(locations)}>Make labels</button>
        </Card>
        <Card title="Item labels">
          <div className="row">
            <Field label="Owner"><input value={item.ownerId} onChange={(e) => setItem({ ...item, ownerId: e.target.value.toUpperCase() })} size={7} /></Field>
            <Field label="Unit" hint="Blank = base unit"><input value={item.uom} onChange={(e) => setItem({ ...item, uom: e.target.value.toUpperCase() })} size={4} /></Field>
            <Field label="Copies"><input type="number" min={1} value={item.copies} onChange={(e) => setItem({ ...item, copies: e.target.value })} size={3} /></Field>
          </div>
          <Field label="Items" hint="Item numbers, comma or space separated"><input value={item.itemNos} onChange={(e) => setItem({ ...item, itemNos: e.target.value.toUpperCase() })} /></Field>
          <button className="primary" disabled={items.busy || !item.ownerId || !item.itemNos} onClick={() => void run(items)}>Make labels</button>
        </Card>
        <Card title="LPN / pallet labels">
          <p className="muted">Next numbers of the site's LPN series; a number is never issued twice.</p>
          <Field label="How many"><input type="number" min={1} max={500} value={lpnCount} onChange={(e) => setLpnCount(e.target.value)} size={4} /></Field>
          <button className="primary" disabled={lpns.busy} onClick={() => void run(lpns)}>Make labels</button>
        </Card>
      </div>
      <ErrorBox error={error} />
      {result && (
        <Card title={`${result.count} label(s)`} actions={
          <div className="row no-print">
            <button onClick={() => window.print()}>Print (browser)</button>
            <button onClick={download}>Download ZPL</button>
          </div>
        }>
          <Success>{result.printedOn ? `Sent to printer ${result.printedOn}` : null}</Success>
          <div className="label-sheet">
            {result.labels.map((l, i) => (
              <div className="print-label" key={i}>
                <div className="print-label-title">{l.lines[0]}</div>
                <div className="print-label-bars" dangerouslySetInnerHTML={{ __html: code128Svg(l.barcode, { gs1: l.symbology === 'GS1-128', height: 60 }) }} />
                <div className="print-label-code">{l.barcode}</div>
                {l.lines.slice(1).map((t, k) => <div key={k} className="print-label-line">{t}</div>)}
              </div>
            ))}
          </div>
        </Card>
      )}
      <Printed base={base} printer={target} onPreview={(l) => setResult(l)} />
      {hasRole('SOLUTION_ADMIN') && <Printers base={base} onChange={printers.reload} rows={printers.data} />}
    </Page>
  )
}

/**
 * Label lifecycle (ADR-0024): every label made here is recorded. Scan a label after it is stuck on to verify it;
 * reprint a damaged one (to the chosen printer, or as a browser preview); void one on the wrong pallet.
 */
function Printed({ base, printer, onPreview }: { base: string; printer: string | null; onPreview: (l: Labels) => void }) {
  const { hasRole } = useAuth()
  const [f, setF] = useState({ type: '', status: '', q: '' })
  const list = useLoad(() => get<Row[]>(`${base}/printed${query(f)}`), [base, f.type, f.status, f.q])
  const [scan, setScan] = useState('')
  const verify = useAction(() => post<Row>(`${base}/printed/verify`, { scan }))
  const reprint = useAction((r: Row) => post<Row & { zpl: string }>(`${base}/printed/${String(r.id)}/reprint`, { printer }))
  const voidIt = useAction((r: Row, reason: string) => post<Row>(`${base}/printed/${String(r.id)}/void`, { reason }))
  return (
    <Card title="Printed labels">
      <form className="row" onSubmit={async (e) => { e.preventDefault(); if (await verify.run()) { setScan(''); list.reload() } }}>
        <Field label="Verify: scan the label after it is applied">
          <input value={scan} onChange={(e) => setScan(e.target.value)} autoComplete="off" placeholder="Scan" />
        </Field>
        <button className="primary" disabled={!scan || verify.busy}>Verify scan</button>
      </form>
      <Success>{verify.result && `${String(verify.result.barcode)} verified`}</Success>
      <ErrorBox error={verify.error ?? reprint.error ?? voidIt.error} />
      <div className="row">
        <Field label="Type"><select value={f.type} onChange={(e) => setF({ ...f, type: e.target.value })}>
          <option value="">All</option><option>LOCATION</option><option>ITEM</option><option>LPN</option></select></Field>
        <Field label="Status"><select value={f.status} onChange={(e) => setF({ ...f, status: e.target.value })}>
          <option value="">All</option><option>PRINTED</option><option>VERIFIED</option><option>VOID</option></select></Field>
        <SearchBox value={f.q} onSearch={(q) => setF({ ...f, q })} placeholder="Barcode or text" />
      </div>
      <Table rows={list.data} empty="No labels printed yet" columns={[
        { header: 'Label', cell: (r) => <><strong>{String(r.barcode)}</strong> <span className="muted">{String(r.label_type).toLowerCase()}</span></> },
        { header: 'Status', cell: (r) => <Badge value={String(r.status)} /> },
        { header: 'Printed', cell: (r) => `${fmtDate(r.printed_at)} by ${String(r.printed_by)}${r.printer ? ` on ${String(r.printer)}` : ' (browser)'} · ${String(r.print_count)}×` },
        { header: 'Verified / void', cell: (r) => (r.status === 'VOID' ? `void: ${String(r.void_reason)} (${String(r.voided_by)})`
          : r.verified_at ? `${fmtDate(r.verified_at)} by ${String(r.verified_by)}` : '') },
        { header: '', cell: (r) => r.status === 'VOID' ? null : (
          <div className="row tight">
            <button className="small" disabled={reprint.busy} onClick={async () => {
              const out = await reprint.run(r)
              if (out && !printer) onPreview({ count: 1, labels: [{ barcode: String(out.barcode), symbology: String(out.barcode).startsWith('(01)') ? 'GS1-128' : 'CODE128', lines: (out.lines as string[]) ?? [], zpl: out.zpl }], zpl: out.zpl })
              list.reload()
            }}>Reprint</button>
            {hasRole('SOLUTION_ADMIN', 'SUPERVISOR', 'INV_MANAGER') && (
              <button className="small" disabled={voidIt.busy} onClick={async () => {
                const reason = window.prompt(`Void ${String(r.barcode)}: reason?`)
                if (reason && await voidIt.run(r, reason)) list.reload()
              }}>Void</button>
            )}
          </div>
        ) },
      ]} />
    </Card>
  )
}

function Printers({ base, rows, onChange }: { base: string; rows?: Row[]; onChange: () => void }) {
  const [f, setF] = useState({ name: '', host: '', port: '9100', dpi: '203', purpose: '' })
  const save = useAction(() => put(`${base}/printers/${encodeURIComponent(f.name)}`, {
    host: f.host, port: Number(f.port), dpi: Number(f.dpi), purpose: f.purpose || null }))
  const remove = useAction((name: string) => del(`${base}/printers/${encodeURIComponent(name)}`))
  return (
    <Card title="Label printers">
      <p className="muted">Zebra-compatible printers on their raw port (9100). AstraWMS must be able to reach them (on-premises
        or over a VPN); otherwise print from the browser or download the ZPL.</p>
      <Table rows={rows} empty="No printers" columns={[
        { header: 'Name', cell: (p) => String(p.name) },
        { header: 'Address', cell: (p) => `${String(p.host)}:${String(p.port)}` },
        { header: 'DPI', cell: (p) => String(p.dpi) },
        { header: 'Default for', cell: (p) => String(p.purpose ?? '') },
        { header: '', cell: (p) => <button className="small" disabled={remove.busy} onClick={async () => { await remove.run(String(p.name)); onChange() }}>Remove</button> },
      ]} />
      <div className="row">
        <Field label="Name"><input value={f.name} onChange={(e) => setF({ ...f, name: e.target.value.toUpperCase() })} size={10} /></Field>
        <Field label="Host / IP"><input value={f.host} onChange={(e) => setF({ ...f, host: e.target.value })} size={14} /></Field>
        <Field label="Port"><input type="number" min={9100} max={9199} value={f.port} onChange={(e) => setF({ ...f, port: e.target.value })} size={5} /></Field>
        <Field label="DPI"><select value={f.dpi} onChange={(e) => setF({ ...f, dpi: e.target.value })}>{['203', '300', '600'].map((d) => <option key={d}>{d}</option>)}</select></Field>
        <Field label="Default for"><select value={f.purpose} onChange={(e) => setF({ ...f, purpose: e.target.value })}>
          <option value="">—</option><option value="LOCATION">Location labels</option><option value="ITEM">Item labels</option><option value="LPN">LPN labels</option>
        </select></Field>
        <button className="primary" disabled={save.busy || !f.name || !f.host} onClick={async () => { if (await save.run()) onChange() }}>Save</button>
      </div>
      <ErrorBox error={save.error ?? remove.error} />
    </Card>
  )
}
