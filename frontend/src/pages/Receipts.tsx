import { useNavigate, useSearchParams } from 'react-router-dom'
import { get, query, type ReceiptSummary } from '../api'
import { Badge, ErrorBox, Page, SearchBox, Table, fmtDate, useLoad, useParamSetter, useSite } from '../ui'

const STATUSES = ['', 'NOT_STARTED', 'IN_PROGRESS', 'CLOSED', 'CONFIRMED', 'POSTING_FAILED', 'CANCELLED']

export default function Receipts() {
  const site = useSite()
  const navigate = useNavigate()
  const [params, setParams] = useSearchParams()
  const setParam = useParamSetter(params, setParams)
  const status = params.get('status') ?? ''
  const q = params.get('q') ?? ''
  const list = useLoad(() => get<ReceiptSummary[]>(`/api/v1/sites/${site}/receipts${query({ status, q })}`), [site, status, q])

  return (
    <Page title="Receipts" actions={
      <>
        <SearchBox value={q} onSearch={(v) => setParam('q', v)} placeholder="Delivery, vendor, item, LPN, SSCC" />
        <select value={status} onChange={(e) => setParam('status', e.target.value)}>
          {STATUSES.map((s) => <option key={s} value={s}>{s || 'All statuses'}</option>)}
        </select>
      </>
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
