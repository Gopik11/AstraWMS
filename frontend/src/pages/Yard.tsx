import { useState } from 'react'
import { Link } from 'react-router-dom'
import { get, post, type Row } from '../api'
import { useAuth } from '../auth'
import { Badge, Card, ErrorBox, Field, Page, Success, Table, fmtDate, useAction, useLoad, useSite } from '../ui'

interface Summary { inYard: Row[]; late: Row[]; doors: { door: string; current?: Row; next?: Row }[]; longestDwellMinutes: number }

function localDay(offsetDays = 0): string {
  const d = new Date()
  d.setDate(d.getDate() + offsetDays)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

/**
 * Yard and dock (ADR-0021): appointments of the day (a door can be booked before the ASN exists), gate check-in,
 * to door, check-out; who is at each door and how long trailers have been in the yard.
 */
export default function Yard() {
  const site = useSite()
  const { hasRole } = useAuth()
  const base = `/api/v1/sites/${site}/yard`
  const [day, setDay] = useState(localDay())
  const from = new Date(`${day}T00:00:00`).toISOString()
  const to = new Date(new Date(`${day}T00:00:00`).getTime() + 86_400_000).toISOString()
  const list = useLoad(() => get<Row[]>(`${base}/appointments?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`), [base, from, to])
  const summary = useLoad(() => get<Summary>(`${base}/summary`), [base])
  const act = useAction((no: string, step: string, body?: unknown) => post<Row>(`${base}/appointments/${no}/${step}`, body ?? {}))
  const reload = () => { list.reload(); summary.reload() }
  const run = async (no: string, step: string, body?: unknown) => { if (await act.run(no, step, body)) reload() }
  const canBook = hasRole('SUPERVISOR')
  const s = summary.data

  return (
    <Page title="Yard and dock" actions={<button onClick={reload}>Refresh</button>}>
      <ErrorBox error={summary.error} />
      {s && (
        <Card title={`In the yard: ${s.inYard.length}${s.inYard.length ? ` · longest dwell ${s.longestDwellMinutes} min` : ''}`}>
          <div className="tiles">
            {s.doors.map((d) => (
              <div key={d.door} className={`tile ${d.current ? 'tile-warn' : ''}`}>
                <span className="tile-n">{d.door}</span>
                <span className="tile-l">{d.current ? `${String(d.current.trailer_no ?? d.current.appt_no)} · ${String(d.current.door_minutes)} min` : 'free'}</span>
                {d.next && <span className="tile-d">next {String(d.next.appt_no)} {fmtDate(d.next.scheduled_start)}</span>}
              </div>
            ))}
          </div>
          {s.late.length > 0 && <div className="alert">Late arrivals: {s.late.map((a) => `${String(a.appt_no)} (${String(a.carrier_scac ?? '?')}, ${String(a.late_minutes)} min late)`).join(', ')}</div>}
          <Table rows={s.inYard} empty="No trailers in the yard" columns={[
            { header: 'Appointment', cell: (a) => String(a.appt_no) },
            { header: 'Trailer', cell: (a) => String(a.trailer_no ?? '') },
            { header: 'Status', cell: (a) => <Badge value={String(a.status)} /> },
            { header: 'Door', cell: (a) => String(a.door ?? '') },
            { header: 'Dwell', cell: (a) => <span className={Number(a.dwell_minutes) > 120 ? 'text-late' : ''}>{String(a.dwell_minutes)} min</span>, align: 'right' },
            { header: 'Delivery', cell: (a) => (a.doc_no ? <Link to={`/receipts/${String(a.doc_no)}`}>{String(a.doc_no)}</Link> : '') },
          ]} />
        </Card>
      )}
      <Card title="Appointments" actions={<input type="date" value={day} onChange={(e) => setDay(e.target.value)} />}>
        <ErrorBox error={list.error ?? act.error} />
        <Table rows={list.data} empty="No appointments this day" columns={[
          { header: 'No.', cell: (a) => String(a.appt_no) },
          { header: 'When', cell: (a) => `${fmtDate(a.scheduled_start)} – ${new Date(String(a.scheduled_end)).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}` },
          { header: 'Dir.', cell: (a) => String(a.direction).toLowerCase() },
          { header: 'Door', cell: (a) => String(a.door ?? '—') },
          { header: 'Carrier / trailer', cell: (a) => `${String(a.carrier_scac ?? '')} ${String(a.trailer_no ?? '')}` },
          { header: 'Delivery', cell: (a) => (a.doc_no ? <span>{a.asn_known ? <Link to={`/receipts/${String(a.doc_no)}`}>{String(a.doc_no)}</Link> : String(a.doc_no)}
              {a.direction === 'INBOUND' && !a.asn_known ? ' (no ASN yet)' : a.receipt_status ? ` · ${String(a.receipt_status)}` : ''}</span> : '—') },
          { header: 'Status', cell: (a) => <span><Badge value={String(a.status)} />{a.late_minutes != null ? <span className="text-late"> {String(a.late_minutes)} min late</span> : ''}</span> },
          { header: 'Dwell', cell: (a) => (a.dwell_minutes == null ? '' : `${String(a.dwell_minutes)} min`), align: 'right' },
          {
            header: '', cell: (a) => (
              <div className="row">
                {a.status === 'SCHEDULED' && <button className="small" disabled={act.busy}
                  onClick={() => void run(String(a.appt_no), 'check-in', { trailerNo: window.prompt('Trailer number', String(a.trailer_no ?? '')) || null })}>Check in</button>}
                {a.status === 'CHECKED_IN' && <button className="small" disabled={act.busy}
                  onClick={() => void run(String(a.appt_no), 'to-door', { door: a.door ? null : window.prompt('Door') })}>To door</button>}
                {(a.status === 'CHECKED_IN' || a.status === 'AT_DOOR') && <button className="small" disabled={act.busy}
                  onClick={() => void run(String(a.appt_no), 'check-out')}>Check out</button>}
                {a.status === 'SCHEDULED' && canBook && <>
                  <button className="small" disabled={act.busy} onClick={() => void run(String(a.appt_no), 'no-show')}>No-show</button>
                  <button className="small" disabled={act.busy} onClick={() => void run(String(a.appt_no), 'cancel')}>Cancel</button>
                </>}
              </div>
            ),
          },
        ]} />
      </Card>
      {canBook && <Book base={base} day={day} onDone={reload} />}
    </Page>
  )
}

function Book({ base, day, onDone }: { base: string; day: string; onDone: () => void }) {
  const [f, setF] = useState({ direction: 'INBOUND', door: '', carrierScac: '', trailerNo: '', docNo: '', time: '08:00', minutes: '60', note: '' })
  const book = useAction(() => {
    const start = new Date(`${day}T${f.time}:00`)
    return post<Row>(`${base}/appointments`, {
      direction: f.direction, door: f.door || null, carrierScac: f.carrierScac || null, trailerNo: f.trailerNo || null,
      docNo: f.docNo || null, note: f.note || null, start: start.toISOString(),
      end: new Date(start.getTime() + Number(f.minutes) * 60_000).toISOString(),
    })
  })
  const set = (k: keyof typeof f, upper = true) => (e: { target: { value: string } }) =>
    setF({ ...f, [k]: upper ? e.target.value.toUpperCase() : e.target.value })
  return (
    <Card title={`Book a door on ${day}`}>
      <div className="row">
        <Field label="Direction">
          <select value={f.direction} onChange={set('direction')}><option value="INBOUND">Inbound</option><option value="OUTBOUND">Outbound</option></select>
        </Field>
        <Field label="Door"><input value={f.door} onChange={set('door')} size={6} /></Field>
        <Field label="Time"><input type="time" value={f.time} onChange={set('time', false)} /></Field>
        <Field label="Minutes"><input type="number" min={15} step={15} value={f.minutes} onChange={set('minutes', false)} size={4} /></Field>
        <Field label="Carrier"><input value={f.carrierScac} onChange={set('carrierScac')} size={6} /></Field>
        <Field label="Trailer"><input value={f.trailerNo} onChange={set('trailerNo')} size={8} /></Field>
        <Field label="Delivery / ASN" hint="Optional: link it later"><input value={f.docNo} onChange={set('docNo', false)} size={12} /></Field>
        <Field label="Note"><input value={f.note} onChange={set('note', false)} size={16} /></Field>
        <button className="primary" disabled={book.busy} onClick={async () => { if (await book.run()) onDone() }}>Book</button>
      </div>
      <ErrorBox error={book.error} />
      <Success>{book.result && `Appointment ${String(book.result.appt_no)} booked`}</Success>
    </Card>
  )
}
