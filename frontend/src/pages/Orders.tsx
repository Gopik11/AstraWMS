import { useNavigate, useSearchParams } from 'react-router-dom'
import { get, query, type Row } from '../api'
import { Badge, ErrorBox, Page, Table, fmtDate, useLoad, useSite } from '../ui'

const STATUSES = ['', 'POOLED', 'RELEASED', 'BACKORDERED', 'PICKED', 'SHIPPED', 'CONFIRMED', 'SHIP_ERROR',
  'CANCEL_REQUESTED', 'CANCELLED']

export default function Orders() {
  const site = useSite()
  const navigate = useNavigate()
  const [params, setParams] = useSearchParams()
  const status = params.get('status') ?? ''
  const list = useLoad(() => get<Row[]>(`/api/v1/sites/${site}/outbound/orders${query({ status })}`), [site, status])

  return (
    <Page title="Outbound orders" actions={
      <select value={status} onChange={(e) => setParams(e.target.value ? { status: e.target.value } : {})}>
        {STATUSES.map((s) => <option key={s} value={s}>{s || 'All statuses'}</option>)}
      </select>
    }>
      <ErrorBox error={list.error} />
      <Table rows={list.data} onRow={(r) => navigate(`/orders/${String(r.erp_doc_no)}`)}
             empty="No orders. Outbound deliveries arrive from the ERP." columns={[
        { header: 'Delivery', cell: (r) => String(r.erp_doc_no) },
        { header: 'Type', cell: (r) => String(r.order_type) },
        { header: 'Carrier', cell: (r) => String(r.carrier_scac ?? '') },
        { header: 'Planned goods issue', cell: (r) => fmtDate(r.planned_gi_utc) },
        { header: 'Status', cell: (r) => <Badge value={String(r.status)} /> },
        { header: 'ERP document', cell: (r) => String(r.erp_document ?? '') },
      ]} />
    </Page>
  )
}
