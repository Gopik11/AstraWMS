import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { setTokenSource } from './api'
import { discard, isQueued, queue, retry, rfPost, sync } from './offline'

function memoryStorage(): Storage {
  const data = new Map<string, string>()
  return {
    getItem: (k) => data.get(k) ?? null,
    setItem: (k, v) => void data.set(k, v),
    removeItem: (k) => void data.delete(k),
    clear: () => data.clear(),
    key: (i) => [...data.keys()][i] ?? null,
    get length() { return data.size },
  }
}

let online = true

beforeEach(() => {
  vi.stubGlobal('window', { localStorage: memoryStorage(), addEventListener() {}, removeEventListener() {} })
  vi.stubGlobal('navigator', { get onLine() { return online } })
  online = true
  setTokenSource(() => 'token')
})

afterEach(() => vi.unstubAllGlobals())

describe('offline queue', () => {
  it('keeps commands without network and sends them in order with their keys when back', async () => {
    const calls: { url: string; key: string | null }[] = []
    vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit) => {
      if (!online) throw new TypeError('Failed to fetch')
      calls.push({ url, key: (init.headers as Record<string, string>)['Idempotency-Key'] ?? null })
      return new Response('{"status":"COMPLETED"}', { status: 200 })
    }))
    online = false
    const a = await rfPost('/api/v1/sites/S1/tasks/t1/pick', { qty: 1 }, 'Pick t1')
    const b = await rfPost('/api/v1/sites/S1/inventory/material-issues/MI1/lines/10/issue', { qty: 2 }, 'Issue', 'mi-1')
    expect(isQueued(a) && isQueued(b)).toBe(true)
    expect(queue()).toHaveLength(2)

    online = true
    // While commands wait, a new one queues behind them so the order is kept.
    const c = await rfPost('/api/v1/sites/S1/tasks/t2/pick', { qty: 1 }, 'Pick t2')
    expect(isQueued(c)).toBe(true)
    expect(await sync()).toEqual({ sent: 3, failed: 0 })
    expect(calls.map((x) => x.url)).toEqual([
      '/api/v1/sites/S1/tasks/t1/pick', '/api/v1/sites/S1/inventory/material-issues/MI1/lines/10/issue',
      '/api/v1/sites/S1/tasks/t2/pick'])
    expect(calls[1].key).toBe('mi-1')
    expect(queue()).toHaveLength(0)
  })

  it('marks a command the server refuses as needing attention and goes on with the next', async () => {
    let n = 0
    vi.stubGlobal('fetch', vi.fn(async () => {
      n++
      return n === 1
        ? new Response('{"code":"TSK_NOT_ASSIGNED","detail":"Task is COMPLETED"}', { status: 409 })
        : new Response('{}', { status: 200 })
    }))
    online = false
    await rfPost('/x/1', {}, 'first')
    await rfPost('/x/2', {}, 'second')
    online = true
    expect(await sync()).toEqual({ sent: 1, failed: 1 })
    const [left] = queue()
    expect(left.status).toBe('FAILED')
    expect(left.error).toContain('TSK_NOT_ASSIGNED')
    retry(left.id)
    expect(queue()[0].status).toBe('PENDING')
    discard(left.id)
    expect(queue()).toHaveLength(0)
  })
})
