/**
 * Operations page state (ADR-0029): every service is checked on its own, every check ends in a definite state within
 * its timeout, and each result says when it was taken and how long it took. No check can leave the page loading.
 */
import { ApiError } from './api'

export type CheckState<T> =
  | { kind: 'loading'; startedAt: number }
  | { kind: 'ok'; data: T; at: number; ms: number }
  | { kind: 'timeout'; at: number; ms: number; detail: string }
  | { kind: 'error'; at: number; ms: number; detail: string; status?: number }

/** Runs a check and always resolves to a final state. */
export async function runCheck<T>(check: () => Promise<T>, now: () => number = Date.now): Promise<CheckState<T>> {
  const start = now()
  try {
    const data = await check()
    return { kind: 'ok', data, at: now(), ms: now() - start }
  } catch (e) {
    const ms = now() - start
    if (e instanceof ApiError && e.code === 'TIMEOUT') {
      return { kind: 'timeout', at: now(), ms, detail: e.message }
    }
    if (e instanceof ApiError) {
      return { kind: 'error', at: now(), ms, detail: `${e.message} (${e.code})`, status: e.status }
    }
    return { kind: 'error', at: now(), ms, detail: e instanceof Error ? e.message : String(e) }
  }
}

/** One-line summary of a check for the table: what it found, or why there is nothing. */
export function describe<T>(s: CheckState<T> | undefined, ok: (data: T) => string): string {
  if (!s || s.kind === 'loading') return 'checking…'
  if (s.kind === 'ok') return ok(s.data)
  if (s.kind === 'timeout') return `no answer (${s.detail})`
  return s.status === 403 ? 'not allowed' : `failed: ${s.detail}`
}

/** A service's overall state from its checks: DOWN wins over DEGRADED over UP; unknown while checking. */
export function overall(health: CheckState<string> | undefined, status: CheckState<unknown> | undefined): string {
  if (!health || health.kind === 'loading') return 'CHECKING'
  if (health.kind !== 'ok' || health.data !== 'UP') return 'DOWN'
  if (!status || status.kind === 'loading') return 'UP'
  return status.kind === 'ok' ? 'UP' : 'DEGRADED'
}

/** How old a result is, for "last checked" (seconds under a minute, then minutes). */
export function age(at: number, now: number = Date.now()): string {
  const s = Math.max(0, Math.round((now - at) / 1000))
  return s < 60 ? `${s} s ago` : `${Math.round(s / 60)} min ago`
}
