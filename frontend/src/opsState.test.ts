import { describe as suite, expect, it } from 'vitest'
import { ApiError } from './api'
import { age, describe, overall, runCheck } from './opsState'

suite('operations checks (ADR-0029)', () => {
  it('every check ends in a definite state', async () => {
    let t = 1000
    const now = () => (t += 50)
    expect(await runCheck(async () => 'UP', now)).toMatchObject({ kind: 'ok', data: 'UP' })
    expect(await runCheck(async () => { throw new ApiError(0, { code: 'TIMEOUT', detail: 'No answer within 8 s' }) }, now))
      .toMatchObject({ kind: 'timeout', detail: 'No answer within 8 s' })
    expect(await runCheck(async () => { throw new ApiError(503, { code: 'HTTP_503', detail: 'restarting' }) }, now))
      .toMatchObject({ kind: 'error', status: 503 })
    expect(await runCheck(async () => { throw new TypeError('Failed to fetch') }, now))
      .toMatchObject({ kind: 'error', detail: 'Failed to fetch' })
  })

  it('describes each state for the table, never an empty cell', () => {
    expect(describe(undefined, () => 'x')).toBe('checking…')
    expect(describe({ kind: 'loading', startedAt: 0 }, () => 'x')).toBe('checking…')
    expect(describe({ kind: 'ok', data: 3, at: 0, ms: 5 }, (n) => `${n} pending`)).toBe('3 pending')
    expect(describe({ kind: 'timeout', at: 0, ms: 8000, detail: 'No answer within 8 s' }, () => 'x')).toBe('no answer (No answer within 8 s)')
    expect(describe({ kind: 'error', at: 0, ms: 1, detail: 'x', status: 403 }, () => 'x')).toBe('not allowed')
  })

  it('derives the service state from its checks', () => {
    const up = { kind: 'ok' as const, data: 'UP', at: 0, ms: 1 }
    expect(overall(undefined, undefined)).toBe('CHECKING')
    expect(overall({ kind: 'ok', data: 'DOWN', at: 0, ms: 1 }, undefined)).toBe('DOWN')
    expect(overall({ kind: 'timeout', at: 0, ms: 1, detail: '' }, undefined)).toBe('DOWN')
    expect(overall(up, { kind: 'error', at: 0, ms: 1, detail: '' })).toBe('DEGRADED')
    expect(overall(up, { kind: 'ok', data: {}, at: 0, ms: 1 })).toBe('UP')
  })

  it('says how old a result is', () => {
    expect(age(0, 12_000)).toBe('12 s ago')
    expect(age(0, 180_000)).toBe('3 min ago')
  })
})
