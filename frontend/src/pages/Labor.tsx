import { useState } from 'react'
import { get, put, type Row } from '../api'
import { Badge, Card, ErrorBox, Field, Page, Table, fmtDate, useAction, useLoad, useSite } from '../ui'

interface Board {
  from: string
  to: string
  activeOperators: number
  operators: {
    userId: string; completed: number; standardMinutes: number; actualMinutes: number; performancePct: number | null
    byType: Record<string, number>; lastActivity?: string; active: boolean
    current?: { taskId: string; taskType: string; from?: string; to?: string; ageMinutes: number; expectedMinutes: number; overStandard: boolean }
  }[]
  backlog: { taskType: string; open: number; standardHours: number }[]
}

interface Standard { taskType: string; baseSeconds: number; perUnitSeconds: number; requiredSkill?: string | null; isDefault: boolean }

/**
 * Labor (ADR-0021): operators against engineered standards, what each is doing now and for how long, the backlog in
 * standard hours; standards, operator equipment/skills and zone equipment that steer who gets which task.
 */
export default function Labor() {
  const site = useSite()
  const base = `/api/v1/sites/${site}/tasks`
  const [hours, setHours] = useState(8)
  const board = useLoad(() => get<Board>(`${base}/labor?hours=${hours}`), [base, hours])
  const b = board.data
  return (
    <Page title="Labor" actions={<button onClick={board.reload}>Refresh</button>}>
      <ErrorBox error={board.error} />
      <Card title={b ? `${b.activeOperators} active operator(s)` : 'Operators'} actions={
        <select value={hours} onChange={(e) => setHours(Number(e.target.value))}>
          {[1, 4, 8, 12, 24].map((h) => <option key={h} value={h}>Last {h} h</option>)}
        </select>
      }>
        <Table rows={b?.operators} empty="No operator activity in this window" columns={[
          { header: 'Operator', cell: (o) => <span>{o.userId} {o.active ? <Badge value="ACTIVE" /> : null}</span> },
          { header: 'Done', cell: (o) => o.completed, align: 'right' },
          { header: 'By type', cell: (o) => Object.entries(o.byType).map(([t, n]) => `${t} ${n}`).join(', ') },
          { header: 'Standard min', cell: (o) => o.standardMinutes, align: 'right' },
          { header: 'Actual min', cell: (o) => o.actualMinutes, align: 'right' },
          { header: 'Performance', cell: (o) => (o.performancePct == null ? '' :
              <span className={o.performancePct < 80 ? 'text-late' : ''}>{o.performancePct} %</span>), align: 'right' },
          { header: 'Now', cell: (o) => (o.current
              ? <span className={o.current.overStandard ? 'text-late' : ''}>
                  {o.current.taskType} {o.current.from ?? ''}{o.current.to ? ` → ${o.current.to}` : ''} · {o.current.ageMinutes} of {o.current.expectedMinutes} min
                </span> : 'idle') },
          { header: 'Last activity', cell: (o) => fmtDate(o.lastActivity) },
        ]} />
        <p className="muted">Performance = standard minutes of the work done ÷ minutes it took (assigned → completed).
          Red: below 80 %, or the current task is past its standard.</p>
      </Card>
      <Card title="Backlog">
        <Table rows={b?.backlog} empty="No open work" columns={[
          { header: 'Task type', cell: (r) => r.taskType },
          { header: 'Open tasks', cell: (r) => r.open, align: 'right' },
          { header: 'Standard hours', cell: (r) => r.standardHours, align: 'right' },
        ]} />
      </Card>
      <Standards base={base} />
      <Equipment base={base} />
      <AutomationZones base={base} />
    </Page>
  )
}

function Standards({ base }: { base: string }) {
  const list = useLoad(() => get<Standard[]>(`${base}/standards`), [base])
  const [f, setF] = useState({ type: 'PICK', base: '', unit: '', skill: '' })
  const save = useAction(() => put(`${base}/standards/${f.type}`, {
    baseSeconds: Number(f.base), perUnitSeconds: f.unit ? Number(f.unit) : 0, requiredSkill: f.skill || null,
  }))
  return (
    <Card title="Task standards">
      <Table rows={list.data} columns={[
        { header: 'Task type', cell: (s) => s.taskType },
        { header: 'Base (s)', cell: (s) => s.baseSeconds, align: 'right' },
        { header: 'Per unit (s)', cell: (s) => s.perUnitSeconds, align: 'right' },
        { header: 'Skill required', cell: (s) => s.requiredSkill ?? '' },
        { header: '', cell: (s) => (s.isDefault ? 'default' : '') },
      ]} />
      <div className="row">
        <Field label="Task type">
          <select value={f.type} onChange={(e) => setF({ ...f, type: e.target.value })}>
            {(list.data ?? []).map((s) => <option key={s.taskType}>{s.taskType}</option>)}
          </select>
        </Field>
        <Field label="Base seconds"><input type="number" min={0} value={f.base} onChange={(e) => setF({ ...f, base: e.target.value })} size={5} /></Field>
        <Field label="Seconds per unit"><input type="number" min={0} step="0.1" value={f.unit} onChange={(e) => setF({ ...f, unit: e.target.value })} size={5} /></Field>
        <Field label="Skill required" hint="Blank = anyone"><input value={f.skill} onChange={(e) => setF({ ...f, skill: e.target.value.toUpperCase() })} size={10} /></Field>
        <button className="primary" disabled={save.busy || f.base === ''} onClick={async () => { if (await save.run()) list.reload() }}>Save standard</button>
      </div>
      <ErrorBox error={list.error ?? save.error} />
    </Card>
  )
}

function Equipment({ base }: { base: string }) {
  const operators = useLoad(() => get<Row[]>(`${base}/operators`), [base])
  const zones = useLoad(() => get<Row[]>(`${base}/zone-equipment`), [base])
  const [op, setOp] = useState({ user: '', equipment: '', skills: '' })
  const [zone, setZone] = useState({ zone: '', equipment: '' })
  const list = (s: string) => s.split(',').map((x) => x.trim()).filter(Boolean)
  const saveOp = useAction(() => put(`${base}/operators/${op.user}`, { equipment: list(op.equipment), skills: list(op.skills) }))
  const saveZone = useAction(() => put(`${base}/zone-equipment/${zone.zone}`, { equipment: zone.equipment }))
  return (
    <Card title="Equipment and skills">
      <p className="muted">A task goes only to an operator who has the skill its type requires and the equipment every
        zone it touches requires. Operators without a profile get only tasks with no requirement.</p>
      <div className="grid2">
        <div>
          <Table rows={operators.data} empty="No operator profiles" columns={[
            { header: 'Operator', cell: (o) => String(o.user_id) },
            { header: 'Equipment', cell: (o) => String(o.equipment ?? '') },
            { header: 'Skills', cell: (o) => String(o.skills ?? '') },
          ]} />
          <div className="row">
            <Field label="User ID"><input value={op.user} onChange={(e) => setOp({ ...op, user: e.target.value })} size={10} /></Field>
            <Field label="Equipment" hint="Comma-separated"><input value={op.equipment} onChange={(e) => setOp({ ...op, equipment: e.target.value.toUpperCase() })} size={14} /></Field>
            <Field label="Skills"><input value={op.skills} onChange={(e) => setOp({ ...op, skills: e.target.value.toUpperCase() })} size={14} /></Field>
            <button disabled={saveOp.busy || !op.user} onClick={async () => { if (await saveOp.run()) operators.reload() }}>Save</button>
          </div>
        </div>
        <div>
          <Table rows={zones.data} empty="No zone needs equipment" columns={[
            { header: 'Zone', cell: (z) => String(z.zone_id) },
            { header: 'Equipment', cell: (z) => String(z.equipment) },
          ]} />
          <div className="row">
            <Field label="Zone"><input value={zone.zone} onChange={(e) => setZone({ ...zone, zone: e.target.value.toUpperCase() })} size={8} /></Field>
            <Field label="Equipment" hint="Blank removes"><input value={zone.equipment} onChange={(e) => setZone({ ...zone, equipment: e.target.value.toUpperCase() })} size={12} /></Field>
            <button disabled={saveZone.busy || !zone.zone} onClick={async () => { if (await saveZone.run()) zones.reload() }}>Save</button>
          </div>
        </div>
      </div>
      <ErrorBox error={operators.error ?? zones.error ?? saveOp.error ?? saveZone.error} />
    </Card>
  )
}

/** Automation zones (ADR-0021): tasks starting there are claimed by devices through the automation API, not on RF. */
function AutomationZones({ base }: { base: string }) {
  const zones = useLoad(() => get<Row[]>(`${base}/automation/zones`), [base])
  const [f, setF] = useState({ zone: '', deviceType: 'PICK_TO_LIGHT' })
  const save = useAction((zone: string, deviceType: string, enabled: boolean) =>
    put(`${base}/automation/zones/${zone}`, { deviceType, enabled }))
  return (
    <Card title="Automation zones">
      <p className="muted">Devices (pick-to-light, robots) claim, confirm and hand back the tasks of these zones through the
        automation API. A task a device hands back goes to RF.</p>
      <Table rows={zones.data} empty="No automated zones" columns={[
        { header: 'Zone', cell: (z) => String(z.zone_id) },
        { header: 'Devices', cell: (z) => String(z.device_type) },
        { header: 'Queued', cell: (z) => String(z.queued), align: 'right' },
        { header: 'On devices', cell: (z) => String(z.on_devices), align: 'right' },
        { header: '', cell: (z) => (
          <button className="small" disabled={save.busy}
                  onClick={async () => { if (await save.run(String(z.zone_id), String(z.device_type), !z.enabled)) zones.reload() }}>
            {z.enabled ? 'Disable' : 'Enable'}</button>
        ) },
      ]} />
      <div className="row">
        <Field label="Zone"><input value={f.zone} onChange={(e) => setF({ ...f, zone: e.target.value.toUpperCase() })} size={8} /></Field>
        <Field label="Device type">
          <select value={f.deviceType} onChange={(e) => setF({ ...f, deviceType: e.target.value })}>
            <option value="PICK_TO_LIGHT">Pick-to-light</option><option value="ROBOT">Robot</option><option value="ASRS">AS/RS</option>
          </select>
        </Field>
        <button disabled={save.busy || !f.zone} onClick={async () => { if (await save.run(f.zone, f.deviceType, true)) zones.reload() }}>Automate zone</button>
      </div>
      <ErrorBox error={zones.error ?? save.error} />
    </Card>
  )
}
