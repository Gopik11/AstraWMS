import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { setTokenSource } from '../api'
import { answer } from './Ask'

const past = new Date(Date.now() - 60 * 60_000).toISOString()
const future = new Date(Date.now() + 5 * 60 * 60_000).toISOString()
const ORDERS = [
  { erp_doc_no: '80001', status: 'RELEASED', carrier_scac: 'UPSN', cutoff_at: past, lines_short: 0 },
  { erp_doc_no: '80002', status: 'RELEASED', carrier_scac: 'UPSN', cutoff_at: future, lines_short: 1 },
  { erp_doc_no: '80003', status: 'RELEASED', carrier_scac: 'FDEG', cutoff_at: past, lines_short: 0 },
  { erp_doc_no: '80004', status: 'SHIPPED', carrier_scac: 'UPSN', cutoff_at: past, lines_short: 0 },
]

beforeEach(() => {
  setTokenSource(() => 'token')
  vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify(ORDERS), { status: 200 })))
})
afterEach(() => vi.unstubAllGlobals())

describe('ask box', () => {
  it('answers "what is late for UPSN" with the open UPSN orders past their cutoff', async () => {
    const a = await answer('DC1', 'what is late for UPSN')
    expect(a.items.map((i) => i.label)).toEqual(['80001'])
    expect(a.items[0].to).toBe('/orders/80001')
    expect(a.text).toContain('UPSN')
  })

  it('lists short orders and says when it does not know the question', async () => {
    expect((await answer('DC1', 'which orders are short?')).items.map((i) => i.label)).toEqual(['80002'])
    const unknown = await answer('DC1', 'what is the weather')
    expect(unknown.items).toHaveLength(0)
    expect(unknown.text).toContain('Not a question I know')
  })
})
