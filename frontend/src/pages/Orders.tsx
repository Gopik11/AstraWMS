import { useNavigate, useSearchParams } from 'react-router-dom'
import { get, query, type Row } from '../api'
import { Badge, ErrorBox, Page, SearchBox, Table, fmtDate, useLoad, useParamSetter, useSite } from '../ui'

const STATUSES = ['', 'POOLED', 'RELEASED', 'BACKORDERED', 'PICKED', 'SHIPPED', 'CONFIRMED', 'SHIP_ERROR',
  'CANCEL_REQUESTED', 'CANCELLED']

export default function Orders() {
  const site = useSite()
  const navigate = useNavigate()
  const [params, setParams] = useSearchParams()
  const setParam = useParamSetter(params, setParams)
  const status = params.get('status') ?? ''
  const q = params.get('q') ?? ''
  const list = useLoad(() => get<Row[]>(`/api/v1/sites/${site}/outbound/orders${query({ status, q })}`), [site, status, q])

  return (
    <Page title="Outbound orders" actions={
      <>
        <SearchBox value={q} onSearch={(v) => setParam('q', v)} placeholder="Delivery, customer, item, LPN, SSCC" />
        <select value={status} onChange={(e) => setParam('status', e.target.value)}>
          {STATUSES.map((s) => <option key={s} value={s}>{s || 'All statuses'}</option>)}
        </select>
      </>
    }>
      <ErrorBox error={list.error} />
      <Table rows={list.data} onRow={(r) => navigate(`/orders/${String(r.erp_doc_no)}`)}
             empty="No orders. Outbound deliveries arrive from the ERP." columns={[
        { header: 'Delivery', cell: (r) => String(r.erp_doc_no) },
        { header: 'Customer', cell: (r) => String(r.ship_to_name ?? '') },
        { header: 'Type', cell: (r) => String(r.order_type) },
        { header: 'Carrier', cell: (r) => String(r.carrier_scac ?? '') },
        { header: 'Planned goods issue', cell: (r) => fmtDate(r.planned_gi_utc) },
        { header: 'Status', cell: (r) => <Badge value={String(r.status)} /> },
        { header: 'Short lines', cell: (r) => (Number(r.lines_short) > 0 ? String(r.lines_short) : ''), align: 'right' },
        { header: 'ERP document', cell: (r) => String(r.erp_document ?? '') },
      ]} />
    </Page>
  )
}
