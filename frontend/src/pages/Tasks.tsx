import { useSearchParams } from 'react-router-dom'
import { get, query, type Task } from '../api'
import { Badge, ErrorBox, Page, SearchBox, Table, fmtDate, fmtQty, useLoad, useParamSetter, useSite } from '../ui'

const STATUSES = ['', 'RELEASED', 'ASSIGNED', 'EXCEPTION', 'COMPLETED', 'CANCELLED']
const TYPES = ['', 'RECEIVE', 'PUTAWAY', 'PICK', 'REPLEN', 'COUNT', 'RETURN']

function what(t: Task): string {
  switch (t.taskType) {
    case 'RECEIVE': return `${t.receiveKind ?? ''} ${t.docNo ?? ''}${t.partner ? ` · ${t.partner}` : ''}`
    case 'PUTAWAY': return `LPN ${t.lpnId}`
    case 'COUNT': return `Count ${t.fromLocation}`
    case 'REPLEN': return `${fmtQty(t.qty)} × ${t.itemNo}`
    default: return `${fmtQty(t.qty)} × ${t.itemNo} · ${t.orderRef ?? ''}`
  }
}

/** Where a putaway went: the confirmed location, plus the suggestion when the operator overrode it. */
function to(t: Task): string {
  const target = t.targetLocation ?? ''
  if (t.taskType === 'PUTAWAY' && t.status === 'COMPLETED' && t.suggestedLocation && t.suggestedLocation !== target) {
    return `${target} (suggested ${t.suggestedLocation})`
  }
  return target
}

export default function Tasks() {
  const site = useSite()
  const [params, setParams] = useSearchParams()
  const setParam = useParamSetter(params, setParams)
  const status = params.get('status') ?? ''
  const type = params.get('type') ?? ''
  const q = params.get('q') ?? ''
  const list = useLoad(() => get<Task[]>(`/api/v1/sites/${site}/tasks${query({ status, type, q })}`), [site, status, type, q])
  return (
    <Page title="Tasks" actions={
      <>
        <SearchBox value={q} onSearch={(v) => setParam('q', v)} placeholder="Delivery, order, item, LPN, location" />
        <select value={type} onChange={(e) => setParam('type', e.target.value)}>
          {TYPES.map((s) => <option key={s} value={s}>{s || 'All types'}</option>)}
        </select>
        <select value={status} onChange={(e) => setParam('status', e.target.value)}>
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
        { header: 'What', cell: what },
        { header: 'From', cell: (t) => t.fromLocation },
        { header: 'To', cell: to },
        { header: 'Assigned to', cell: (t) => t.assignedTo },
        { header: 'Exception', cell: (t) => t.exceptionReason },
        { header: 'Created', cell: (t) => fmtDate(t.createdAt) },
      ]} />
    </Page>
  )
}
