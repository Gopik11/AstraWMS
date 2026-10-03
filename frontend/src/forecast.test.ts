import { describe, expect, it } from 'vitest'
import { cutoffForecast } from './forecast'

describe('cutoff labor forecast', () => {
  const now = new Date('2026-10-03T12:00:00Z')
  const orders = [
    { erp_doc_no: 'A', status: 'RELEASED', cutoff_at: '2026-10-03T13:00:00Z' },
    { erp_doc_no: 'B', status: 'RELEASED', cutoff_at: '2026-10-03T13:00:00Z' },
    { erp_doc_no: 'C', status: 'RELEASED', cutoff_at: '2026-10-03T14:00:00Z' },
    { erp_doc_no: 'D', status: 'SHIPPED', cutoff_at: '2026-10-03T13:00:00Z' },
  ]
  const work = [{ orderRef: 'A', openPicks: 3, standardMinutes: 60 }, { orderRef: 'B', openPicks: 1, standardMinutes: 30 },
    { orderRef: 'C', openPicks: 2, standardMinutes: 90 }, { orderRef: 'D', openPicks: 1, standardMinutes: 10 }]

  it('adds earlier cutoffs first and says how many operators each cutoff needs', () => {
    const f = cutoffForecast(orders, work, 1, now)
    expect(f.map((c) => c.orders)).toEqual([['A', 'B'], ['C']])
    expect(f[0]).toMatchObject({ workMinutes: 90, cumulativeMinutes: 90, operatorsNeeded: 2, atRisk: true, slackMinutes: -30 })
    expect(f[1]).toMatchObject({ cumulativeMinutes: 180, operatorsNeeded: 2, atRisk: true })
    const two = cutoffForecast(orders, work, 2, now)
    expect(two.map((c) => c.atRisk)).toEqual([false, false])
  })
})
