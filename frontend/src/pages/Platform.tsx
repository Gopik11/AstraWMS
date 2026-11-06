import { useState, type FormEvent } from 'react'
import { get, post, type Row } from '../api'
import { PLANS, TENANT_ID, TRANSITIONS, USERNAME } from '../platform'
import { Badge, Card, ConfirmButton, ErrorBox, Field, Page, Success, Table, fmtDate, useAction, useLoad } from '../ui'

/**
 * The platform view (ADR-0030 A), for PLATFORM_ADMIN only: the tenant registry across tenants. Creates tenants, changes
 * their status (every change needs a reason and is kept in the history) and creates a tenant's first administrator in
 * the identity provider. That administrator gets SOLUTION_ADMIN and a temporary password, shown here once.
 */
const API = '/api/v1/platform/tenants'

interface Created {
  tenantId: string
  username: string
  temporaryPassword: string
  roles: string[]
  passwordChangeRequired: boolean
}

export default function Platform() {
  const tenants = useLoad(() => get<Row[]>(API), [])
  const [selected, setSelected] = useState<string>()
  const counts = (tenants.data ?? []).reduce<Record<string, number>>((m, t) => {
    const s = String(t.status)
    m[s] = (m[s] ?? 0) + 1
    return m
  }, {})
  return (
    <Page title="Tenants (platform)" actions={<button onClick={tenants.reload} disabled={tenants.loading}>Refresh</button>}>
      <p className="muted">
        The tenant registry across all tenants. Changes reach every service within seconds; a suspended tenant's users
        are refused until it is active again.
      </p>
      <ErrorBox error={tenants.error} />
      <div className="tiles">
        {['ACTIVE', 'SUSPENDED', 'OFFBOARDING', 'PURGED'].map((s) => (
          <div key={s} className="tile"><span className="tile-n">{counts[s] ?? 0}</span><span className="tile-l">{s.toLowerCase()}</span></div>
        ))}
      </div>
      <div className="grid2">
        <Card title="Tenants">
          <Table rows={tenants.data} empty="No tenants yet" onRow={(r) => setSelected(String(r.tenant_id))} columns={[
            { header: 'Tenant', cell: (r) => <strong>{String(r.tenant_id)}</strong> },
            { header: 'Name', cell: (r) => String(r.display_name ?? '') },
            { header: 'Status', cell: (r) => <Badge value={String(r.status)} /> },
            { header: 'Plan', cell: (r) => String(r.plan ?? '') },
            { header: 'Created', cell: (r) => fmtDate(r.created_at) },
          ]} />
          <p className="muted small">Select a tenant to change its status or add an administrator.</p>
        </Card>
        <CreateTenant onCreated={(id) => { tenants.reload(); setSelected(id) }} />
      </div>
      {selected && <TenantDetail key={selected} tenantId={selected} onChanged={tenants.reload} onClose={() => setSelected(undefined)} />}
    </Page>
  )
}

function CreateTenant({ onCreated }: { onCreated: (tenantId: string) => void }) {
  const [form, setForm] = useState({ tenantId: '', displayName: '', plan: 'STANDARD', timeZone: 'UTC', reportingCurrency: 'USD' })
  const set = (k: keyof typeof form) => (e: { target: { value: string } }) => setForm({ ...form, [k]: e.target.value })
  const create = useAction(() => post<Row>(API, { ...form, tenantId: form.tenantId.trim() }))
  const valid = TENANT_ID.test(form.tenantId.trim())
  const submit = async (e: FormEvent) => {
    e.preventDefault()
    const r = await create.run()
    if (r) {
      onCreated(String(r.tenant_id))
      setForm({ ...form, tenantId: '', displayName: '' })
    }
  }
  return (
    <Card title="New tenant">
      <form className="form" onSubmit={submit}>
        <Field label="Tenant id" hint="1-64 letters, digits, '-' or '_'. Fixed for good: it is in every record and token.">
          <input value={form.tenantId} onChange={set('tenantId')} required maxLength={64} autoComplete="off" />
        </Field>
        <Field label="Display name"><input value={form.displayName} onChange={set('displayName')} placeholder="Defaults to the id" /></Field>
        <div className="row">
          <Field label="Plan">
            <select value={form.plan} onChange={set('plan')}>{PLANS.map((p) => <option key={p}>{p}</option>)}</select>
          </Field>
          <Field label="Time zone"><input value={form.timeZone} onChange={set('timeZone')} placeholder="e.g. Asia/Dubai" /></Field>
          <Field label="Reporting currency"><input value={form.reportingCurrency} onChange={set('reportingCurrency')} size={5} maxLength={3} /></Field>
        </div>
        <ErrorBox error={create.error} />
        <Success>{create.result && `Tenant ${String(create.result.tenant_id)} created; next, add its first administrator below.`}</Success>
        <div className="actions"><button className="primary" disabled={create.busy || !valid}>Create tenant</button></div>
      </form>
    </Card>
  )
}

function TenantDetail({ tenantId, onChanged, onClose }: { tenantId: string; onChanged: () => void; onClose: () => void }) {
  const tenant = useLoad(() => get<Row>(`${API}/${encodeURIComponent(tenantId)}`), [tenantId])
  const history = useLoad(() => get<Row[]>(`${API}/${encodeURIComponent(tenantId)}/history`), [tenantId])
  const admins = useLoad(() => get<Row[]>(`${API}/${encodeURIComponent(tenantId)}/admins`), [tenantId])
  const status = String(tenant.data?.status ?? '')
  return (
    <Card title={`Tenant ${tenantId}`} actions={<button className="small" onClick={onClose}>Close</button>}>
      <ErrorBox error={tenant.error} />
      {tenant.data && (
        <p>
          <Badge value={status} /> · {String(tenant.data.display_name ?? '')} · plan {String(tenant.data.plan ?? '')} ·{' '}
          {String(tenant.data.time_zone ?? '')} · {String(tenant.data.reporting_currency ?? '')}
          {tenant.data.cell ? ` · cell ${String(tenant.data.cell)}` : ''}
        </p>
      )}
      <div className="grid2">
        <div>
          <h3>Administrators</h3>
          <ErrorBox error={admins.error} />
          <Table rows={admins.data} empty="No administrator created here yet" columns={[
            { header: 'Username', cell: (r) => <strong>{String(r.username)}</strong> },
            { header: 'E-mail', cell: (r) => String(r.email ?? '') },
            { header: 'Created', cell: (r) => `${fmtDate(r.created_at)} by ${String(r.created_by ?? '')}` },
          ]} />
          {status === 'ACTIVE'
            ? <CreateAdmin tenantId={tenantId} onCreated={admins.reload} />
            : status && <p className="muted">Administrators can only be added while the tenant is active.</p>}
        </div>
        <div>
          <h3>Status</h3>
          {status && <ChangeStatus tenantId={tenantId} status={status} onChanged={() => { tenant.reload(); history.reload(); onChanged() }} />}
          <h3>History</h3>
          <ErrorBox error={history.error} />
          <Table rows={history.data} empty="No changes" columns={[
            { header: 'When', cell: (r) => fmtDate(r.changed_at) },
            { header: 'Change', cell: (r) => `${r.from_status ? String(r.from_status) + ' → ' : ''}${String(r.to_status)}` },
            { header: 'Reason', cell: (r) => String(r.reason ?? '') },
            { header: 'By', cell: (r) => String(r.changed_by ?? '') },
          ]} />
        </div>
      </div>
    </Card>
  )
}

function ChangeStatus({ tenantId, status, onChanged }: { tenantId: string; status: string; onChanged: () => void }) {
  const targets = TRANSITIONS[status] ?? []
  const [to, setTo] = useState(targets[0] ?? '')
  const [reason, setReason] = useState('')
  const change = useAction(() => post<Row>(`${API}/${encodeURIComponent(tenantId)}/status`, { status: to, reason: reason.trim() }))
  if (targets.length === 0) {
    return <p className="muted">A {status.toLowerCase()} tenant cannot be changed here.</p>
  }
  const warn = to === 'SUSPENDED' ? 'Every user of this tenant is refused until it is active again.'
    : to === 'OFFBOARDING' ? 'The tenant becomes read-only: its users can still read and export, but not write.' : ''
  return (
    <div className="form">
      <div className="row">
        <Field label="New status">
          <select value={to} onChange={(e) => setTo(e.target.value)}>{targets.map((t) => <option key={t}>{t}</option>)}</select>
        </Field>
        <Field label="Reason (kept in the history)"><input value={reason} onChange={(e) => setReason(e.target.value)} size={30} /></Field>
      </div>
      {warn && <p className="muted small">{warn}</p>}
      <ErrorBox error={change.error} />
      <div className="actions">
        <ConfirmButton label={`Set ${to}`} className="" disabled={change.busy || !reason.trim()}
                       question={`Set ${tenantId} to ${to}?`}
                       onConfirm={async () => { if (await change.run()) { setReason(''); onChanged() } }} />
      </div>
    </div>
  )
}

function CreateAdmin({ tenantId, onCreated }: { tenantId: string; onCreated: () => void }) {
  const [form, setForm] = useState({ username: '', email: '', firstName: '', lastName: '' })
  const set = (k: keyof typeof form) => (e: { target: { value: string } }) => setForm({ ...form, [k]: e.target.value })
  const create = useAction(() => post<Created>(`${API}/${encodeURIComponent(tenantId)}/admins`, {
    username: form.username.trim().toLowerCase(), email: form.email.trim() || null,
    firstName: form.firstName.trim() || null, lastName: form.lastName.trim() || null,
  }))
  const valid = USERNAME.test(form.username.trim().toLowerCase())
  const submit = async (e: FormEvent) => {
    e.preventDefault()
    if (await create.run()) {
      setForm({ username: '', email: '', firstName: '', lastName: '' })
      onCreated()
    }
  }
  const created = create.result
  return (
    <form className="form" onSubmit={submit}>
      <h3>Add an administrator</h3>
      <Field label="Username" hint="3-64 characters: lower-case letters, digits, '.', '_', '@' or '-'">
        <input value={form.username} onChange={set('username')} required autoComplete="off" />
      </Field>
      <div className="row">
        <Field label="E-mail"><input type="email" value={form.email} onChange={set('email')} /></Field>
        <Field label="First name"><input value={form.firstName} onChange={set('firstName')} /></Field>
        <Field label="Last name"><input value={form.lastName} onChange={set('lastName')} /></Field>
      </div>
      <ErrorBox error={create.error} />
      {created && (
        <div className="alert ok" role="status">
          <strong>{created.username}</strong> can now sign in to tenant {created.tenantId} with the temporary password{' '}
          <code style={{ userSelect: 'all' }}>{created.temporaryPassword}</code>
          <div className="small">
            Copy it now: it is not stored and will not be shown again. {created.passwordChangeRequired && 'They must choose a new password at first sign-in. '}
            Roles: {created.roles.join(', ')}.
          </div>
          <button type="button" className="small" onClick={() => void navigator.clipboard?.writeText(created.temporaryPassword)}>Copy password</button>{' '}
          <button type="button" className="small" onClick={create.clear}>Done</button>
        </div>
      )}
      <div className="actions"><button className="primary" disabled={create.busy || !valid}>Create administrator</button></div>
    </form>
  )
}
