import { useNavigate, useSearchParams } from 'react-router-dom'
import { get, query, type ReceiptSummary } from '../api'
import { Badge, ErrorBox, Page, Table, fmtDate, useLoad, useSite } from '../ui'

const STATUSES = ['', 'NOT_STARTED', 'IN_PROGRESS', 'CLOSED', 'CONFIRMED', 'POSTING_FAILED', 'CANCELLED']

export default function Receipts() {
  const site = useSite()
  const navigate = useNavigate()
  const [params, setParams] = useSearchParams()
  const status = params.get('status') ?? ''
  const list = useLoad(() => get<ReceiptSummary[]>(`/api/v1/sites/${site}/receipts${query({ status })}`), [site, status])

  return (
    <Page title="Receipts" actions={
      <select value={status} onChange={(e) => setParams(e.target.value ? { status: e.target.value } : {})}>
        {STATUSES.map((s) => <option key={s} value={s}>{s || 'All statuses'}</option>)}
      </select>
    }>
      <ErrorBox error={list.error} />
      <Table rows={list.data} onRow={(r) => navigate(`/receipts/${r.erpDocNo}`)} empty="No receipts. Inbound deliveries arrive from the ERP." columns={[
        { header: 'Delivery', cell: (r) => r.erpDocNo },
        { header: 'Type', cell: (r) => r.erpDocType },
        { header: 'Vendor', cell: (r) => r.vendorId },
        { header: 'Expected', cell: (r) => fmtDate(r.expectedArrivalUtc) },
        { header: 'Lines', cell: (r) => r.lineCount, align: 'right' },
        { header: 'Status', cell: (r) => <Badge value={r.status} /> },
        { header: 'ERP document', cell: (r) => r.erpDocument ?? r.erpErrorText },
      ]} />
    </Page>
  )
}
