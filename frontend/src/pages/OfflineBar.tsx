import { discard, offlineTasks, retry, sync, useOffline } from '../offline'
import { fmtDate } from '../ui'

/**
 * Network and sync state on the RF screens (ADR-0023): offline, how many scans wait on this device, and scans the
 * server refused when they were sent ("needs attention"), with retry and discard.
 */
export function OfflineBar({ site }: { site: string }) {
  const { online, pending, failed } = useOffline()
  const onDevice = offlineTasks(site).length
  if (online && pending.length === 0 && failed.length === 0) {
    return onDevice ? <p className="muted">{onDevice} task(s) downloaded to this device</p> : null
  }
  return (
    <div className={`offline-bar ${online ? '' : 'offline'}`} role="status">
      <strong>{online ? 'Online' : 'Offline'}</strong>
      {pending.length > 0 && <span> · {pending.length} scan(s) waiting to be sent</span>}
      {onDevice > 0 && <span> · {onDevice} task(s) on this device</span>}
      {online && pending.length > 0 && <button className="small" onClick={() => void sync()}>Send now</button>}
      {failed.length > 0 && (
        <details open>
          <summary>{failed.length} need attention: the server refused them when they were sent</summary>
          <ul>
            {failed.map((c) => (
              <li key={c.id}>
                {c.label} ({fmtDate(c.createdAt)}): {c.error}{' '}
                <button className="small" onClick={() => retry(c.id)}>Retry</button>{' '}
                <button className="small" onClick={() => { if (window.confirm('Discard this scan? It will not be sent.')) discard(c.id) }}>Discard</button>
              </li>
            ))}
          </ul>
        </details>
      )}
    </div>
  )
}
