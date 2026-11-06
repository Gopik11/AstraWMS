import { useState } from 'react'
import { get, post, put, type Row } from '../api'
import { useAuth } from '../auth'
import { parseScope } from '../tenancy'
import { Card, ConfirmButton, ErrorBox, Field, OneTimeSecret, Page, Success, Table, fmtDate, useAction, useLoad } from '../ui'

/** A sign-in account of the tenant (ADR-0031). */
interface TenantUser {
  id: string
  username: string
  email: string | null
  firstName: string | null
  lastName: string | null
  enabled: boolean
  roles: string[]
  sites: string[]
  owners: string[]
  zones: string[]
  approvalLimit: string | null
  passwordChangeRequired: boolean
  createdAt: number | null
  /** False for integration and service accounts: listed, not changed here. */
  managed: boolean
}

const ROLE_HELP: Record<string, string> = {
  RECEIVER: 'Receiving, putaway, returns on RF',
  PICKER: 'Picking, packing, loading on RF',
  INV_ANALYST: 'Counts, stock inquiry, adjustments within limits',
  INV_MANAGER: 'Inventory approvals, transfers, store replenishment',
  SUPERVISOR: 'Floor control: waves, tasks, exceptions, approvals',
  QA_MANAGER: 'Quality holds and releases, recalls',
  SOLUTION_ADMIN: 'Master data, users, integration, operations',
}

const show = (list: string[]) => (list.length === 0 ? '–' : list.includes('*') ? 'all' : list.join(', '))

interface Form {
  username: string; email: string; firstName: string; lastName: string; roles: string[]
  sites: string; owners: string; zones: string; approvalLimit: string
}
const blank: Form = { username: '', email: '', firstName: '', lastName: '', roles: [], sites: '*', owners: '*', zones: '*', approvalLimit: '' }

const fromUser = (u: TenantUser): Form => ({
  username: u.username, email: u.email ?? '', firstName: u.firstName ?? '', lastName: u.lastName ?? '', roles: u.roles,
  sites: u.sites.join(', '), owners: u.owners.join(', '), zones: u.zones.join(', '), approvalLimit: u.approvalLimit ?? '',
})

const body = (f: Form) => ({
  email: f.email, firstName: f.firstName, lastName: f.lastName, roles: f.roles, sites: parseScope(f.sites),
  owners: parseScope(f.owners), zones: parseScope(f.zones), approvalLimit: f.approvalLimit,
})

/** The tenant's users, for its administrators (ADR-0031). Accounts live in the sign-in service; nothing here stores a password. */
export default function Users() {
  const { session } = useAuth()
  const users = useLoad(() => get<TenantUser[]>('/api/v1/users'), [])
  const roles = useLoad(() => get<string[]>('/api/v1/users/roles'), [])
  const audit = useLoad(() => get<Row[]>('/api/v1/users/audit?limit=50'), [])
  const [selected, setSelected] = useState<string>()
  const [filter, setFilter] = useState('')
  const [secret, setSecret] = useState<{ username: string; password: string }>()
  const reload = () => { users.reload(); audit.reload() }
  const current = users.data?.find((u) => u.id === selected)
  const shown = (users.data ?? []).filter((u) => !filter
    || `${u.username} ${u.firstName ?? ''} ${u.lastName ?? ''} ${u.email ?? ''} ${u.roles.join(' ')}`.toLowerCase().includes(filter.toLowerCase()))

  return (
    <Page title={`Users · ${session.tenant}`}
          actions={<input type="search" placeholder="Find a user or role" value={filter} onChange={(e) => setFilter(e.target.value)} />}>
      <ErrorBox error={users.error} />
      {secret && <OneTimeSecret username={secret.username} secret={secret.password} onDone={() => setSecret(undefined)} />}
      <Card title={`${shown.length} user${shown.length === 1 ? '' : 's'}`}>
        <Table rows={users.data ? shown : undefined} onRow={(u) => setSelected(u.id)} columns={[
          { header: 'User', cell: (u) => <><strong>{u.username}</strong>{(u.firstName || u.lastName) && <div className="muted small">{[u.firstName, u.lastName].filter(Boolean).join(' ')}</div>}</> },
          { header: 'Roles', cell: (u) => u.roles.join(', ') || '–' },
          { header: 'Sites', cell: (u) => show(u.sites) },
          { header: 'Owners', cell: (u) => show(u.owners) },
          { header: 'Zones', cell: (u) => show(u.zones) },
          { header: 'Approval limit', cell: (u) => u.approvalLimit ?? '–', align: 'right' },
          { header: 'Status', cell: (u) => <>
            <span className={`badge ${u.enabled ? 'good' : 'bad'}`}>{u.enabled ? 'ENABLED' : 'DISABLED'}</span>
            {u.passwordChangeRequired && <div className="muted small">must set a password</div>}
            {!u.managed && <div className="muted small">integration account</div>}
          </> },
        ]} />
      </Card>
      <div className="grid">
        {current
          ? <EditUser key={current.id} user={current} roles={roles.data ?? []} self={current.username === session.userName}
                      onSaved={reload} onPassword={(p) => setSecret({ username: current.username, password: p })}
                      onClose={() => setSelected(undefined)} />
          : <NewUser roles={roles.data ?? []} onCreated={(u, p) => { setSecret({ username: u.username, password: p }); reload() }} />}
        <Card title="Changes">
          <ErrorBox error={audit.error} />
          <Table rows={audit.data} empty="No changes yet" columns={[
            { header: 'When', cell: (a) => fmtDate(a.changed_at) },
            { header: 'User', cell: (a) => String(a.username) },
            { header: 'What', cell: (a) => <>{String(a.action)}<div className="muted small">{String(a.detail ?? '')}</div></> },
            { header: 'By', cell: (a) => String(a.changed_by) },
          ]} />
        </Card>
      </div>
    </Page>
  )
}

function RolePicks({ roles, value, onChange, disabled }: { roles: string[]; value: string[]; onChange: (v: string[]) => void; disabled?: boolean }) {
  return (
    <div className="role-picks">
      {roles.map((r) => (
        <label key={r} title={ROLE_HELP[r]}>
          <input type="checkbox" disabled={disabled} checked={value.includes(r)}
                 onChange={(e) => onChange(e.target.checked ? [...value, r] : value.filter((x) => x !== r))} />
          {r}
        </label>
      ))}
    </div>
  )
}

function ScopeFields({ f, setF, disabled }: { f: Form; setF: (f: Form) => void; disabled?: boolean }) {
  return <>
    <Field label="Sites" hint="Site ids separated by commas, or * for all"><input disabled={disabled} value={f.sites} onChange={(e) => setF({ ...f, sites: e.target.value })} /></Field>
    <Field label="Owners" hint="Owner ids, or * for all"><input disabled={disabled} value={f.owners} onChange={(e) => setF({ ...f, owners: e.target.value })} /></Field>
    <Field label="Zones" hint="Zone ids, or * for all"><input disabled={disabled} value={f.zones} onChange={(e) => setF({ ...f, zones: e.target.value })} /></Field>
    <Field label="Approval limit" hint="Largest adjustment value this user may approve; empty for none">
      <input disabled={disabled} inputMode="decimal" value={f.approvalLimit} onChange={(e) => setF({ ...f, approvalLimit: e.target.value })} />
    </Field>
  </>
}

function NewUser({ roles, onCreated }: { roles: string[]; onCreated: (u: TenantUser, password: string) => void }) {
  const [f, setF] = useState<Form>(blank)
  const create = useAction(async () => {
    const r = await post<{ user: TenantUser; temporaryPassword: string }>('/api/v1/users', { username: f.username, ...body(f) })
    onCreated(r.user, r.temporaryPassword)
    setF(blank)
    return r
  })
  return (
    <Card title="New user">
      <form className="form" onSubmit={(e) => (e.preventDefault(), void create.run())}>
        <Field label="Username" hint="Unique across the system, e.g. first.last@company"><input required value={f.username} onChange={(e) => setF({ ...f, username: e.target.value })} /></Field>
        <Field label="E-mail"><input type="email" value={f.email} onChange={(e) => setF({ ...f, email: e.target.value })} /></Field>
        <Field label="First name"><input value={f.firstName} onChange={(e) => setF({ ...f, firstName: e.target.value })} /></Field>
        <Field label="Last name"><input value={f.lastName} onChange={(e) => setF({ ...f, lastName: e.target.value })} /></Field>
        <Field label="Roles"><RolePicks roles={roles} value={f.roles} onChange={(v) => setF({ ...f, roles: v })} /></Field>
        <ScopeFields f={f} setF={setF} />
        <ErrorBox error={create.error} />
        <button className="primary" disabled={create.busy || f.roles.length === 0}>Create user</button>
      </form>
    </Card>
  )
}

function EditUser({ user, roles, self, onSaved, onPassword, onClose }: {
  user: TenantUser; roles: string[]; self: boolean; onSaved: () => void; onPassword: (p: string) => void; onClose: () => void
}) {
  const [f, setF] = useState<Form>(fromUser(user))
  const save = useAction(async () => {
    // one's own roles and scope are not sent: nobody changes their own access (ADR-0031)
    const b = body(f)
    const r = await put<TenantUser>(`/api/v1/users/${user.id}`, self
      ? { email: b.email, firstName: b.firstName, lastName: b.lastName } : b)
    onSaved()
    return r
  })
  const enable = useAction(async (enabled: boolean) => {
    const r = await put<TenantUser>(`/api/v1/users/${user.id}`, { enabled })
    onSaved()
    return r
  })
  const reset = useAction(async () => {
    const r = await post<{ temporaryPassword: string }>(`/api/v1/users/${user.id}/password`)
    onPassword(r.temporaryPassword)
    onSaved()
    return r
  })
  if (!user.managed) {
    return (
      <Card title={user.username} actions={<button className="small" onClick={onClose}>Close</button>}>
        <p className="muted">An integration or service account ({user.roles.join(', ')}). It is managed by the operator, not here.</p>
      </Card>
    )
  }
  return (
    <Card title={`${user.username}${self ? ' (you)' : ''}`} actions={<button className="small" onClick={onClose}>New user</button>}>
      <form className="form" onSubmit={(e) => (e.preventDefault(), void save.run())}>
        <Field label="E-mail"><input type="email" value={f.email} onChange={(e) => setF({ ...f, email: e.target.value })} /></Field>
        <Field label="First name"><input value={f.firstName} onChange={(e) => setF({ ...f, firstName: e.target.value })} /></Field>
        <Field label="Last name"><input value={f.lastName} onChange={(e) => setF({ ...f, lastName: e.target.value })} /></Field>
        {self && <p className="muted small">Your own roles, scope and status are changed by another administrator.</p>}
        <Field label="Roles"><RolePicks roles={roles} value={f.roles} disabled={self} onChange={(v) => setF({ ...f, roles: v })} /></Field>
        <ScopeFields f={f} setF={setF} disabled={self} />
        <ErrorBox error={save.error ?? enable.error ?? reset.error} />
        <Success>{save.done && 'Saved'}</Success>
        <div className="row tight">
          <button className="primary" disabled={save.busy || f.roles.length === 0}>Save</button>
          {!self && (user.enabled
            ? <ConfirmButton label="Disable" question={`Disable ${user.username}? Their sessions end now.`} disabled={enable.busy} onConfirm={() => void enable.run(false)} />
            : <ConfirmButton label="Enable" question={`Enable ${user.username}?`} disabled={enable.busy} onConfirm={() => void enable.run(true)} />)}
          <ConfirmButton label="Reset password" question={`Issue a new temporary password for ${user.username}?`}
                         disabled={reset.busy} onConfirm={() => void reset.run()} />
        </div>
      </form>
    </Card>
  )
}
