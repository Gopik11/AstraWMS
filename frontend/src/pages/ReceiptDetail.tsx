import { useState, type FormEvent } from 'react'
import { Link, useParams } from 'react-router-dom'
import { get, newIdempotencyKey, post, type ReceiptDetail as Detail, type Row } from '../api'
import { useAuth } from '../auth'
import { Badge, Card, ErrorBox, Field, Page, Success, Table, fmtDate, fmtQty, useAction, useLoad, useSite } from '../ui'

const SHORT_REASONS = ['SHORT_VENDOR', 'DAMAGED', 'REFUSED', 'IN_TRANSIT']

/** One inbound delivery: lines and handling units; receive by line or SSCC, close, repost (IF-IB-001/002). */
export default function ReceiptDetail() {
  const { doc = '' } = useParams()
  const site = useSite()
  const { hasRole } = useAuth()
  const base = `/api/v1/sites/${site}/receipts/${doc}`
  const detail = useLoad(() => get<Detail>(base), [base])
  const appointments = useLoad(() => get<Row[]>(`/api/v1/sites/${site}/yard/appointments?docNo=${encodeURIComponent(doc)}`), [site, doc])
  const appt = appointments.data?.find((a) => a.status !== 'CANCELLED' && a.status !== 'NO_SHOW')
  const canReceive = hasRole('RECEIVER', 'SUPERVISOR')
  const d = detail.data
  const open = d && ['NOT_STARTED', 'IN_PROGRESS'].includes(d.header.status)

  return (
    <Page title={`Receipt ${doc}`} actions={<Link to="/receipts">All receipts</Link>}>
      <ErrorBox error={detail.error} />
      {d && (
        <>
          <Card>
            <div className="facts">
              <span>Status <Badge value={d.header.status} /></span>
              <span>Type {d.header.erpDocType}</span>
              <span>Vendor {d.header.vendorId}</span>
              <span>Expected {fmtDate(d.header.expectedArrivalUtc)}</span>
              {d.header.erpDocument && <span>ERP document {d.header.erpDocument}</span>}
              {appt && <span>Appointment <Link to="/yard">{String(appt.appt_no)}</Link> {fmtDate(appt.scheduled_start)}
                {appt.door ? ` · door ${String(appt.door)}` : ''} · <Badge value={String(appt.status)} />
                {appt.dwell_minutes != null ? ` · trailer ${String(appt.trailer_no ?? '')} in yard ${String(appt.dwell_minutes)} min` : ''}</span>}
            </div>
            {d.header.erpErrorText && <div className="alert error">ERP: {d.header.erpErrorText}</div>}
          </Card>
          <Card title="Lines">
            <Table rows={d.lines} columns={[
              { header: 'Line', cell: (l) => l.erpLineRef },
              { header: 'Owner', cell: (l) => l.ownerId },
              { header: 'Item', cell: (l) => l.itemNo },
              { header: 'Lot', cell: (l) => l.lotNo },
              { header: 'Expected', cell: (l) => `${fmtQty(l.qtyExpected)} ${l.uom}`, align: 'right' },
              { header: 'Received', cell: (l) => fmtQty(l.qtyReceived), align: 'right' },
              { header: 'Short reason', cell: (l) => l.shortReason },
            ]} />
          </Card>
          {d.handlingUnits.length > 0 && (
            <Card title="Handling units (SSCC)">
              <Table rows={d.handlingUnits} columns={[
                { header: 'SSCC', cell: (h) => h.sscc },
                { header: 'Line', cell: (h) => h.erpLineRef },
                { header: 'Qty', cell: (h) => fmtQty(h.qty), align: 'right' },
                { header: 'Received', cell: (h) => (h.received ? 'yes' : '') },
              ]} />
            </Card>
          )}
          {canReceive && open && <ReceiveForms base={base} detail={d} onChange={detail.reload} />}
          {canReceive && open && <CloseForm base={base} detail={d} onChange={detail.reload} />}
          {hasRole('SUPERVISOR') && d.header.status === 'POSTING_FAILED' && <Repost base={base} onChange={detail.reload} />}
        </>
      )}
    </Page>
  )
}

function ReceiveForms({ base, detail, onChange }: { base: string; detail: Detail; onChange: () => void }) {
  const lines = detail.lines.filter((l) => l.qtyReceived < l.qtyExpected)
  const [line, setLine] = useState(lines[0]?.erpLineRef ?? '')
  const current = detail.lines.find((l) => l.erpLineRef === line)
  const [form, setForm] = useState({ qty: '', uom: current?.uom ?? 'EA', lotNo: '', expiryDate: '', lpnId: '', locationId: '', serials: '' })
  const [sscc, setSscc] = useState('')
  const [ssccLocation, setSsccLocation] = useState('')
  const [key, setKey] = useState(newIdempotencyKey('rcv'))
  const receive = useAction(() => post(`${base}/lines/${line}/receive`, {
    qty: Number(form.qty), uom: form.uom, lotNo: form.lotNo || null, expiryDate: form.expiryDate || null,
    lpnId: form.lpnId || null, locationId: form.locationId,
    serials: form.serials.split(/[\s,]+/).filter(Boolean),
  }, { idempotencyKey: key }))
  const receiveSscc = useAction(() => post(`${base}/sscc/${sscc.replace(/^\(?00\)?/, '')}/receive`,
    { locationId: ssccLocation }, { idempotencyKey: newIdempotencyKey('sscc') }))
  const set = (k: keyof typeof form) => (e: { target: { value: string } }) => setForm({ ...form, [k]: e.target.value })

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    if (await receive.run()) {
      setKey(newIdempotencyKey('rcv'))
      setForm({ ...form, qty: '', lpnId: '', serials: '' })
      onChange()
    }
  }
  return (
    <div className="grid">
      <Card title="Receive a line">
        <form onSubmit={submit} className="form">
          <Field label="Line">
            <select value={line} onChange={(e) => setLine(e.target.value)}>
              {lines.map((l) => <option key={l.erpLineRef} value={l.erpLineRef}>{l.erpLineRef} · {l.itemNo} ({fmtQty(l.qtyExpected - l.qtyReceived)} {l.uom} open)</option>)}
            </select>
          </Field>
          <div className="row">
            <Field label="Quantity"><input type="number" step="any" min="0" value={form.qty} onChange={set('qty')} required /></Field>
            <Field label="UoM"><input value={form.uom} onChange={set('uom')} required size={4} /></Field>
          </div>
          <div className="row">
            <Field label="Lot"><input value={form.lotNo} onChange={set('lotNo')} /></Field>
            <Field label="Expiry"><input type="date" value={form.expiryDate} onChange={set('expiryDate')} /></Field>
          </div>
          <div className="row">
            <Field label="LPN" hint="Empty = new pallet ID"><input value={form.lpnId} onChange={set('lpnId')} /></Field>
            <Field label="Dock location"><input value={form.locationId} onChange={set('locationId')} required /></Field>
          </div>
          <Field label="Serials" hint="Serial-tracked items only"><textarea rows={2} value={form.serials} onChange={set('serials')} /></Field>
          <ErrorBox error={receive.error} />
          <Success>{receive.result ? 'Received' : null}</Success>
          <button className="primary" disabled={receive.busy || !line}>Receive</button>
        </form>
      </Card>
      {detail.handlingUnits.some((h) => !h.received) && (
        <Card title="Receive a pallet by SSCC">
          <form className="form" onSubmit={async (e) => (e.preventDefault(), (await receiveSscc.run()) && onChange())}>
            <Field label="SSCC"><input value={sscc} onChange={(e) => setSscc(e.target.value)} required /></Field>
            <Field label="Dock location"><input value={ssccLocation} onChange={(e) => setSsccLocation(e.target.value)} required /></Field>
            <ErrorBox error={receiveSscc.error} />
            <button className="primary" disabled={receiveSscc.busy}>Receive pallet</button>
          </form>
        </Card>
      )}
    </div>
  )
}

function CloseForm({ base, detail, onChange }: { base: string; detail: Detail; onChange: () => void }) {
  const shortLines = detail.lines.filter((l) => l.qtyReceived < l.qtyExpected)
  const [reasons, setReasons] = useState<Record<string, string>>({})
  const close = useAction(() => post(`${base}/close`, { shortReasons: reasons }))
  return (
    <Card title="Close receipt">
      <p className="muted">Closing sends the receipt confirmation to the ERP. Lines received short need a reason.</p>
      {shortLines.map((l) => (
        <Field key={l.erpLineRef} label={`Line ${l.erpLineRef}: ${fmtQty(l.qtyExpected - l.qtyReceived)} ${l.uom} short`}>
          <select value={reasons[l.erpLineRef] ?? ''} onChange={(e) => setReasons({ ...reasons, [l.erpLineRef]: e.target.value })}>
            <option value="">— reason —</option>
            {SHORT_REASONS.map((r) => <option key={r}>{r}</option>)}
          </select>
        </Field>
      ))}
      <ErrorBox error={close.error} />
      <button className="primary" disabled={close.busy} onClick={async () => (await close.run()) && onChange()}>Close and confirm to ERP</button>
    </Card>
  )
}

function Repost({ base, onChange }: { base: string; onChange: () => void }) {
  const repost = useAction(() => post(`${base}/repost`))
  return (
    <Card title="ERP posting failed">
      <p className="muted">Fix the cause in the ERP, then repost with the same WMS transaction ID.</p>
      <ErrorBox error={repost.error} />
      <button className="primary" disabled={repost.busy} onClick={async () => (await repost.run()) !== undefined && onChange()}>Repost</button>
    </Card>
  )
}
