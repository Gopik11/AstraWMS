import { useState, type FormEvent } from 'react'
import { get, query, type Balance, type Page as ApiPage, type Row, type Txn } from '../api'
import { Badge, Card, ErrorBox, Field, Page, Table, fmtDate, fmtQty, useAction, useSite } from '../ui'

type Tab = 'balances' | 'lpn' | 'transactions'

/** Stock inquiry (§6): balances by location / LPN / item, LPN contents, and the inventory ledger. */
export default function Inventory() {
  const [tab, setTab] = useState<Tab>('balances')
  return (
    <Page title="Stock inquiry">
      <div className="tabs">
        {(['balances', 'lpn', 'transactions'] as Tab[]).map((t) => (
          <button key={t} className={tab === t ? 'active' : ''} onClick={() => setTab(t)}>
            {t === 'balances' ? 'Balances' : t === 'lpn' ? 'LPN' : 'Transactions'}
          </button>
        ))}
      </div>
      {tab === 'balances' && <Balances />}
      {tab === 'lpn' && <Lpn />}
      {tab === 'transactions' && <Transactions />}
    </Page>
  )
}

function Balances() {
  const site = useSite()
  const [f, setF] = useState({ ownerId: '', itemNo: '', locationId: '', lpnId: '', status: '' })
  const search = useAction(() => get<ApiPage<Balance>>(`/api/v1/sites/${site}/inventory/balances${query({ ...f, limit: 200 })}`))
  const set = (k: keyof typeof f) => (e: { target: { value: string } }) => setF({ ...f, [k]: e.target.value })
  const submit = (e: FormEvent) => (e.preventDefault(), void search.run())
  return (
    <Card>
      <form className="row" onSubmit={submit}>
        <Field label="Owner"><input value={f.ownerId} onChange={set('ownerId')} size={8} /></Field>
        <Field label="Item"><input value={f.itemNo} onChange={set('itemNo')} size={10} /></Field>
        <Field label="Location"><input value={f.locationId} onChange={set('locationId')} size={10} /></Field>
        <Field label="LPN"><input value={f.lpnId} onChange={set('lpnId')} size={12} /></Field>
        <Field label="Status">
          <select value={f.status} onChange={set('status')}>
            {['', 'AVAILABLE', 'QI', 'BLOCKED', 'DAMAGED', 'EXPIRED'].map((s) => <option key={s} value={s}>{s || 'Any'}</option>)}
          </select>
        </Field>
        <button className="primary" disabled={search.busy}>Search</button>
      </form>
      <ErrorBox error={search.error} />
      {search.result && <Table rows={search.result.items} empty="No stock found" columns={[
        { header: 'Owner', cell: (b) => b.ownerId },
        { header: 'Item', cell: (b) => b.itemNo },
        { header: 'Lot', cell: (b) => b.lotNo },
        { header: 'Location', cell: (b) => b.locationId },
        { header: 'LPN', cell: (b) => b.lpnId },
        { header: 'Status', cell: (b) => <Badge value={b.status} /> },
        { header: 'On hand', cell: (b) => fmtQty(b.qty), align: 'right' },
        { header: 'Allocated', cell: (b) => fmtQty(b.allocatedQty), align: 'right' },
        { header: 'Available', cell: (b) => fmtQty(b.availableQty), align: 'right' },
        { header: 'Expiry', cell: (b) => b.expiryDate },
      ]} />}
    </Card>
  )
}

function Lpn() {
  const site = useSite()
  const [lpn, setLpn] = useState('')
  const load = useAction(() => get<Row & { contents: Balance[]; serials: string[] }>(`/api/v1/sites/${site}/inventory/lpns/${encodeURIComponent(lpn.trim())}`))
  return (
    <Card>
      <form className="row" onSubmit={(e) => (e.preventDefault(), void load.run())}>
        <Field label="Scan LPN / SSCC"><input autoFocus value={lpn} onChange={(e) => setLpn(e.target.value)} required /></Field>
        <button className="primary" disabled={load.busy}>Look up</button>
      </form>
      <ErrorBox error={load.error} />
      {load.result && (
        <>
          <div className="facts">
            <span>LPN {String(load.result.lpnId)}</span>
            <span>Owner {String(load.result.ownerId)}</span>
            <span>Location {String(load.result.locationId)}</span>
            <span>Type {String(load.result.lpnType)}</span>
          </div>
          <Table rows={load.result.contents} empty="Empty LPN" columns={[
            { header: 'Item', cell: (b) => b.itemNo },
            { header: 'Lot', cell: (b) => b.lotNo },
            { header: 'Status', cell: (b) => <Badge value={b.status} /> },
            { header: 'Qty', cell: (b) => fmtQty(b.qty), align: 'right' },
            { header: 'Allocated', cell: (b) => fmtQty(b.allocatedQty), align: 'right' },
          ]} />
          {load.result.serials.length > 0 && <p className="muted">Serials: {load.result.serials.join(', ')}</p>}
        </>
      )}
    </Card>
  )
}

function Transactions() {
  const site = useSite()
  const [f, setF] = useState({ itemNo: '', lpnId: '', locationId: '' })
  const search = useAction(() => get<ApiPage<Txn>>(`/api/v1/sites/${site}/inventory/transactions${query({ ...f, limit: 200 })}`))
  const set = (k: keyof typeof f) => (e: { target: { value: string } }) => setF({ ...f, [k]: e.target.value })
  return (
    <Card>
      <form className="row" onSubmit={(e) => (e.preventDefault(), void search.run())}>
        <Field label="Item"><input value={f.itemNo} onChange={set('itemNo')} size={10} /></Field>
        <Field label="LPN"><input value={f.lpnId} onChange={set('lpnId')} size={12} /></Field>
        <Field label="Location"><input value={f.locationId} onChange={set('locationId')} size={10} /></Field>
        <button className="primary" disabled={search.busy}>Search</button>
      </form>
      <ErrorBox error={search.error} />
      {search.result && <Table rows={search.result.items} empty="No transactions" columns={[
        { header: 'When', cell: (t) => fmtDate(t.occurredAt) },
        { header: 'Type', cell: (t) => t.txnType },
        { header: 'Item', cell: (t) => `${t.ownerId} / ${t.itemNo}${t.lotNo ? ` · ${t.lotNo}` : ''}` },
        { header: 'Location', cell: (t) => t.locationId },
        { header: 'LPN', cell: (t) => t.lpnId },
        { header: 'Δ', cell: (t) => fmtQty(t.qtyDelta), align: 'right' },
        { header: 'After', cell: (t) => fmtQty(t.qtyAfter), align: 'right' },
        { header: 'Reason', cell: (t) => t.reasonCode },
        { header: 'Document', cell: (t) => t.sourceDoc },
        { header: 'User', cell: (t) => t.userId },
      ]} />}
    </Card>
  )
}
