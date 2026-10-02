import { useState, type FormEvent } from 'react'
import { get, post, query, type Row } from '../api'
import { Badge, Card, ErrorBox, Field, Page, Success, Table, fmtQty, useAction, useSite } from '../ui'

interface PackView {
  erpDocNo: string
  status: string
  carrierScac?: string
  lines: { erp_line_ref: string; item_no: string; qty_picked: number; qty_packed: number; base_uom?: string }[]
  cartons: { sscc: string; carton_type: string; status: string; weight_kg?: number; tracking_no?: string; items?: string }[]
}

/** Pack station (§5.1): scan the order, open cartons, scan items in, close with weight → carrier label. */
export default function PackStation() {
  const site = useSite()
  const base = `/api/v1/sites/${site}/outbound`
  const [doc, setDoc] = useState('')
  const [carton, setCarton] = useState<string>()
  const [line, setLine] = useState('')
  const [qty, setQty] = useState('1')
  const [weight, setWeight] = useState('')
  const view = useAction((d: string) => get<PackView>(`${base}/orders/${d}/packing`))
  const open = useAction(() => post<Row>(`${base}/orders/${doc}/cartons`, { cartonType: 'BOX' }))
  const pack = useAction(() => post<Row>(`${base}/cartons/${carton}/items`, { erpLineRef: line, qty: Number(qty) }))
  const close = useAction(() => post<Row>(`${base}/cartons/${carton}/close`, { weightKg: weight ? Number(weight) : null }))
  const label = useAction((sscc: string) => get<Row>(`${base}/cartons/${sscc}`))
  const refresh = () => void view.run(doc)
  const v = view.result

  const scanOrder = async (e: FormEvent) => {
    e.preventDefault()
    setCarton(undefined)
    const r = await view.run(doc.trim())
    if (r) {
      setLine(r.lines.find((l) => l.qty_picked > l.qty_packed)?.erp_line_ref ?? '')
      setCarton(r.cartons.find((c) => c.status === 'OPEN')?.sscc)
    }
  }

  return (
    <Page title="Pack station">
      <Card>
        <form className="row" onSubmit={scanOrder}>
          <Field label="Scan order / delivery"><input autoFocus value={doc} onChange={(e) => setDoc(e.target.value)} required /></Field>
          <button className="primary" disabled={view.busy}>Open order</button>
        </form>
        <ErrorBox error={view.error} />
      </Card>
      {v && (
        <div className="grid">
          <Card title={`Order ${v.erpDocNo}`}>
            <div className="facts"><span>Status <Badge value={v.status} /></span><span>Carrier {v.carrierScac ?? '—'}</span></div>
            <Table rows={v.lines} columns={[
              { header: 'Line', cell: (l) => l.erp_line_ref },
              { header: 'Item', cell: (l) => l.item_no },
              { header: 'Picked', cell: (l) => fmtQty(l.qty_picked), align: 'right' },
              { header: 'Packed', cell: (l) => fmtQty(l.qty_packed), align: 'right' },
              { header: '', cell: (l) => (Number(l.qty_packed) >= Number(l.qty_picked) ? <Badge value="DONE" /> : null) },
            ]} />
            <h3>Cartons</h3>
            <Table rows={v.cartons} empty="No cartons yet" onRow={(c) => (c.status === 'OPEN' ? setCarton(c.sscc) : void label.run(c.sscc))} columns={[
              { header: 'SSCC', cell: (c) => c.sscc },
              { header: 'Status', cell: (c) => <Badge value={c.status} /> },
              { header: 'Weight', cell: (c) => fmtQty(c.weight_kg), align: 'right' },
              { header: 'Tracking', cell: (c) => c.tracking_no },
            ]} />
          </Card>
          <Card title={carton ? `Carton ${carton}` : 'Carton'}>
            {!carton && (
              <button className="primary" disabled={open.busy}
                      onClick={async () => { const c = await open.run(); if (c) { setCarton(String(c.sscc)); refresh() } }}>
                Open new carton
              </button>
            )}
            {carton && (
              <>
                <form className="row" onSubmit={async (e) => { e.preventDefault(); if (await pack.run()) refresh() }}>
                  <Field label="Line">
                    <select value={line} onChange={(e) => setLine(e.target.value)}>
                      {v.lines.map((l) => <option key={l.erp_line_ref} value={l.erp_line_ref}>{l.erp_line_ref} · {l.item_no}</option>)}
                    </select>
                  </Field>
                  <Field label="Qty"><input type="number" min="1" step="any" value={qty} onChange={(e) => setQty(e.target.value)} size={4} /></Field>
                  <button className="primary">Pack</button>
                </form>
                <div className="row">
                  <Field label="Weight (kg)"><input type="number" min="0" step="0.001" value={weight} onChange={(e) => setWeight(e.target.value)} size={6} /></Field>
                  <button disabled={close.busy} onClick={async () => {
                    const c = await close.run()
                    if (c) { setCarton(undefined); setWeight(''); label.run(String(c.sscc)); refresh() }
                  }}>Close carton + label</button>
                  <button className="link" onClick={() => setCarton(undefined)}>Other carton</button>
                </div>
              </>
            )}
            <ErrorBox error={open.error ?? pack.error ?? close.error} />
          </Card>
          {label.result && <ShippingLabel carton={label.result} />}
          {v.status === 'PICKED' && v.cartons.length > 0 && v.cartons.every((c) => c.status !== 'OPEN') && (
            <LoadOrder base={base} order={v.erpDocNo} carrier={v.carrierScac} onDone={refresh} />
          )}
        </div>
      )}
    </Page>
  )
}

/**
 * Packed order onto a trailer (§5.3): the packer adds it to an OPEN load of the same carrier. Loads are opened and
 * closed by a supervisor (Loads page); a picker only loads.
 */
function LoadOrder({ base, order, carrier, onDone }: { base: string; order: string; carrier?: string; onDone: () => void }) {
  const loads = useAction(() => get<Row[]>(`${base}/loads${query({ status: 'OPEN' })}`))
  const [loadNo, setLoadNo] = useState('')
  const add = useAction(() => post<Row>(`${base}/loads/${loadNo}/orders`, { erpDocNo: order }))
  const open = (loads.result ?? []).filter((l) => !carrier || !l.carrier_scac || l.carrier_scac === carrier)
  return (
    <Card title="Load">
      {!loads.result && <button onClick={() => void loads.run()} disabled={loads.busy}>Show open loads{carrier ? ` for ${carrier}` : ''}</button>}
      {loads.result && open.length === 0 && <p className="muted">No open load for {carrier ?? 'this carrier'}; ask a supervisor to open one.</p>}
      {open.length > 0 && (
        <div className="row">
          <Field label="Open load">
            <select value={loadNo} onChange={(e) => setLoadNo(e.target.value)}>
              <option value="">Choose…</option>
              {open.map((l) => <option key={String(l.load_no)} value={String(l.load_no)}>
                {String(l.load_no)} · {String(l.carrier_scac ?? '')} · door {String(l.door ?? '—')}</option>)}
            </select>
          </Field>
          <button className="primary" disabled={!loadNo || add.busy} onClick={async () => { if (await add.run()) onDone() }}>Add to load</button>
        </div>
      )}
      <ErrorBox error={loads.error ?? add.error} />
      <Success>{add.result && `Order ${order} is on load ${loadNo}`}</Success>
    </Card>
  )
}

/** On-screen rendition of the carrier label; the ZPL goes to a label printer in production. */
function ShippingLabel({ carton }: { carton: Row }) {
  return (
    <Card title="Shipping label">
      <div className="label">
        <div className="label-carrier">{String(carton.carrier_scac ?? '')}</div>
        <div>Order {String(carton.erp_doc_no)}</div>
        <div className="label-barcode">{String(carton.tracking_no ?? '')}</div>
        <div className="label-barcode">(00) {String(carton.sscc)}</div>
        <div className="muted small">Weight {fmtQty(carton.weight_kg)} kg</div>
      </div>
      <Success>{carton.tracking_no ? 'Label ready to print' : null}</Success>
      <details><summary>ZPL</summary><pre className="zpl">{String(carton.label ?? '')}</pre></details>
    </Card>
  )
}
