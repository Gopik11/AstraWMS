import { useState } from 'react'
import { get, post, put, type Row } from '../api'
import { useAuth } from '../auth'
import { Badge, Card, ErrorBox, Field, Page, Success, Table, fmtQty, useAction, useLoad, useSite } from '../ui'

/**
 * Slotting (ADR-0021): the item–location master. Each item's pick face (min/max rule), reserve zone, units per pallet
 * and velocity class; velocity comes from 30 days of picks unless set by hand. Fast movers get golden-zone suggestions
 * and a reslot moves the face (free stock at the old face becomes RF MOVE tasks).
 */
export default function Slotting() {
  const site = useSite()
  const { hasRole } = useAuth()
  const canEdit = hasRole('SOLUTION_ADMIN', 'INV_MANAGER')
  const base = `/api/v1/sites/${site}/inventory`
  const list = useLoad(() => get<Row[]>(`${base}/slotting`), [base])
  const [edit, setEdit] = useState<Row>()
  const reslot = useAction((owner: string, item: string, to: string) =>
    post<Row>(`${base}/slotting/${owner}/${item}/reslot`, { toLocation: to }))

  return (
    <Page title="Slotting" actions={<button onClick={list.reload}>Refresh</button>}>
      <p className="muted">Velocity from the last 30 days of picks (A: items making 80 % of picks, B: next 15 %, C: rest).
        Putaway sends pallets to the item's reserve zone; slow movers take the slots furthest down the path.</p>
      <ErrorBox error={list.error ?? reslot.error} />
      <Success>{reslot.result && `Pick face moved to ${String(reslot.result.pickFace)}; ${(reslot.result.moves as unknown[]).length} move task(s) created`}</Success>
      <Card>
        <Table rows={list.data} empty="No items with stock or a pick face" columns={[
          { header: 'Item', cell: (r) => `${String(r.owner_id)} / ${String(r.item_no)}` },
          { header: 'Velocity', cell: (r) => <span title={r.velocity_source === 'SET' ? 'set by hand' : 'computed from picks'}>
              <Badge value={String(r.velocity_class)} />{r.velocity_source === 'SET' ? ' (set)' : ''}</span> },
          { header: 'Picks 30 d', cell: (r) => String(r.picks_30d), align: 'right' },
          { header: 'Units 30 d', cell: (r) => fmtQty(r.units_picked), align: 'right' },
          { header: 'On hand', cell: (r) => fmtQty(r.on_hand), align: 'right' },
          { header: 'Pick face', cell: (r) => (r.pick_face ? `${String(r.pick_face)} (${fmtQty(r.min_qty)}–${fmtQty(r.max_qty)})` : '—') },
          { header: 'Reserve zone', cell: (r) => String(r.reserve_zone ?? 'any') },
          { header: 'Units / pallet', cell: (r) => (r.units_per_pallet == null ? '' : fmtQty(r.units_per_pallet)), align: 'right' },
          { header: 'Suggestion', cell: (r) => (r.suggested_face ? <span title={String(r.suggestion_reason ?? '')}>
              → {String(r.suggested_face)}: {String(r.suggestion_reason ?? '')}</span> : '') },
          { header: '', cell: (r) => canEdit && (
            <div className="row">
              {r.suggested_face != null && r.pick_face != null && (
                <button className="small" disabled={reslot.busy}
                        onClick={async () => { if (await reslot.run(String(r.owner_id), String(r.item_no), String(r.suggested_face))) list.reload() }}>
                  Reslot</button>
              )}
              <button className="small" onClick={() => setEdit(r)}>Edit</button>
            </div>
          ) },
        ]} />
      </Card>
      {edit && <EditSlotting key={`${String(edit.owner_id)}/${String(edit.item_no)}`} base={base} row={edit}
                             onDone={() => { setEdit(undefined); list.reload() }} />}
    </Page>
  )
}

function EditSlotting({ base, row, onDone }: { base: string; row: Row; onDone: () => void }) {
  const owner = String(row.owner_id)
  const item = String(row.item_no)
  const [f, setF] = useState({
    reserveZone: String(row.reserve_zone ?? ''),
    unitsPerPallet: row.units_per_pallet == null ? '' : String(row.units_per_pallet),
    velocityClass: row.velocity_source === 'SET' ? String(row.velocity_class) : '',
    face: '', min: '', max: '',
  })
  const save = useAction(() => put(`${base}/slotting/${owner}/${item}`, {
    reserveZone: f.reserveZone || null, unitsPerPallet: f.unitsPerPallet ? Number(f.unitsPerPallet) : null,
    velocityClass: f.velocityClass || null,
  }))
  const reslot = useAction(() => post(`${base}/slotting/${owner}/${item}/reslot`, {
    toLocation: f.face, minQty: f.min ? Number(f.min) : null, maxQty: f.max ? Number(f.max) : null,
  }))
  const set = (k: keyof typeof f) => (e: { target: { value: string } }) => setF({ ...f, [k]: e.target.value.toUpperCase() })
  return (
    <Card title={`Slotting of ${owner} / ${item}`}>
      <div className="row">
        <Field label="Reserve zone" hint="Zone ID; blank = any"><input value={f.reserveZone} onChange={set('reserveZone')} size={8} /></Field>
        <Field label="Units per pallet"><input type="number" min={1} value={f.unitsPerPallet} onChange={set('unitsPerPallet')} size={6} /></Field>
        <Field label="Velocity">
          <select value={f.velocityClass} onChange={set('velocityClass')}>
            <option value="">Computed from picks</option><option value="A">A (fast)</option><option value="B">B</option><option value="C">C (slow)</option>
          </select>
        </Field>
        <button className="primary" disabled={save.busy} onClick={async () => { if (await save.run()) onDone() }}>Save</button>
      </div>
      <h3>Move the pick face</h3>
      <div className="row">
        <Field label="New pick face"><input value={f.face} onChange={set('face')} size={10} /></Field>
        <Field label="Min" hint="Blank = keep"><input type="number" min={0} value={f.min} onChange={set('min')} size={5} /></Field>
        <Field label="Max" hint="Blank = keep"><input type="number" min={1} value={f.max} onChange={set('max')} size={5} /></Field>
        <button disabled={reslot.busy || !f.face} onClick={async () => { if (await reslot.run()) onDone() }}>Reslot</button>
      </div>
      <ErrorBox error={save.error ?? reslot.error} />
    </Card>
  )
}
