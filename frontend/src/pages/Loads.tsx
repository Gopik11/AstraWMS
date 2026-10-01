import { useState } from 'react'
import { del, get, post, query, type Row } from '../api'
import { useAuth } from '../auth'
import { Badge, Card, ErrorBox, Field, Page, Success, Table, fmtDate, useAction, useLoad, useSite } from '../ui'

interface LoadView extends Row {
  load_no: string
  status: string
  orders: Row[]
}

/** Loading (§5.3): trailers per carrier; orders loaded by delivery or carton scan; close with seal → ship. */
export default function Loads() {
  const site = useSite()
  const { hasRole } = useAuth()
  const base = `/api/v1/sites/${site}/outbound/loads`
  const [status, setStatus] = useState('OPEN')
  const list = useLoad(() => get<Row[]>(`${base}${query({ status })}`), [base, status])
  const [selected, setSelected] = useState<string>()
  const [f, setF] = useState({ carrierScac: '', door: '', trailerNo: '' })
  const create = useAction(() => post<LoadView>(base, f))

  return (
    <Page title="Loads" actions={
      <select value={status} onChange={(e) => setStatus(e.target.value)}>
        {['', 'OPEN', 'CLOSED'].map((s) => <option key={s} value={s}>{s || 'All'}</option>)}
      </select>
    }>
      {hasRole('SUPERVISOR') && (
        <Card title="New load">
          <div className="row">
            <Field label="Carrier"><input value={f.carrierScac} onChange={(e) => setF({ ...f, carrierScac: e.target.value.toUpperCase() })} size={6} /></Field>
            <Field label="Door"><input value={f.door} onChange={(e) => setF({ ...f, door: e.target.value })} size={5} /></Field>
            <Field label="Trailer"><input value={f.trailerNo} onChange={(e) => setF({ ...f, trailerNo: e.target.value })} size={8} /></Field>
            <button className="primary" disabled={create.busy}
                    onClick={async () => { const l = await create.run(); if (l) { setSelected(l.load_no); list.reload() } }}>Create load</button>
          </div>
          <ErrorBox error={create.error} />
        </Card>
      )}
      <Card title="Loads">
        <ErrorBox error={list.error} />
        <Table rows={list.data} empty="No loads" onRow={(l) => setSelected(String(l.load_no))} columns={[
          { header: 'Load', cell: (l) => String(l.load_no) },
          { header: 'Carrier', cell: (l) => String(l.carrier_scac ?? '') },
          { header: 'Door', cell: (l) => String(l.door ?? '') },
          { header: 'Trailer', cell: (l) => String(l.trailer_no ?? '') },
          { header: 'Orders', cell: (l) => String(l.orders), align: 'right' },
          { header: 'Status', cell: (l) => <Badge value={String(l.status)} /> },
          { header: 'BOL', cell: (l) => String(l.bol_no ?? '') },
          { header: 'Created', cell: (l) => fmtDate(l.created_at) },
        ]} />
      </Card>
      {selected && <LoadDetail base={base} loadNo={selected} canClose={hasRole('SUPERVISOR')} onChange={list.reload} />}
    </Page>
  )
}

function LoadDetail({ base, loadNo, canClose, onChange }: { base: string; loadNo: string; canClose: boolean; onChange: () => void }) {
  const detail = useLoad(() => get<LoadView>(`${base}/${loadNo}`), [base, loadNo])
  const [scan, setScan] = useState('')
  const [seal, setSeal] = useState('')
  const add = useAction(() => post<LoadView>(`${base}/${loadNo}/orders`,
    /^\(?00\)?\d{18}$|^\d{18,20}$/.test(scan.trim()) ? { sscc: scan.trim() } : { erpDocNo: scan.trim() }))
  const remove = useAction((doc: string) => del<LoadView>(`${base}/${loadNo}/orders/${doc}`))
  const close = useAction(() => post<LoadView>(`${base}/${loadNo}/close`, { sealNo: seal || null }))
  const done = (r: unknown) => { if (r) { setScan(''); detail.reload(); onChange() } }
  const d = detail.data
  return (
    <Card title={`Load ${loadNo}`}>
      <ErrorBox error={detail.error} />
      {d && (
        <>
          <div className="facts">
            <span>Status <Badge value={d.status} /></span>
            <span>Carrier {String(d.carrier_scac ?? '—')}</span>
            {d.seal_no != null && <span>Seal {String(d.seal_no)}</span>}
            {d.bol_no != null && <span>BOL {String(d.bol_no)}</span>}
          </div>
          {d.status === 'OPEN' && (
            <form className="row" onSubmit={async (e) => { e.preventDefault(); done(await add.run()) }}>
              <Field label="Scan carton SSCC or enter delivery"><input autoFocus value={scan} onChange={(e) => setScan(e.target.value)} required /></Field>
              <button className="primary" disabled={add.busy}>Load</button>
            </form>
          )}
          <Table rows={d.orders} empty="Nothing loaded" columns={[
            { header: 'Delivery', cell: (o) => String(o.erp_doc_no) },
            { header: 'Cartons', cell: (o) => String(o.cartons), align: 'right' },
            { header: 'Status', cell: (o) => <Badge value={String(o.status)} /> },
            { header: 'ERP document', cell: (o) => String(o.erp_document ?? '') },
            { header: '', cell: (o) => d.status === 'OPEN' && canClose && (
              <button className="small" onClick={async () => done(await remove.run(String(o.erp_doc_no)))}>Unload</button>) },
          ]} />
          {canClose && d.status === 'OPEN' && d.orders.length > 0 && (
            <div className="row">
              <Field label="Seal no."><input value={seal} onChange={(e) => setSeal(e.target.value)} size={10} /></Field>
              <button className="primary" disabled={close.busy} onClick={async () => done(await close.run())}>Close trailer and ship</button>
            </div>
          )}
          <ErrorBox error={add.error ?? remove.error ?? close.error} />
          <Success>{close.result && `Shipped with ${String(close.result.bol_no)}`}</Success>
        </>
      )}
    </Card>
  )
}
