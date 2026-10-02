import { useState, type FormEvent, type ReactNode } from 'react'
import { del, get, post, put, query, type Page as ApiPage, type Row } from '../api'
import { Card, ErrorBox, Field, Page, Success, Table, fmtQty, useAction, useLoad, useSite } from '../ui'

const LOCATION_TYPES = ['RACK', 'SHELF', 'FLOOR', 'BULK', 'DOOR', 'DOCK', 'STAGING_IN', 'STAGING_OUT', 'STAGING']
const ZONE_TYPES = ['RESERVE', 'PICK', 'DOCK', 'RECEIVING', 'RETURNS', 'SHIPPING', 'QC', 'FREEZER', 'CHILLED', 'HAZMAT']

function FormCard({ title, onSubmit, action, children, busy, error, ok }: {
  title: string; onSubmit: () => void; action: string; children: ReactNode; busy: boolean; error: unknown; ok?: ReactNode
}) {
  return (
    <Card title={title}>
      <form className="form" onSubmit={(e: FormEvent) => (e.preventDefault(), onSubmit())}>
        {children}
        <ErrorBox error={error} />
        <Success>{ok}</Success>
        <button className="primary" disabled={busy}>{action}</button>
      </form>
    </Card>
  )
}

/** Master data administration (UX-015): site, zones, locations, items, ERP plant mapping, release mode. */
export default function MasterData() {
  const site = useSite()
  // Sends every location of the site to the other services again, e.g. after an upgrade added zone types (ADR-0019).
  const republish = useAction(() => post<{ locationsRepublished: number }>(`/api/v1/sites/${site}/locations/republish`))
  return (
    <Page title={`Master data · ${site}`} actions={
      <button disabled={republish.busy} onClick={() => void republish.run()}
              title="Send all locations of this site to inventory, tasks and outbound again">Republish locations</button>
    }>
      <ErrorBox error={republish.error} />
      <Success>{republish.result && `${republish.result.locationsRepublished} location(s) republished; the other services update within seconds`}</Success>
      <div className="grid">
        <SiteForm site={site} />
        <ZoneForm site={site} />
        <LocationForm site={site} />
        <GenerateForm site={site} />
        <ItemForm site={site} />
        <PlantMapping site={site} />
        <ReleaseMode site={site} />
        <AllocationPolicy site={site} />
        <OwnerRules site={site} />
      </div>
      <Locations site={site} />
      <Items />
    </Page>
  )
}

function SiteForm({ site }: { site: string }) {
  const [f, setF] = useState({ name: '', timeZone: 'UTC', erpSite: '' })
  const save = useAction(() => put(`/api/v1/sites/${site}`, f))
  return (
    <FormCard title={`Site ${site}`} action="Save site" onSubmit={() => void save.run()} busy={save.busy} error={save.error}
              ok={save.done && `Site ${site} saved`}>
      <Field label="Name"><input value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} required /></Field>
      <div className="row">
        <Field label="Time zone"><input value={f.timeZone} onChange={(e) => setF({ ...f, timeZone: e.target.value })} required /></Field>
        <Field label="ERP plant"><input value={f.erpSite} onChange={(e) => setF({ ...f, erpSite: e.target.value })} required size={6} /></Field>
      </div>
    </FormCard>
  )
}

function ZoneForm({ site }: { site: string }) {
  const [f, setF] = useState({ zoneId: '', zoneType: 'RESERVE', erpBucket: '0001', temperatureClass: '', hazmatAllowed: false })
  const save = useAction(() => put<Row>(`/api/v1/sites/${site}/zones/${f.zoneId}`, {
    zoneType: f.zoneType, erpBucket: f.erpBucket, temperatureClass: f.temperatureClass || null, hazmatAllowed: f.hazmatAllowed,
  }))
  return (
    <FormCard title="Zone" action="Save zone" onSubmit={() => void save.run()} busy={save.busy} error={save.error}
              ok={save.result && `Zone ${f.zoneId} saved`}>
      <div className="row">
        <Field label="Zone ID"><input value={f.zoneId} onChange={(e) => setF({ ...f, zoneId: e.target.value.toUpperCase() })} required size={8} /></Field>
        <Field label="Type"><select value={f.zoneType} onChange={(e) => setF({ ...f, zoneType: e.target.value })}>{ZONE_TYPES.map((t) => <option key={t}>{t}</option>)}</select></Field>
      </div>
      <div className="row">
        <Field label="ERP storage location"><input value={f.erpBucket} onChange={(e) => setF({ ...f, erpBucket: e.target.value })} required size={6} /></Field>
        <Field label="Temperature"><input value={f.temperatureClass} onChange={(e) => setF({ ...f, temperatureClass: e.target.value })} size={8} placeholder="e.g. FROZEN" /></Field>
      </div>
      <label className="check"><input type="checkbox" checked={f.hazmatAllowed} onChange={(e) => setF({ ...f, hazmatAllowed: e.target.checked })} /> Hazmat allowed</label>
    </FormCard>
  )
}

function LocationForm({ site }: { site: string }) {
  const [f, setF] = useState({ locationId: '', zoneId: '', locationType: 'RACK', pickSeq: '' })
  const save = useAction(() => put<Row>(`/api/v1/sites/${site}/locations/${f.locationId}`, {
    zoneId: f.zoneId, locationType: f.locationType, pickSeq: f.pickSeq ? Number(f.pickSeq) : null,
  }))
  return (
    <FormCard title="Location" action="Save location" onSubmit={() => void save.run()} busy={save.busy} error={save.error}
              ok={save.result && `Location ${String(save.result.locationId)} saved · check digit ${String(save.result.checkDigit)}`}>
      <div className="row">
        <Field label="Location ID"><input value={f.locationId} onChange={(e) => setF({ ...f, locationId: e.target.value.toUpperCase() })} required size={10} /></Field>
        <Field label="Zone"><input value={f.zoneId} onChange={(e) => setF({ ...f, zoneId: e.target.value.toUpperCase() })} required size={8} /></Field>
      </div>
      <div className="row">
        <Field label="Type"><select value={f.locationType} onChange={(e) => setF({ ...f, locationType: e.target.value })}>{LOCATION_TYPES.map((t) => <option key={t}>{t}</option>)}</select></Field>
        <Field label="Pick sequence"><input type="number" value={f.pickSeq} onChange={(e) => setF({ ...f, pickSeq: e.target.value })} size={5} /></Field>
      </div>
    </FormCard>
  )
}

function GenerateForm({ site }: { site: string }) {
  const [f, setF] = useState({ zoneId: '', aisles: 'A,B', bayFrom: '1', bayTo: '3', levels: '1,2', positionFrom: '1', positionTo: '2',
    pattern: '{aisle}-{bay}-{level}{position}', locationType: 'RACK' })
  const gen = useAction(() => post<Row>(`/api/v1/sites/${site}/zones/${f.zoneId}/locations/generate`, {
    aisles: f.aisles.split(',').map((s) => s.trim()).filter(Boolean), bayFrom: Number(f.bayFrom), bayTo: Number(f.bayTo),
    levels: f.levels.split(',').map((s) => s.trim()).filter(Boolean), positionFrom: Number(f.positionFrom),
    positionTo: Number(f.positionTo), pattern: f.pattern, locationType: f.locationType,
  }))
  const set = (k: keyof typeof f) => (e: { target: { value: string } }) => setF({ ...f, [k]: e.target.value })
  return (
    <FormCard title="Generate locations" action="Generate" onSubmit={() => void gen.run()} busy={gen.busy} error={gen.error}
              ok={gen.result && `Created ${String(gen.result.created)}, updated ${String(gen.result.updated)}, unchanged ${String(gen.result.unchanged)}`}>
      <div className="row">
        <Field label="Zone"><input value={f.zoneId} onChange={(e) => setF({ ...f, zoneId: e.target.value.toUpperCase() })} required size={8} /></Field>
        <Field label="Aisles"><input value={f.aisles} onChange={set('aisles')} size={8} /></Field>
        <Field label="Levels"><input value={f.levels} onChange={set('levels')} size={6} /></Field>
      </div>
      <div className="row">
        <Field label="Bays"><span className="row tight"><input value={f.bayFrom} onChange={set('bayFrom')} size={3} />–<input value={f.bayTo} onChange={set('bayTo')} size={3} /></span></Field>
        <Field label="Positions"><span className="row tight"><input value={f.positionFrom} onChange={set('positionFrom')} size={3} />–<input value={f.positionTo} onChange={set('positionTo')} size={3} /></span></Field>
      </div>
      <Field label="ID pattern"><input value={f.pattern} onChange={set('pattern')} /></Field>
    </FormCard>
  )
}

function ItemForm({ site }: { site: string }) {
  const [f, setF] = useState({ ownerId: '', itemNo: '', description: '', baseUom: 'EA', standardCost: '', lotControlled: false,
    serialControl: 'NONE', caseQty: '', gtin: '' })
  const save = useAction(() => put<Row>(`/api/v1/items/${f.ownerId}/${f.itemNo}`, {
    description: f.description, baseUom: f.baseUom, status: 'ACTIVE',
    standardCost: f.standardCost ? Number(f.standardCost) : null,
    sites: [{ siteId: site, lotControlled: f.lotControlled, serialControl: f.serialControl }],
    uoms: f.caseQty ? [{ uom: 'CS', numerator: Number(f.caseQty), denominator: 1, gtin: f.gtin || null }] : [],
  }))
  const set = (k: keyof typeof f) => (e: { target: { value: string } }) => setF({ ...f, [k]: e.target.value })
  return (
    <FormCard title="Item" action="Save item" onSubmit={() => void save.run()} busy={save.busy} error={save.error}
              ok={save.result && `Item ${f.ownerId}/${f.itemNo} saved (version ${String(save.result.version)})`}>
      <div className="row">
        <Field label="Owner"><input value={f.ownerId} onChange={(e) => setF({ ...f, ownerId: e.target.value.toUpperCase() })} required size={8} /></Field>
        <Field label="Item no."><input value={f.itemNo} onChange={(e) => setF({ ...f, itemNo: e.target.value.toUpperCase() })} required size={10} /></Field>
      </div>
      <Field label="Description"><input value={f.description} onChange={set('description')} required /></Field>
      <div className="row">
        <Field label="Base UoM"><input value={f.baseUom} onChange={set('baseUom')} required size={4} /></Field>
        <Field label="Standard cost"><input type="number" step="0.0001" min="0" value={f.standardCost} onChange={set('standardCost')} size={8} /></Field>
        <Field label="Serials"><select value={f.serialControl} onChange={set('serialControl')}>{['NONE', 'INBOUND', 'OUTBOUND', 'FULL'].map((s) => <option key={s}>{s}</option>)}</select></Field>
      </div>
      <div className="row">
        <Field label="Case = n × base"><input type="number" min="1" value={f.caseQty} onChange={set('caseQty')} size={4} /></Field>
        <Field label="Case GTIN"><input value={f.gtin} onChange={set('gtin')} size={14} /></Field>
      </div>
      <label className="check"><input type="checkbox" checked={f.lotControlled} onChange={(e) => setF({ ...f, lotControlled: e.target.checked })} /> Lot controlled at {site}</label>
    </FormCard>
  )
}

function PlantMapping({ site }: { site: string }) {
  const [f, setF] = useState({ werks: '', timeZone: 'UTC', defaultOwner: '' })
  const save = useAction(() => put(`/api/v1/sap/site-map/${f.werks}`, { siteId: site, timeZone: f.timeZone, defaultOwner: f.defaultOwner }))
  return (
    <FormCard title="SAP plant → site" action="Save mapping" onSubmit={() => void save.run()}
              busy={save.busy} error={save.error} ok={save.done && `SAP plant ${f.werks} maps to ${site}`}>
      <div className="row">
        <Field label="SAP plant (WERKS)"><input value={f.werks} onChange={(e) => setF({ ...f, werks: e.target.value })} required size={6} /></Field>
        <Field label="Default owner"><input value={f.defaultOwner} onChange={(e) => setF({ ...f, defaultOwner: e.target.value.toUpperCase() })} required size={8} /></Field>
      </div>
      <Field label="Plant time zone"><input value={f.timeZone} onChange={(e) => setF({ ...f, timeZone: e.target.value })} required /></Field>
    </FormCard>
  )
}

function ReleaseMode({ site }: { site: string }) {
  const current = useLoad(() => get<{ releaseMode: string; packRequired: boolean; timezone: string; shipComplete: boolean }>(
    `/api/v1/sites/${site}/outbound/config`), [site])
  const save = useAction((mode: string) => put(`/api/v1/sites/${site}/outbound/config`, { releaseMode: mode }))
  const savePack = useAction((body: Record<string, unknown>) => put(`/api/v1/sites/${site}/outbound/config`, body))
  const [tz, setTz] = useState<string>()
  return (
    <Card title="Outbound release">
      <ErrorBox error={current.error ?? save.error} />
      <p>Current: <strong>{current.data?.releaseMode ?? '…'}</strong></p>
      <p className="muted">WAVELESS releases each order on receipt; WAVE keeps orders in the pool until a supervisor releases a wave.</p>
      <div className="actions">
        {['WAVELESS', 'WAVE'].map((m) => (
          <button key={m} disabled={save.busy || current.data?.releaseMode === m}
                  onClick={async () => { await save.run(m); current.reload() }}>{m}</button>
        ))}
      </div>
      <label className="check">
        <input type="checkbox" checked={current.data?.packRequired ?? false} disabled={savePack.busy}
               onChange={async (e) => { await savePack.run({ packRequired: e.target.checked }); current.reload() }} />
        Orders must be fully packed in closed cartons before loading / shipping
      </label>
      <label className="check">
        <input type="checkbox" checked={current.data?.shipComplete ?? false} disabled={savePack.busy}
               onChange={async (e) => { await savePack.run({ shipComplete: e.target.checked }); current.reload() }} />
        Orders ship complete (no partial shipments) unless the owner's rule says otherwise
      </label>
      <div className="row">
        <Field label="Site time zone" hint="Carrier cutoffs are local times, e.g. America/Chicago">
          <input value={tz ?? current.data?.timezone ?? ''} onChange={(e) => setTz(e.target.value)} />
        </Field>
        <button disabled={savePack.busy || tz == null}
                onClick={async () => { if (await savePack.run({ timezone: tz })) { setTz(undefined); current.reload() } }}>Save time zone</button>
      </div>
      <ErrorBox error={savePack.error} />
    </Card>
  )
}

/** Owner (3PL client) rules (ADR-0021): ship complete, pack list and label template per owner. */
function OwnerRules({ site }: { site: string }) {
  const url = `/api/v1/sites/${site}/outbound/owner-policies`
  const list = useLoad(() => get<Row[]>(url), [url])
  const [f, setF] = useState({ owner: '', shipComplete: '', packList: false, labelTemplate: '' })
  const save = useAction(() => put(`${url}/${f.owner}`, {
    shipComplete: f.shipComplete === '' ? null : f.shipComplete === 'true', packList: f.packList,
    labelTemplate: f.labelTemplate || null,
  }))
  return (
    <Card title="Owner rules">
      <p className="muted">Rules of a client that replace the site's for its orders. Its allocation policy is set below
        (allocation policy for an owner).</p>
      <Table rows={list.data} empty="No owner rules" columns={[
        { header: 'Owner', cell: (r) => String(r.owner_id) },
        { header: 'Ship complete', cell: (r) => (r.ship_complete == null ? 'as site' : r.ship_complete ? 'yes' : 'no') },
        { header: 'Pack list', cell: (r) => (r.pack_list ? 'yes' : 'no') },
        { header: 'Label', cell: (r) => String(r.label_template ?? 'STANDARD') },
        { header: 'Set by', cell: (r) => String(r.updated_by) },
      ]} />
      <div className="row">
        <Field label="Owner"><input value={f.owner} onChange={(e) => setF({ ...f, owner: e.target.value.toUpperCase() })} size={8} required /></Field>
        <Field label="Ship complete">
          <select value={f.shipComplete} onChange={(e) => setF({ ...f, shipComplete: e.target.value })}>
            <option value="">As the site</option><option value="true">Yes</option><option value="false">No</option>
          </select>
        </Field>
        <Field label="Label">
          <select value={f.labelTemplate} onChange={(e) => setF({ ...f, labelTemplate: e.target.value })}>
            <option value="">Standard (carrier label)</option><option value="RETAIL">Retail (+ content label)</option>
          </select>
        </Field>
        <label className="check"><input type="checkbox" checked={f.packList} onChange={(e) => setF({ ...f, packList: e.target.checked })} /> Pack list in each carton</label>
        <button className="primary" disabled={save.busy || !f.owner} onClick={async () => { if (await save.run()) list.reload() }}>Save</button>
      </div>
      <ErrorBox error={list.error ?? save.error} />
    </Card>
  )
}

interface Policy {
  lotRotation: string; otherRotation: string; pickFaceFirst: boolean; fullLpn: string; lotAffinity: boolean
  ownerId?: string | null; updatedBy?: string | null
}

/**
 * The site's allocation policy (ADR-0020): explicit rotation and full-LPN rules for every item; an owner can have its
 * own (ADR-0021).
 */
function AllocationPolicy({ site }: { site: string }) {
  const [owner, setOwner] = useState('')
  const [ownerInput, setOwnerInput] = useState('')
  const url = `/api/v1/sites/${site}/inventory/allocation-policy`
  const current = useLoad(() => get<Policy>(owner ? `${url}?ownerId=${encodeURIComponent(owner)}` : url), [url, owner])
  const overrides = useLoad(() => get<Policy[]>(`${url}/owners`), [url])
  const [f, setF] = useState<Policy>()
  const p = f ?? current.data
  const save = useAction(() => put<Policy>(url, { ...p, ownerId: owner || null }))
  const remove = useAction(() => del(`${url}/owners/${owner}`))
  if (!p) {
    return <Card title="Allocation policy"><ErrorBox error={current.error} /></Card>
  }
  const set = (k: keyof Policy, v: string | boolean) => setF({ ...p, [k]: v })
  const own = owner !== '' && p.ownerId === owner
  return (
    <Card title="Allocation policy" actions={
      <div className="row">
        <input placeholder="Owner (blank = site)" value={ownerInput} size={14}
               onChange={(e) => setOwnerInput(e.target.value.toUpperCase())} />
        <button className="small" onClick={() => { setF(undefined); setOwner(ownerInput.trim()) }}>Show</button>
      </div>
    }>
      <p className="muted">How {owner ? `owner ${owner}'s orders` : 'orders'} take stock at {site}.{' '}
        {owner && !own ? 'This owner follows the site policy; saving creates its own. ' : ''}
        {p.updatedBy ? `Set by ${p.updatedBy}.` : 'Defaults (not set yet).'}
        {overrides.data?.length ? ` Owners with their own policy: ${overrides.data.map((o) => o.ownerId).join(', ')}.` : ''}</p>
      <div className="row">
        <Field label="Lot-controlled items">
          <select value={p.lotRotation} onChange={(e) => set('lotRotation', e.target.value)}>
            <option value="FEFO">FEFO: first expiry first out</option><option value="FIFO">FIFO: first received first out</option>
          </select>
        </Field>
        <Field label="Other items">
          <select value={p.otherRotation} onChange={(e) => set('otherRotation', e.target.value)}>
            <option value="FIFO">FIFO: first received first out</option><option value="FEFO">FEFO: first expiry first out</option>
          </select>
        </Field>
      </div>
      <div className="row">
        <Field label="Reserve pallets (LPNs)">
          <select value={p.fullLpn} onChange={(e) => set('fullLpn', e.target.value)}>
            <option value="COVERED_ONLY">Whole pallet only when the order covers it; split only for items without a pick face</option>
            <option value="SPLIT_ALLOWED">Split pallets freely in rotation order</option>
            <option value="NEVER_SPLIT">Never split: whole pallets only, the rest from the pick face</option>
          </select>
        </Field>
        <label className="check"><input type="checkbox" checked={p.pickFaceFirst} onChange={(e) => set('pickFaceFirst', e.target.checked)} /> Pick faces first</label>
        <label className="check"><input type="checkbox" checked={p.lotAffinity} onChange={(e) => set('lotAffinity', e.target.checked)} /> Lot affinity: one lot per line when a lot can cover it</label>
      </div>
      <div className="actions">
        <button className="primary" disabled={save.busy || !f}
                onClick={async () => { if (await save.run()) { setF(undefined); current.reload(); overrides.reload() } }}>Save policy</button>
        {own && <button disabled={remove.busy}
                        onClick={async () => { if (await remove.run()) { setF(undefined); current.reload(); overrides.reload() } }}>
          Use the site policy for {owner}</button>}
      </div>
      <ErrorBox error={save.error ?? remove.error} />
      <Success>{save.done && 'Policy saved; it applies to the next allocation'}</Success>
    </Card>
  )
}

function Locations({ site }: { site: string }) {
  const [zone, setZone] = useState('')
  const list = useLoad(() => get<ApiPage<Row>>(`/api/v1/sites/${site}/locations${query({ zoneId: zone, limit: 200 })}`), [site, zone])
  return (
    <Card title="Locations" actions={
      <input placeholder="Filter by zone" value={zone} onChange={(e) => setZone(e.target.value.toUpperCase())} size={10} />
    }>
      <ErrorBox error={list.error} />
      <Table rows={list.data?.items} empty="No locations" columns={[
        { header: 'Location', cell: (l) => String(l.locationId) },
        { header: 'Zone', cell: (l) => `${String(l.zoneId)}${l.zoneType ? ` (${String(l.zoneType)})` : ''}` },
        { header: 'Type', cell: (l) => String(l.locationType) },
        { header: 'Check digit', cell: (l) => String(l.checkDigit ?? '') },
        { header: 'ERP bucket', cell: (l) => String(l.erpBucket) },
        { header: 'Pick seq', cell: (l) => String(l.pickSeq ?? ''), align: 'right' },
        { header: 'Status', cell: (l) => String(l.status) },
      ]} />
    </Card>
  )
}

function Items() {
  const [owner, setOwner] = useState('')
  const list = useLoad(() => (owner ? get<ApiPage<Row>>(`/api/v1/items/${owner}${query({ limit: 200 })}`) : Promise.resolve({ items: [] })), [owner])
  return (
    <Card title="Items" actions={<input placeholder="Owner, e.g. ACME" value={owner} onChange={(e) => setOwner(e.target.value.toUpperCase())} size={12} />}>
      <ErrorBox error={list.error} />
      <Table rows={list.data?.items} empty={owner ? 'No items' : 'Enter an owner'} columns={[
        { header: 'Item', cell: (i) => String(i.itemNo) },
        { header: 'Description', cell: (i) => String(i.description) },
        { header: 'Base UoM', cell: (i) => String(i.baseUom) },
        { header: 'Standard cost', cell: (i) => fmtQty(i.standardCost), align: 'right' },
        { header: 'Status', cell: (i) => String(i.status) },
        { header: 'Version', cell: (i) => String(i.version), align: 'right' },
      ]} />
    </Card>
  )
}
