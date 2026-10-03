import { useEffect, useState } from 'react'
import { ApiError, api, type Task } from './api'

/**
 * Offline work for handhelds in stores with unreliable network (ADR-0023).
 *
 * - Commands from the RF screens go through {@link rfPost}. Without network they are kept on the device, in order,
 *   and sent when it is back: the same body and the same idempotency key, so a command that did reach the server
 *   before the connection dropped is answered, not done twice.
 * - A command the server refuses when it is finally sent (stock moved meanwhile, task reassigned) is kept as
 *   "needs attention" with the server's reason, for the operator or a supervisor to resolve.
 * - "Download my work" claims a batch of tasks onto the device, so RF can go on without network.
 */
export interface QueuedCommand {
  id: string
  path: string
  body: unknown
  idempotencyKey?: string
  label: string
  createdAt: string
  status: 'PENDING' | 'FAILED'
  error?: string
}

/** What {@link rfPost} returns for a command kept on the device. */
export interface Queued { queued: true; label: string }

const QUEUE = 'astra.offline.queue'
const TASKS = 'astra.offline.tasks.'
const listeners = new Set<() => void>()

function read<T>(key: string, fallback: T): T {
  try {
    const raw = window.localStorage.getItem(key)
    return raw ? (JSON.parse(raw) as T) : fallback
  } catch {
    return fallback
  }
}

function write(key: string, value: unknown): void {
  try {
    window.localStorage.setItem(key, JSON.stringify(value))
  } catch {
    // storage full or blocked: the command stays in memory for this page only
  }
  listeners.forEach((l) => l())
}

export function queue(): QueuedCommand[] {
  return read<QueuedCommand[]>(QUEUE, [])
}

export function isQueued(v: unknown): v is Queued {
  return typeof v === 'object' && v !== null && (v as Queued).queued === true
}

/** A failed fetch (no network, DNS, connection reset) as opposed to an answer from the server. */
export function isNetworkError(e: unknown): boolean {
  return e instanceof TypeError || (typeof navigator !== 'undefined' && navigator.onLine === false && !(e instanceof ApiError))
}

/**
 * POST for RF commands: sent at once when possible; kept on the device when there is no network, or when commands
 * are already waiting (so they reach the server in the order they were done).
 */
export async function rfPost<T>(path: string, body: unknown, label: string, idempotencyKey?: string): Promise<T> {
  const waiting = queue().some((c) => c.status === 'PENDING')
  if (!waiting && navigator.onLine !== false) {
    try {
      return await api<T>('POST', path, { body: body ?? {}, idempotencyKey })
    } catch (e) {
      if (!isNetworkError(e)) {
        throw e
      }
    }
  }
  const command: QueuedCommand = {
    id: crypto.randomUUID(), path, body: body ?? {}, idempotencyKey, label, createdAt: new Date().toISOString(),
    status: 'PENDING',
  }
  write(QUEUE, [...queue(), command])
  return { queued: true, label } as unknown as T
}

let syncing = false

/** Sends waiting commands in order; stops at the first network failure and tries again later. */
export async function sync(): Promise<{ sent: number; failed: number }> {
  if (syncing || navigator.onLine === false) {
    return { sent: 0, failed: 0 }
  }
  syncing = true
  let sent = 0
  let failed = 0
  try {
    for (const c of queue()) {
      if (c.status !== 'PENDING') {
        continue
      }
      try {
        await api('POST', c.path, { body: c.body, idempotencyKey: c.idempotencyKey })
        write(QUEUE, queue().filter((x) => x.id !== c.id))
        sent++
      } catch (e) {
        if (isNetworkError(e) || (e instanceof ApiError && (e.status >= 500 || e.status === 401))) {
          break // offline again, a server problem or an expired sign-in: retry later, keep the order
        }
        const reason = e instanceof ApiError ? `${e.code}: ${e.message}` : String(e)
        write(QUEUE, queue().map((x) => (x.id === c.id ? { ...x, status: 'FAILED' as const, error: reason } : x)))
        failed++
        await flagConflict(c.path, `${c.label}: ${reason}`)
      }
    }
  } finally {
    syncing = false
  }
  return { sent, failed }
}

/**
 * The server wins (ADR-0024): a task confirmation it refused on sync puts the task in exception (SYNC_CONFLICT), so a
 * supervisor checks the location instead of the device silently disagreeing with the stock. Best effort.
 */
const TASK_COMMAND = /^(\/api\/v1\/sites\/[^/]+\/tasks\/[0-9a-f-]{36})\/(?!sync-conflict)[a-z/-]+$/

export async function flagConflict(path: string, detail: string): Promise<void> {
  const m = TASK_COMMAND.exec(path)
  if (!m) return
  try {
    await api('POST', `${m[1]}/sync-conflict`, { body: { detail } })
  } catch {
    // the scan stays in "needs attention" on the device either way
  }
}

export function retry(id: string): void {
  write(QUEUE, queue().map((c) => (c.id === id ? { ...c, status: 'PENDING' as const, error: undefined } : c)))
}

export function discard(id: string): void {
  write(QUEUE, queue().filter((c) => c.id !== id))
}

// ------------------------------------------------------------------ work on the device

export function offlineTasks(site: string): Task[] {
  return read<Task[]>(TASKS + site, [])
}

export function saveOfflineTasks(site: string, tasks: Task[]): void {
  write(TASKS + site, tasks)
}

export function dropOfflineTask(site: string, taskId: string): void {
  write(TASKS + site, offlineTasks(site).filter((t) => t.id !== taskId))
}

/** Online state, the queue, and automatic sending when the device is back online (and every 30 s while waiting). */
export function useOffline() {
  const [online, setOnline] = useState(() => navigator.onLine !== false)
  const [, setVersion] = useState(0)
  useEffect(() => {
    const changed = () => setVersion((v) => v + 1)
    const up = () => { setOnline(true); void sync() }
    const down = () => setOnline(false)
    listeners.add(changed)
    window.addEventListener('online', up)
    window.addEventListener('offline', down)
    const timer = window.setInterval(() => {
      if (queue().some((c) => c.status === 'PENDING')) void sync()
    }, 30_000)
    return () => {
      listeners.delete(changed)
      window.removeEventListener('online', up)
      window.removeEventListener('offline', down)
      window.clearInterval(timer)
    }
  }, [])
  const q = queue()
  return {
    online,
    pending: q.filter((c) => c.status === 'PENDING'),
    failed: q.filter((c) => c.status === 'FAILED'),
  }
}
