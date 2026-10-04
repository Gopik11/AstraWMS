import { post, type Row } from './api'

/** A store replenishment recommendation (ADR-0025), as the inventory service computes it. */
/** A possible source with what it holds (ADR-0025): free = on hand − allocated − waiting on open documents. */
export interface SourceView {
  site: string
  type: 'WAREHOUSE' | 'STORE'
  onHand: number
  allocatedOrders: number
  allocatedTransfers: number
  waitingOnOpenDocuments: number
  free: number
  transferable: number
}

export interface Recommendation {
  siteId: string
  ownerId: string
  itemNo: string
  available: number
  inTransit: number
  openTransferQty: number
  openTransfers?: string | null
  usageDays: number
  history: 'NONE' | 'SHORT' | 'OK'
  stockoutRiskText: string
  transitDaysFrom: string
  source?: SourceView | null
  alternatives?: SourceView[]
  whySource?: string
  commitmentsKnown: boolean
  /** ADR-0026 predictive shortage. */
  risk: 'CRITICAL' | 'HIGH' | 'MED' | 'NONE' | 'NO_HISTORY'
  shortage: boolean
  stockoutDate?: string | null
  coverDays: number
  coverWithPipeline?: number
  qtyBasis?: string
  dailyUsage: number
  daysOfCover: number | null
  projected: number
  min: number
  max: number
  safety: number
  transitDays: number
  stockoutRisk: boolean
  recommended: boolean
  qty?: number
  neededQty?: number
  sourceSite?: string | null
  requiredDate?: string
  confidence?: 'HIGH' | 'MEDIUM' | 'LOW'
  reason: string
}

/**
 * Accepting a recommendation creates the transfer (the same document as any transfer: allocated, picked, shipped,
 * in transit, received) at the source site, ranked for the short-stock queue, then records the acceptance so the
 * need is not recommended again while the transfer is open.
 */
export async function acceptRecommendation(r: Recommendation): Promise<string> {
  if (!r.sourceSite || !r.qty) throw new Error('Nothing to send: no source site has stock')
  const shipBy = new Date(`${r.requiredDate ?? new Date().toISOString().slice(0, 10)}T12:00:00Z`)
  shipBy.setUTCDate(shipBy.getUTCDate() - r.transitDays)
  // strict: outbound refuses it when the source cannot cover it now (naming who holds the stock) or when an open
  // transfer to the store already brings the item, so an accepted quantity is never recommended or accepted twice.
  const transfer = await post<Row>(`/api/v1/sites/${r.sourceSite}/outbound/transfers`, {
    toSiteId: r.siteId,
    strict: true,
    plannedShipUtc: shipBy.getTime() < Date.now() ? new Date().toISOString() : shipBy.toISOString(),
    note: `Replenishment (${r.confidence} confidence): ${r.reason}`.slice(0, 500),
    priority: r.stockoutRisk ? 80 : 60,
    criticality: r.stockoutRisk ? 'HIGH' : 'NORMAL',
    lines: [{ ownerId: r.ownerId, itemNo: r.itemNo, qty: r.qty, uom: 'EA' }],
  })
  const transferNo = String(transfer.erp_doc_no)
  await post(`/api/v1/sites/${r.siteId}/inventory/store-replenishment/accept`, {
    ownerId: r.ownerId, itemNo: r.itemNo, qty: r.qty, sourceSite: r.sourceSite, requiredDate: r.requiredDate,
    reason: r.reason, confidence: r.confidence, transferNo,
  })
  return transferNo
}
