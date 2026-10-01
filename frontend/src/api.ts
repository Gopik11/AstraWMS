/** REST client for the AstraWMS API behind the gateway (same origin). Errors are RFC 9457 problem details. */

export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly problem: Record<string, unknown>

  constructor(status: number, problem: Record<string, unknown>) {
    super(String(problem.detail ?? problem.title ?? `HTTP ${status}`))
    this.status = status
    this.code = String(problem.code ?? `HTTP_${status}`)
    this.problem = problem
  }
}

let tokenSource: () => string = () => ''

export function setTokenSource(source: () => string) {
  tokenSource = source
}

export interface RequestOptions {
  body?: unknown
  /** Commands: a fresh key per user action; a retry of the same action must reuse it (NFR-123). */
  idempotencyKey?: string
  headers?: Record<string, string>
}

export function newIdempotencyKey(prefix = 'ui'): string {
  return `${prefix}-${crypto.randomUUID()}`
}

export async function api<T>(method: string, path: string, options: RequestOptions = {}): Promise<T> {
  const headers: Record<string, string> = { Authorization: `Bearer ${tokenSource()}`, ...options.headers }
  if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json'
  }
  if (options.idempotencyKey) {
    headers['Idempotency-Key'] = options.idempotencyKey
  }
  const response = await fetch(path, {
    method,
    headers,
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
  })
  if (response.status === 204) {
    return undefined as T
  }
  const text = await response.text()
  const data = text ? (JSON.parse(text) as unknown) : undefined
  if (!response.ok) {
    throw new ApiError(response.status, (data as Record<string, unknown>) ?? { title: response.statusText })
  }
  return data as T
}

export const get = <T>(path: string) => api<T>('GET', path)
export const post = <T>(path: string, body?: unknown, options: RequestOptions = {}) =>
  api<T>('POST', path, { ...options, body })
export const put = <T>(path: string, body?: unknown, options: RequestOptions = {}) =>
  api<T>('PUT', path, { ...options, body })

export function query(params: Record<string, string | number | undefined | null>): string {
  const q = new URLSearchParams()
  Object.entries(params).forEach(([k, v]) => {
    if (v !== undefined && v !== null && v !== '') {
      q.set(k, String(v))
    }
  })
  const s = q.toString()
  return s ? `?${s}` : ''
}

// ------------------------------------------------------------------ API types (subset used by the UI)

export interface Page<T> {
  items: T[]
  nextCursor?: string | null
}

export interface Balance {
  id: number
  ownerId: string
  itemNo: string
  lotNo: string
  lpnId: string
  locationId: string
  status: string
  qty: number
  allocatedQty: number
  availableQty: number
  expiryDate?: string | null
}

export interface Txn {
  id: number
  txnType: string
  ownerId: string
  itemNo: string
  lotNo: string
  lpnId: string
  locationId: string
  status: string
  qtyDelta: number
  qtyAfter: number
  reasonCode?: string | null
  sourceDoc?: string | null
  userId: string
  occurredAt: string
}

export interface Task {
  id: string
  taskType: 'PUTAWAY' | 'PICK' | 'RETURN'
  status: string
  priority: number
  ownerId: string
  lpnId: string
  fromLocation: string
  targetLocation: string
  strategy?: string
  exceptionReason?: string | null
  assignedTo?: string | null
  orderRef?: string | null
  orderLineRef?: string | null
  itemNo?: string | null
  lotNo?: string | null
  qty?: number | null
  uom?: string | null
  toLpn?: string | null
  qtyPicked?: number | null
  contents?: { itemNo: string; lotNo: string; qty: number }[]
  createdAt: string
}

export interface ReceiptSummary {
  erpDocNo: string
  erpDocType: string
  vendorId?: string
  expectedArrivalUtc?: string
  status: string
  lineCount: number
  erpDocument?: string | null
  erpErrorText?: string | null
}

export interface ReceiptDetail {
  header: ReceiptSummary
  lines: {
    erpLineRef: string
    ownerId: string
    itemNo: string
    qtyExpected: number
    qtyReceived: number
    uom: string
    lotNo?: string | null
    shortReason?: string | null
  }[]
  handlingUnits: { sscc: string; erpLineRef: string; qty: number; received: boolean }[]
}

export type Row = Record<string, unknown>
