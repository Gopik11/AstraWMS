import { useState } from 'react'
import { get, post, query, type Row } from '../api'
import { useAuth } from '../auth'
import { Card, ErrorBox, Field, Page, Success, Table, useAction } from '../ui'

/**
 * ERP simulator for test environments: sends SAP DELVRY07 IDocs to the adapter's IDoc port, as SAP middleware
 * would, and shows what the simulated SAP backend posted. Not part of a production deployment.
 */

function docnum(): string {
  return String(Date.now()).padStart(16, '0')
}

interface LineInput { item: string; qty: string; uom: string }

const SAP_UOM: Record<string, string> = { EA: 'ST', CS: 'KAR', PAL: 'PAL' }

export default function ErpSimulator() {
  const { hasRole } = useAuth()
  // The IDoc port accepts only the ERP integration role (as the SAP middleware account would have).
  const canSend = hasRole('ERP_INTEGRATION')
  return (
    <Page title="ERP simulator (SAP)">
      <div className="alert">Test environments only: these send SAP IDocs as SAP would. Deliveries need the SAP plant mapped
        to a site (Master data → SAP plant → site).</div>
      {!canSend && <div className="alert">Sending IDocs needs the ERP_INTEGRATION role. You can check IDoc status and
        {hasRole('SOLUTION_ADMIN') ? ' see what the simulated SAP posted.' : ' nothing else here.'}</div>}
      <div className="grid">
        {canSend && <Delivery inbound />}
        {canSend && <Delivery inbound={false} />}
        {canSend && <ReturnDelivery />}
        {canSend && <Cancel />}
        <IdocStatus />
      </div>
      {hasRole('SOLUTION_ADMIN') && <Documents />}
    </Page>
  )
}

function Delivery({ inbound }: { inbound: boolean }) {
  const [f, setF] = useState({ vbeln: '', werks: '1000', partner: inbound ? 'V-100' : 'C-1', name: '', carrier: 'UPSN', date: '' })
  const [lines, setLines] = useState<LineInput[]>([{ item: '', qty: '', uom: 'EA' }])
  const send = useAction(() => {
    const id = docnum()
    const date = (f.date || new Date(Date.now() + 86_400_000).toISOString().slice(0, 10)).replace(/-/g, '')
    const idoc = {
      DOCNUM: id,
      MESTYP: inbound ? 'SHP_IBDLV_SAVE_REPLICA' : 'SHP_OBDLV_SAVE_REPLICA',
      E1EDL20: { VBELN: f.vbeln, LFART: inbound ? 'EL' : 'LF', WERKS: f.werks, ...(inbound ? { LIFEX: `ASN-${f.vbeln}` } : {}) },
      E1ADRM1: inbound
        ? [{ PARTNER_Q: 'LF', PARTNER_ID: f.partner }]
        : [{ PARTNER_Q: 'WE', PARTNER_ID: f.partner, NAME1: f.name || f.partner }, ...(f.carrier ? [{ PARTNER_Q: 'SP', PARTNER_ID: f.carrier }] : [])],
      E1EDT13: [{ QUALF: inbound ? '007' : '006', NTANF: date, NTANZ: '120000' }],
      E1EDL24: lines.filter((l) => l.item && l.qty).map((l, i) => ({
        POSNR: String((i + 1) * 10).padStart(6, '0'), MATNR: l.item, LFIMG: l.qty, VRKME: SAP_UOM[l.uom] ?? l.uom,
      })),
    }
    return post<Row>('/api/v1/sap/idocs/delvry07', idoc)
  })
  const setLine = (i: number, k: keyof LineInput, v: string) => setLines(lines.map((l, j) => (j === i ? { ...l, [k]: v } : l)))

  return (
    <Card title={inbound ? 'Inbound delivery (ASN)' : 'Outbound delivery'}>
      <div className="row">
        <Field label="Delivery no."><input value={f.vbeln} onChange={(e) => setF({ ...f, vbeln: e.target.value })} placeholder={inbound ? '0180000001' : '0080000001'} size={11} /></Field>
        <Field label="Plant"><input value={f.werks} onChange={(e) => setF({ ...f, werks: e.target.value })} size={5} /></Field>
        <Field label={inbound ? 'Vendor' : 'Customer'}><input value={f.partner} onChange={(e) => setF({ ...f, partner: e.target.value })} size={8} /></Field>
      </div>
      {!inbound && (
        <div className="row">
          <Field label="Ship-to name"><input value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} /></Field>
          <Field label="Carrier"><input value={f.carrier} onChange={(e) => setF({ ...f, carrier: e.target.value })} size={6} /></Field>
        </div>
      )}
      <Field label={inbound ? 'Arrival date' : 'Goods issue date'}><input type="date" value={f.date} onChange={(e) => setF({ ...f, date: e.target.value })} /></Field>
      {lines.map((l, i) => (
        <div className="row" key={i}>
          <Field label={`Line ${(i + 1) * 10} item`}><input value={l.item} onChange={(e) => setLine(i, 'item', e.target.value.toUpperCase())} size={10} /></Field>
          <Field label="Qty"><input type="number" min="0" value={l.qty} onChange={(e) => setLine(i, 'qty', e.target.value)} size={5} /></Field>
          <Field label="UoM"><select value={l.uom} onChange={(e) => setLine(i, 'uom', e.target.value)}>{Object.keys(SAP_UOM).map((u) => <option key={u}>{u}</option>)}</select></Field>
        </div>
      ))}
      <div className="actions">
        <button type="button" onClick={() => setLines([...lines, { item: '', qty: '', uom: 'EA' }])}>Add line</button>
        <button className="primary" disabled={send.busy || !f.vbeln} onClick={() => void send.run()}>Send IDoc</button>
      </div>
      <ErrorBox error={send.error} />
      <Success>{send.result && `IDoc ${String(send.result.idocNumber)} accepted (status ${String(send.result.status)})`}</Success>
    </Card>
  )
}

/** A returns delivery (LFART LR): becomes an RMA in AstraWMS (IF-RET-001). */
function ReturnDelivery() {
  const [f, setF] = useState({ vbeln: '', werks: '1000', customer: 'C-1', name: '', item: '', qty: '1', uom: 'EA' })
  const send = useAction(() => post<Row>('/api/v1/sap/idocs/delvry07', {
    DOCNUM: docnum(),
    MESTYP: 'SHP_IBDLV_SAVE_REPLICA',
    E1EDL20: { VBELN: f.vbeln, LFART: 'LR', WERKS: f.werks },
    E1ADRM1: [{ PARTNER_Q: 'AG', PARTNER_ID: f.customer, NAME1: f.name || f.customer }],
    E1EDL24: [{ POSNR: '000010', MATNR: f.item, LFIMG: f.qty, VRKME: SAP_UOM[f.uom] ?? f.uom }],
  }))
  return (
    <Card title="Customer return (RMA)">
      <div className="row">
        <Field label="Returns delivery no."><input value={f.vbeln} onChange={(e) => setF({ ...f, vbeln: e.target.value })} placeholder="0060000001" size={11} /></Field>
        <Field label="Plant"><input value={f.werks} onChange={(e) => setF({ ...f, werks: e.target.value })} size={5} /></Field>
        <Field label="Customer"><input value={f.customer} onChange={(e) => setF({ ...f, customer: e.target.value })} size={8} /></Field>
        <Field label="Name"><input value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} /></Field>
      </div>
      <div className="row">
        <Field label="Item"><input value={f.item} onChange={(e) => setF({ ...f, item: e.target.value.toUpperCase() })} size={10} /></Field>
        <Field label="Qty"><input type="number" min="0" value={f.qty} onChange={(e) => setF({ ...f, qty: e.target.value })} size={5} /></Field>
        <Field label="UoM"><select value={f.uom} onChange={(e) => setF({ ...f, uom: e.target.value })}>{Object.keys(SAP_UOM).map((u) => <option key={u}>{u}</option>)}</select></Field>
      </div>
      <button className="primary" disabled={send.busy || !f.vbeln || !f.item} onClick={() => void send.run()}>Send IDoc</button>
      <ErrorBox error={send.error} />
      <Success>{send.result && `IDoc ${String(send.result.idocNumber)} accepted; the RMA appears under Customer returns`}</Success>
    </Card>
  )
}

function Cancel() {
  const [vbeln, setVbeln] = useState('')
  const [werks, setWerks] = useState('1000')
  const send = useAction(() => post<Row>('/api/v1/sap/idocs/delvry07', {
    DOCNUM: docnum(), MESTYP: 'SHP_OBDLV_CHANGE', E1EDL20: { VBELN: vbeln, LFART: 'LF', WERKS: werks }, E1EDL18: [{ QUALF: 'DEL' }],
  }))
  return (
    <Card title="Cancel outbound delivery">
      <div className="row">
        <Field label="Delivery no."><input value={vbeln} onChange={(e) => setVbeln(e.target.value)} size={11} /></Field>
        <Field label="Plant"><input value={werks} onChange={(e) => setWerks(e.target.value)} size={5} /></Field>
      </div>
      <button className="primary" disabled={send.busy || !vbeln} onClick={() => void send.run()}>Send cancellation</button>
      <ErrorBox error={send.error} />
      <Success>{send.result && `Cancellation IDoc ${String(send.result.idocNumber)} sent; it reaches status 53 once AstraWMS accepts it`}</Success>
    </Card>
  )
}

function IdocStatus() {
  const [id, setId] = useState('')
  const check = useAction(() => get<Row>(`/api/v1/sap/idocs/${id}`))
  return (
    <Card title="IDoc status">
      <div className="row">
        <Field label="IDoc number"><input value={id} onChange={(e) => setId(e.target.value)} size={18} /></Field>
        <button disabled={check.busy || !id} onClick={() => void check.run()}>Check</button>
      </div>
      <ErrorBox error={check.error} />
      {check.result && <p>Status <strong>{String(check.result.status)}</strong> — {String(check.result.statusText ?? '')}</p>}
    </Card>
  )
}

function Documents() {
  const [vbeln, setVbeln] = useState('')
  const docs = useAction(() => get<Row[]>(`/mock-sap/documents${query({ vbeln })}`))
  return (
    <Card title="Posted in simulated SAP">
      <div className="row">
        <Field label="Delivery no. (optional)"><input value={vbeln} onChange={(e) => setVbeln(e.target.value)} size={11} /></Field>
        <button onClick={() => void docs.run()} disabled={docs.busy}>Show documents</button>
      </div>
      <ErrorBox error={docs.error} />
      {docs.result && <Table rows={docs.result} empty="No documents" columns={[
        { header: 'Material document', cell: (d) => `${String(d.materialDocument)}/${String(d.year)}` },
        { header: 'Type', cell: (d) => String(d.docType) },
        { header: 'Delivery', cell: (d) => String(d.vbeln ?? '') },
        { header: 'WMS txn (XBLNR)', cell: (d) => String(d.xblnr) },
      ]} />}
    </Card>
  )
}
