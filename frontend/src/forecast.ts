import type { Row } from './api'

/**
 * Cutoff labor forecast (ADR-0024): for each upcoming carrier cutoff, the open pick work of the orders due by then
 * (standard minutes, from the labor board) shared by the operators active now. Work for earlier cutoffs is done first,
 * so the work is cumulative. A cutoff is at risk when the work cannot finish before it with the people on the floor.
 */
export interface CutoffForecast {
  cutoff: Date
  orders: string[]
  picks: number
  workMinutes: number
  cumulativeMinutes: number
  finishAt: Date
  slackMinutes: number
  operatorsNeeded: number
  atRisk: boolean
}

export function cutoffForecast(orders: Row[], pickWork: { orderRef: string; openPicks: number; standardMinutes: number }[],
                               activeOperators: number, now = new Date()): CutoffForecast[] {
  const work = new Map(pickWork.map((w) => [w.orderRef, w]))
  const groups = new Map<number, Row[]>()
  for (const o of orders) {
    const due = o.cutoff_at ?? o.planned_gi_utc
    if (!due || ['SHIPPED', 'CONFIRMED', 'CANCELLED', 'SHIP_ERROR'].includes(String(o.status))) continue
    const w = work.get(String(o.erp_doc_no))
    if (!w) continue
    const t = new Date(String(due)).getTime()
    groups.set(t, [...(groups.get(t) ?? []), o])
  }
  const people = Math.max(activeOperators, 1)
  let cumulative = 0
  return [...groups.entries()].sort(([a], [b]) => a - b).map(([t, rows]) => {
    const docs = rows.map((r) => String(r.erp_doc_no))
    const minutes = docs.reduce((n, d) => n + (work.get(d)?.standardMinutes ?? 0), 0)
    cumulative += minutes
    const finishAt = new Date(now.getTime() + (cumulative / people) * 60_000)
    const until = (t - now.getTime()) / 60_000
    return {
      cutoff: new Date(t), orders: docs, picks: docs.reduce((n, d) => n + (work.get(d)?.openPicks ?? 0), 0),
      workMinutes: minutes, cumulativeMinutes: cumulative, finishAt,
      slackMinutes: Math.round(until - cumulative / people),
      operatorsNeeded: until <= 0 ? Infinity : Math.ceil(cumulative / until),
      atRisk: finishAt.getTime() > t,
    }
  })
}
