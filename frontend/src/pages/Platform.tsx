import { useState, type FormEvent, type ReactNode } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { get, post, put, type Row } from '../api'
import { TRANSITIONS, type TenantStatus } from '../tenancy'
import { Card, ConfirmButton, ErrorBox, Field, OneTimeSecret, Page, Success, Table, fmtDate, useAction, useLoad } from '../ui'

/** A tenant in the registry (ADR-0030 A). */
interface Tenant {
  tenant_id: string
  display_name: string
  status: TenantStatus
  plan: string
  cell: string
  time_zone: string
  reporting_currency: string
  created_at: string
  created_by: string
  status_changed_at: string
  status_changed_by: string
  status_reason: string | null
}

const PLANS = ['STANDARD', 'LARGE']
const ACTION_LABEL: Record<string, string> = { ACTIVE: 'Reactivate', SUSPENDED: 'Suspend', OFFBOARDING: 'Start offboarding' }
const STATUS_TONE: Record<string, string> = { ACTIVE: 'good', SUSPENDED: 'bad', OFFBOARDING: 'warn', PURGED: 'neutral' }

function StatusBadge({ status }: { status: string }) {
  return <span className={`badge ${STATUS_TONE[status] ?? 'neutral'}`}>{status}</span>
}

const base = '/api/v1/platform/tenants'

/** The operator's tenant console (ADR-0031): register, configure, suspend and offboard tenants; their first administrator. */
export default function Platform() {
  const { tenantId } = useParams()
  return tenantId ? <TenantDetail tenantId={tenantId} /> : <Tenants />
}

function Tenants() {
  const navigate = useNavigate()
  const tenants = useLoad(() => get<Tenant[]>(base), [])
  const [filter, setFilter] = useState('')
  const shown = (tenants.data ?? []).filter((t) => !filter
    || `${t.tenant_id} ${t.display_name}`.toLowerCase().includes(filter.toLowerCase()))
  return (
    <Page title="Tenants" actions={<input type="search" placeholder="Find a tenant" value={filter} onChange={(e) => setFilter(e.target.value)} />}>
      <p className="muted">
        Each customer is a tenant: its own users, sites, items and stock, isolated from every other tenant. Register the
        tenant, create its first administrator, and the administrator sets up the rest.
      </p>
      <ErrorBox error={tenants.error} />
      <Card title={`${shown.length} tenant${shown.length === 1 ? '' : 's'}`}>
        <Table rows={tenants.data ? shown : undefined} onRow={(t) => navigate(`/tenants/${encodeURIComponent(t.tenant_id)}`)} columns={[
          { header: 'Tenant', cell: (t) => <strong>{t.tenant_id}</strong> },
          { header: 'Name', cell: (t) => t.display_name },
          { header: 'Status', cell: (t) => <StatusBadge status={t.status} /> },
          { header: 'Plan', cell: (t) => t.plan },
          { header: 'Time zone', cell: (t) => t.time_zone },
          { header: 'Currency', cell: (t) => t.reporting_currency },
          { header: 'Since', cell: (t) => fmtDate(t.created_at) },
        ]} />
      </Card>
      <NewTenant onCreated={(id) => navigate(`/tenants/${encodeURIComponent(id)}`)} />
    </Page>
  )
}

function NewTenant({ onCreated }: { onCreated: (id: string) => void }) {
  const zone = Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'
  const [f, setF] = useState({ tenantId: '', displayName: '', plan: 'STANDARD', timeZone: zone, reportingCurrency: 'USD' })
  const create = useAction(() => post<Tenant>(base, { ...f, tenantId: f.tenantId.trim(), displayName: f.displayName.trim() || f.tenantId.trim() }))
  const submit = async (e: FormEvent) => {
    e.preventDefault()
    const t = await create.run()
    if (t) onCreated(t.tenant_id)
  }
  return (
    <Card title="Register a tenant">
      <form className="form" onSubmit={(e) => void submit(e)}>
        <Field label="Tenant id" hint="Letters, digits, '-' and '_'. It is stamped on every account and record of the tenant and cannot be changed.">
          <input required pattern="[A-Za-z0-9_\-]{1,64}" value={f.tenantId} onChange={(e) => setF({ ...f, tenantId: e.target.value })} placeholder="acme" />
        </Field>
        <Field label="Name"><input value={f.displayName} onChange={(e) => setF({ ...f, displayName: e.target.value })} placeholder="Acme Logistics" /></Field>
        <Field label="Plan" hint="Sets the tenant's request rate (STANDARD 100/s, LARGE 400/s)">
          <select value={f.plan} onChange={(e) => setF({ ...f, plan: e.target.value })}>{PLANS.map((p) => <option key={p}>{p}</option>)}</select>
        </Field>
        <Field label="Time zone"><input value={f.timeZone} onChange={(e) => setF({ ...f, timeZone: e.target.value })} /></Field>
        <Field label="Reporting currency"><input value={f.reportingCurrency} maxLength={3} onChange={(e) => setF({ ...f, reportingCurrency: e.target.value.toUpperCase() })} /></Field>
        <ErrorBox error={create.error} />
        <button className="primary" disabled={create.busy}>Register tenant</button>
      </form>
    </Card>
  )
}

function TenantDetail({ tenantId }: { tenantId: string }) {
  const navigate = useNavigate()
  const path = `${base}/${encodeURIComponent(tenantId)}`
  const tenant = useLoad(() => get<Tenant>(path), [path])
  const history = useLoad(() => get<Row[]>(`${path}/history`), [path])
  const admins = useLoad(() => get<Row[]>(`${path}/admins`), [path])
  const reload = () => { tenant.reload(); history.reload(); admins.reload() }
  const t = tenant.data
  return (
    <Page title={t ? `${t.display_name} (${t.tenant_id})` : tenantId}
          actions={<button className="small" onClick={() => navigate('/tenants')}>All tenants</button>}>
      <ErrorBox error={tenant.error} />
      {t && <>
        <div className="grid">
          <Status tenant={t} path={path} onChanged={reload} />
          <Onboarding tenant={t} admins={admins.data} />
        </div>
        <div className="grid">
          <Administrators tenant={t} path={path} admins={admins.data} error={admins.error} onCreated={admins.reload} />
          <Settings tenant={t} path={path} onSaved={reload} />
        </div>
        <Card title="Status history">
          <ErrorBox error={history.error} />
          <Table rows={history.data} columns={[
            { header: 'When', cell: (h) => fmtDate(h.changed_at) },
            { header: 'From', cell: (h) => String(h.from_status ?? '–') },
            { header: 'To', cell: (h) => <StatusBadge status={String(h.to_status)} /> },
            { header: 'Reason', cell: (h) => String(h.reason ?? '') },
            { header: 'By', cell: (h) => String(h.changed_by) },
          ]} />
        </Card>
      </>}
    </Page>
  )
}

function Status({ tenant, path, onChanged }: { tenant: Tenant; path: string; onChanged: () => void }) {
  const [reason, setReason] = useState('')
  const change = useAction(async (status: string) => {
    const r = await post<Tenant>(`${path}/status`, { status, reason })
    setReason('')
    onChanged()
    return r
  })
  const next = TRANSITIONS[tenant.status]
  return (
    <Card title="Status">
      <p><StatusBadge status={tenant.status} /> since {fmtDate(tenant.status_changed_at)} by {tenant.status_changed_by}
        {tenant.status_reason && <> — {tenant.status_reason}</>}</p>
      <ul className="muted small">
        <li><strong>Suspended</strong>: sign-in and API refused, the ERP's messages wait for replay; data kept.</li>
        <li><strong>Offboarding</strong>: read-only, for the customer's export; then purged after retention.</li>
      </ul>
      {next.length > 0 && <>
        <Field label="Reason (required, kept in the history)">
          <input value={reason} onChange={(e) => setReason(e.target.value)} placeholder="e.g. Invoice overdue" />
        </Field>
        <div className="row tight">
          {next.map((s) => (
            <ConfirmButton key={s} label={ACTION_LABEL[s] ?? s} disabled={change.busy || !reason.trim()}
                           className={s === 'ACTIVE' ? 'small primary' : 'small'}
                           question={`${ACTION_LABEL[s] ?? s} ${tenant.tenant_id}?`} onConfirm={() => void change.run(s)} />
          ))}
        </div>
      </>}
      <ErrorBox error={change.error} />
    </Card>
  )
}

function Onboarding({ tenant, admins }: { tenant: Tenant; admins: Row[] | undefined }) {
  const hasAdmin = (admins?.length ?? 0) > 0
  const step = (done: boolean, text: ReactNode) => <li className={done ? 'done' : 'todo'}>{text}</li>
  return (
    <Card title="Onboarding">
      <ol className="checklist">
        {step(true, <>Tenant <strong>{tenant.tenant_id}</strong> registered</>)}
        {step(tenant.status === 'ACTIVE', 'Tenant active')}
        {step(hasAdmin, 'First administrator created (below)')}
        {step(false, <>The administrator signs in at <code>{window.location.origin}</code>, sets a new password, then sets up
          sites, zones and locations, items and owners under <em>Admin → Master data</em></>)}
        {step(false, <>The administrator creates the tenant's users under <em>Admin → Users</em></>)}
        {step(false, <>ERP: SAP plant mapping under <em>Master data</em>, and an integration client for the tenant
          (an operator task, see the onboarding runbook)</>)}
      </ol>
    </Card>
  )
}

function Administrators({ tenant, path, admins, error, onCreated }: {
  tenant: Tenant; path: string; admins: Row[] | undefined; error: unknown; onCreated: () => void
}) {
  const empty = { username: '', email: '', firstName: '', lastName: '' }
  const [f, setF] = useState(empty)
  const [secret, setSecret] = useState<{ username: string; password: string }>()
  const create = useAction(async () => {
    const r = await post<{ username: string; temporaryPassword: string }>(`${path}/admins`, f)
    setSecret({ username: r.username, password: r.temporaryPassword })
    setF(empty)
    onCreated()
    return r
  })
  return (
    <Card title="Administrators">
      <ErrorBox error={error} />
      <Table rows={admins} empty="No administrator yet" columns={[
        { header: 'Username', cell: (a) => String(a.username) },
        { header: 'E-mail', cell: (a) => String(a.email ?? '') },
        { header: 'Created', cell: (a) => `${fmtDate(a.created_at)} by ${String(a.created_by)}` },
      ]} />
      {secret && <OneTimeSecret username={secret.username} secret={secret.password} onDone={() => setSecret(undefined)} />}
      {tenant.status === 'ACTIVE' ? (
        <form className="form" onSubmit={(e) => (e.preventDefault(), void create.run())}>
          <p className="muted small">The account gets the role SOLUTION_ADMIN and every site, owner and zone of the tenant.
            Further users are created by this administrator.</p>
          <Field label="Username"><input required value={f.username} onChange={(e) => setF({ ...f, username: e.target.value })} /></Field>
          <Field label="E-mail"><input type="email" value={f.email} onChange={(e) => setF({ ...f, email: e.target.value })} /></Field>
          <Field label="First name"><input value={f.firstName} onChange={(e) => setF({ ...f, firstName: e.target.value })} /></Field>
          <Field label="Last name"><input value={f.lastName} onChange={(e) => setF({ ...f, lastName: e.target.value })} /></Field>
          <ErrorBox error={create.error} />
          <button className="primary" disabled={create.busy}>Create administrator</button>
        </form>
      ) : <p className="muted">Administrators are created for active tenants only.</p>}
    </Card>
  )
}

function Settings({ tenant, path, onSaved }: { tenant: Tenant; path: string; onSaved: () => void }) {
  const [f, setF] = useState({ displayName: tenant.display_name, plan: tenant.plan, timeZone: tenant.time_zone,
    reportingCurrency: tenant.reporting_currency })
  const save = useAction(async () => {
    const r = await put<Tenant>(path, f)
    onSaved()
    return r
  })
  return (
    <Card title="Settings">
      <form className="form" onSubmit={(e) => (e.preventDefault(), void save.run())}>
        <Field label="Name"><input value={f.displayName} onChange={(e) => setF({ ...f, displayName: e.target.value })} /></Field>
        <Field label="Plan">
          <select value={f.plan} onChange={(e) => setF({ ...f, plan: e.target.value })}>
            {[...new Set([...PLANS, tenant.plan])].map((p) => <option key={p}>{p}</option>)}
          </select>
        </Field>
        <Field label="Time zone"><input value={f.timeZone} onChange={(e) => setF({ ...f, timeZone: e.target.value })} /></Field>
        <Field label="Reporting currency"><input value={f.reportingCurrency} maxLength={3} onChange={(e) => setF({ ...f, reportingCurrency: e.target.value.toUpperCase() })} /></Field>
        <p className="muted small">Cell: {tenant.cell} · created {fmtDate(tenant.created_at)} by {tenant.created_by}</p>
        <ErrorBox error={save.error} />
        <Success>{save.done && 'Saved'}</Success>
        <button className="primary" disabled={save.busy}>Save</button>
      </form>
    </Card>
  )
}
