import { useState, type FormEvent } from 'react'
import { newIdempotencyKey, post, type Row } from '../api'
import { useAuth } from '../auth'
import { Card, ErrorBox, Field, Page, Success, fmtQty, useAction, useSite } from '../ui'

const ADJUST_REASONS: [string, string, boolean][] = [
  ['CC_TOL', 'Count variance within tolerance', false],
  ['CC_VAR', 'Count variance (approval)', true],
  ['FOUND', 'Found stock (approval)', true],
  ['LOST', 'Stock not found (approval)', true],
  ['DAMAGE', 'Damaged in warehouse', false],
  ['SYS_CORR', 'System correction, no ERP posting (approval)', true],
]
const STATUS_REASONS: [string, string, boolean][] = [
  ['QA_HOLD', 'Quality hold', false],
  ['QA_REL', 'Quality release (QA manager + approval)', true],
  ['DAMAGE', 'Damaged', false],
  ['EXPIRY', 'Expired', false],
  ['SYS_CORR', 'System correction (approval)', true],
]
const STATUSES = ['AVAILABLE', 'QI', 'BLOCKED', 'DAMAGED', 'EXPIRED']

/**
 * Adjustments and status changes (§6.3/6.4). Reasons that need approval require a second person to sign in
 * (segregation of duties): the approver's own sign-in token is sent as X-Approval-Token.
 */
export default function Adjust() {
  const site = useSite()
  const { approverToken } = useAuth()
  const [kind, setKind] = useState<'adjust' | 'status'>('adjust')
  const [f, setF] = useState({ ownerId: '', itemNo: '', lotNo: '', lpnId: '', locationId: '', qty: '', uom: 'EA',
    reasonCode: 'CC_TOL', fromStatus: 'AVAILABLE', toStatus: 'QI', serials: '' })
  const [approval, setApproval] = useState<string>()
  const [key, setKey] = useState(newIdempotencyKey('adj'))
  const reasons = kind === 'adjust' ? ADJUST_REASONS : STATUS_REASONS
  const needsApproval = reasons.find(([c]) => c === f.reasonCode)?.[2] ?? false
  const set = (k: keyof typeof f) => (e: { target: { value: string } }) => setF({ ...f, [k]: e.target.value })

  const getApproval = useAction(async () => {
    const token = await approverToken()
    setApproval(token)
    return token
  })
  const submit = useAction(async () => {
    const common = {
      ownerId: f.ownerId, itemNo: f.itemNo, lotNo: f.lotNo || null, lpnId: f.lpnId || null, locationId: f.locationId,
      uom: f.uom, reasonCode: f.reasonCode, serials: f.serials.split(/[\s,]+/).filter(Boolean),
    }
    const headers = approval ? { 'X-Approval-Token': approval } : undefined
    return kind === 'adjust'
      ? post<Row>(`/api/v1/sites/${site}/inventory/adjustments`, { ...common, qtyDelta: Number(f.qty) }, { idempotencyKey: key, headers })
      : post<Row>(`/api/v1/sites/${site}/inventory/status-changes`,
        { ...common, qty: Number(f.qty), fromStatus: f.fromStatus, toStatus: f.toStatus }, { idempotencyKey: key, headers })
  })

  const onSubmit = async (e: FormEvent) => {
    e.preventDefault()
    if (await submit.run()) {
      setKey(newIdempotencyKey('adj'))
      setApproval(undefined)
    }
  }
  const switchKind = (k: 'adjust' | 'status') => {
    setKind(k)
    setF({ ...f, reasonCode: (k === 'adjust' ? ADJUST_REASONS : STATUS_REASONS)[0][0] })
    submit.clear()
  }

  return (
    <Page title="Adjust stock / change status">
      <div className="tabs">
        <button className={kind === 'adjust' ? 'active' : ''} onClick={() => switchKind('adjust')}>Quantity adjustment</button>
        <button className={kind === 'status' ? 'active' : ''} onClick={() => switchKind('status')}>Status change</button>
      </div>
      <Card>
        <form className="form" onSubmit={onSubmit}>
          <div className="row">
            <Field label="Owner"><input value={f.ownerId} onChange={set('ownerId')} required size={8} /></Field>
            <Field label="Item"><input value={f.itemNo} onChange={set('itemNo')} required /></Field>
            <Field label="Lot"><input value={f.lotNo} onChange={set('lotNo')} /></Field>
          </div>
          <div className="row">
            <Field label="Location"><input value={f.locationId} onChange={set('locationId')} required /></Field>
            <Field label="LPN"><input value={f.lpnId} onChange={set('lpnId')} /></Field>
          </div>
          <div className="row">
            <Field label={kind === 'adjust' ? 'Quantity (+/−)' : 'Quantity'}>
              <input type="number" step="any" value={f.qty} onChange={set('qty')} required />
            </Field>
            <Field label="UoM"><input value={f.uom} onChange={set('uom')} required size={4} /></Field>
            {kind === 'status' && (
              <>
                <Field label="From status"><select value={f.fromStatus} onChange={set('fromStatus')}>{STATUSES.map((s) => <option key={s}>{s}</option>)}</select></Field>
                <Field label="To status"><select value={f.toStatus} onChange={set('toStatus')}>{STATUSES.map((s) => <option key={s}>{s}</option>)}</select></Field>
              </>
            )}
          </div>
          <Field label="Reason">
            <select value={f.reasonCode} onChange={(e) => (setF({ ...f, reasonCode: e.target.value }), setApproval(undefined))}>
              {reasons.map(([c, label]) => <option key={c} value={c}>{c} — {label}</option>)}
            </select>
          </Field>
          <Field label="Serials" hint="Serial-tracked items only"><textarea rows={2} value={f.serials} onChange={set('serials')} /></Field>
          {needsApproval && (
            <div className="approval">
              {approval
                ? <span className="badge good">Approver signed in</span>
                : <span className="muted">This reason needs approval by another person (inventory manager or supervisor).</span>}
              <button type="button" onClick={() => void getApproval.run()} disabled={getApproval.busy}>
                {approval ? 'Sign in another approver' : 'Approver sign-in'}
              </button>
              <ErrorBox error={getApproval.error} />
            </div>
          )}
          <ErrorBox error={submit.error} />
          <Success>{submit.result && `Done: ${((submit.result.lines as Row[]) ?? []).map((l) => `${String(l.txnType)} ${fmtQty(l.qtyDelta)}`).join(', ')}`}</Success>
          <button className="primary" disabled={submit.busy || (needsApproval && !approval)}>
            {kind === 'adjust' ? 'Post adjustment' : 'Change status'}
          </button>
        </form>
      </Card>
    </Page>
  )
}
