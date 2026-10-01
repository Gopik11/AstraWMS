import { useSearchParams } from 'react-router-dom'
import { get, query, type Task } from '../api'
import { Badge, ErrorBox, Page, Table, fmtDate, fmtQty, useLoad, useSite } from '../ui'

const STATUSES = ['', 'RELEASED', 'ASSIGNED', 'EXCEPTION', 'COMPLETED', 'CANCELLED']

export default function Tasks() {
  const site = useSite()
  const [params, setParams] = useSearchParams()
  const status = params.get('status') ?? ''
  const list = useLoad(() => get<Task[]>(`/api/v1/sites/${site}/tasks${query({ status })}`), [site, status])
  return (
    <Page title="Tasks" actions={
      <>
        <select value={status} onChange={(e) => setParams(e.target.value ? { status: e.target.value } : {})}>
          {STATUSES.map((s) => <option key={s} value={s}>{s || 'All statuses'}</option>)}
        </select>
        <button onClick={list.reload}>Refresh</button>
      </>
    }>
      <ErrorBox error={list.error} />
      <Table rows={list.data} empty="No tasks" columns={[
        { header: 'Type', cell: (t) => t.taskType },
        { header: 'Status', cell: (t) => <Badge value={t.status} /> },
        { header: 'Prio', cell: (t) => t.priority, align: 'right' },
        { header: 'What', cell: (t) => (t.taskType === 'PUTAWAY' ? `LPN ${t.lpnId}` : `${fmtQty(t.qty)} × ${t.itemNo} · ${t.orderRef}`) },
        { header: 'From', cell: (t) => t.fromLocation },
        { header: 'To', cell: (t) => t.targetLocation },
        { header: 'Assigned to', cell: (t) => t.assignedTo },
        { header: 'Exception', cell: (t) => t.exceptionReason },
        { header: 'Created', cell: (t) => fmtDate(t.createdAt) },
      ]} />
    </Page>
  )
}
