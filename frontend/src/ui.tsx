import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react'
import { ApiError, get } from './api'

// ------------------------------------------------------------------ site context

/** A site of the network (ADR-0024): the main warehouse or a satellite store supplied by it. */
export interface SiteInfo {
  siteId: string
  name: string
  timeZone: string
  erpSite: string
  siteType: 'MAIN' | 'STORE'
  supplyingSite?: string | null
}

interface SiteContextValue {
  site: string
  setSite: (site: string) => void
  /** The sites the user may work at; empty until loaded (or when the API is not reachable and nothing is cached). */
  sites: SiteInfo[]
  /** The current site is a satellite store: the UI shows only store work. */
  isStore: boolean
}

const SiteContext = createContext<SiteContextValue>({ site: 'DC1', setSite: () => {}, sites: [], isStore: false })

function stored<T>(key: string, fallback: T): T {
  try {
    const v = window.localStorage.getItem(key)
    return v === null ? fallback : (JSON.parse(v) as T)
  } catch {
    return fallback
  }
}

function store(key: string, value: unknown) {
  try {
    window.localStorage.setItem(key, JSON.stringify(value))
  } catch {
    // per-browser convenience only
  }
}

function storedSite(fallback: string): string {
  try {
    return window.localStorage.getItem('astrawms.site') || fallback
  } catch {
    return fallback
  }
}

export function SiteProvider({ initial, children }: { initial: string; children: ReactNode }) {
  const [site, setSiteState] = useState(() => storedSite(initial))
  // The last list is kept on the device so a store client opened offline still knows its site type.
  const [sites, setSites] = useState<SiteInfo[]>(() => stored<SiteInfo[]>('astrawms.sites', []))
  useEffect(() => {
    get<SiteInfo[]>('/api/v1/sites').then((list) => { setSites(list); store('astrawms.sites', list) }).catch(() => undefined)
  }, [])
  const setSite = (s: string) => {
    setSiteState(s)
    try {
      window.localStorage.setItem('astrawms.site', s)
    } catch {
      // per-browser convenience only
    }
  }
  const isStore = sites.find((x) => x.siteId === site)?.siteType === 'STORE'
  return <SiteContext.Provider value={{ site, setSite, sites, isStore }}>{children}</SiteContext.Provider>
}

export const useSite = () => useContext(SiteContext).site
export const useSiteContext = () => useContext(SiteContext)

// ------------------------------------------------------------------ data loading

export function useLoad<T>(load: () => Promise<T>, deps: unknown[]): {
  data: T | undefined
  error: unknown
  loading: boolean
  reload: () => void
} {
  const [data, setData] = useState<T>()
  const [error, setError] = useState<unknown>()
  const [loading, setLoading] = useState(true)
  const [tick, setTick] = useState(0)
  useEffect(() => {
    let cancelled = false
    setLoading(true)
    load()
      .then((d) => !cancelled && (setData(d), setError(undefined)))
      .catch((e) => !cancelled && setError(e))
      .finally(() => !cancelled && setLoading(false))
    return () => {
      cancelled = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, tick])
  return { data, error, loading, reload: useCallback(() => setTick((t) => t + 1), []) }
}

/** Runs a command; keeps its error and result for display. */
export function useAction<A extends unknown[], R>(action: (...args: A) => Promise<R>) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<unknown>()
  const [result, setResult] = useState<R>()
  const [done, setDone] = useState(false)
  const run = async (...args: A): Promise<R | undefined> => {
    setBusy(true)
    setError(undefined)
    setDone(false)
    try {
      const r = await action(...args)
      setResult(r)
      setDone(true)
      return r
    } catch (e) {
      setError(e)
      return undefined
    } finally {
      setBusy(false)
    }
  }
  /** {@code done}: the last run succeeded (also for 204 responses, whose result is undefined). */
  return { run, busy, error, result, done, clear: () => (setError(undefined), setResult(undefined), setDone(false)) }
}

// ------------------------------------------------------------------ components

export function Page({ title, actions, children }: { title: string; actions?: ReactNode; children: ReactNode }) {
  return (
    <section className="page">
      <header className="page-head">
        <h1>{title}</h1>
        {actions && <div className="page-actions">{actions}</div>}
      </header>
      {children}
    </section>
  )
}

export function Card({ title, children, actions }: { title?: string; children: ReactNode; actions?: ReactNode }) {
  return (
    <div className="card">
      {(title || actions) && (
        <div className="card-head">
          {title && <h2>{title}</h2>}
          {actions}
        </div>
      )}
      {children}
    </div>
  )
}

export function ErrorBox({ error }: { error: unknown }) {
  if (!error) {
    return null
  }
  if (error instanceof ApiError) {
    const extra = Object.entries(error.problem).filter(
      ([k]) => !['type', 'title', 'status', 'detail', 'code', 'instance'].includes(k),
    )
    return (
      <div className="alert error" role="alert">
        <strong>{error.code}</strong> — {error.message}
        {extra.length > 0 && <div className="muted small">{extra.map(([k, v]) => `${k}: ${JSON.stringify(v)}`).join(' · ')}</div>}
      </div>
    )
  }
  return <div className="alert error" role="alert">{error instanceof Error ? error.message : String(error)}</div>
}

/**
 * A button that asks on itself before acting (ADR-0028): first click shows the question and Confirm / Cancel. No
 * browser dialog, which some browsers and automation dismiss without a trace.
 */
export function ConfirmButton({ label, question, onConfirm, disabled, className = 'small', title }: {
  label: string; question: string; onConfirm: () => void; disabled?: boolean; className?: string; title?: string
}) {
  const [asking, setAsking] = useState(false)
  if (!asking) {
    return <button type="button" className={className} disabled={disabled} title={title} onClick={() => setAsking(true)}>{label}</button>
  }
  return (
    <span className="confirm-inline" role="group" aria-label={question}>
      <span className="small">{question}</span>{' '}
      <button type="button" className={`${className} primary`} onClick={() => { setAsking(false); onConfirm() }}>Confirm</button>{' '}
      <button type="button" className={className} onClick={() => setAsking(false)}>Cancel</button>
    </span>
  )
}

/** A message that appears at the bottom of the screen for a few seconds (and stays readable in the page as well). */
export function useToast(): [ReactNode, (kind: 'ok' | 'error', text: string) => void] {
  const [toast, setToast] = useState<{ kind: 'ok' | 'error'; text: string; at: number }>()
  useEffect(() => {
    if (!toast) return
    const t = window.setTimeout(() => setToast(undefined), 12_000)
    return () => window.clearTimeout(t)
  }, [toast])
  const node = toast ? (
    <div className={`toast ${toast.kind}`} role={toast.kind === 'error' ? 'alert' : 'status'} onClick={() => setToast(undefined)}>
      {toast.text}
    </div>
  ) : null
  return [node, (kind, text) => setToast({ kind, text, at: Date.now() })]
}

export function Success({ children }: { children: ReactNode }) {
  return children ? <div className="alert ok" role="status">{children}</div> : null
}

/** Search input for list pages: searches on Enter or when cleared (the page keeps the term in the URL). */
export function SearchBox({ value, onSearch, placeholder }: { value: string; onSearch: (v: string) => void; placeholder?: string }) {
  const [text, setText] = useState(value)
  useEffect(() => setText(value), [value])
  return (
    <form className="search" role="search" onSubmit={(e) => { e.preventDefault(); onSearch(text.trim()) }}>
      <input type="search" value={text} placeholder={placeholder ?? 'Search'} aria-label="Search"
             onChange={(e) => { setText(e.target.value); if (!e.target.value) onSearch('') }} />
    </form>
  )
}

/** Reads and updates one URL search parameter, keeping the others. */
export function useParamSetter(params: URLSearchParams, setParams: (p: URLSearchParams) => void) {
  return (key: string, value: string) => {
    const next = new URLSearchParams(params)
    if (value) {
      next.set(key, value)
    } else {
      next.delete(key)
    }
    setParams(next)
  }
}

export function Field({ label, children, hint }: { label: string; children: ReactNode; hint?: string }) {
  return (
    <label className="field">
      <span>{label}</span>
      {children}
      {hint && <small className="muted">{hint}</small>}
    </label>
  )
}

export function Badge({ value }: { value: string | null | undefined }) {
  if (!value) {
    return null
  }
  const tone = /ERROR|FAILED|SHORT|CANCEL/.test(value) ? 'bad'
    : /CONFIRMED|COMPLETED|CLOSED|SHIPPED|PICKED|AVAILABLE|RELEASED|DONE|RETURNED/.test(value) ? 'good'
      : /POOLED|PLANNED|OPEN|ASSIGNED|IN_PROGRESS|NOT_STARTED|EXCEPTION|BACKORDERED|QI|RETURNING|CANCEL_REQUESTED/.test(value) ? 'warn'
        : 'neutral'
  return <span className={`badge ${tone}`}>{value}</span>
}

export interface Column<T> {
  header: string
  cell: (row: T) => ReactNode
  align?: 'right'
}

export function Table<T>({ rows, columns, empty = 'Nothing to show', onRow }: {
  rows: T[] | undefined
  columns: Column<T>[]
  empty?: string
  onRow?: (row: T) => void
}) {
  if (!rows) {
    return <p className="muted">Loading…</p>
  }
  if (rows.length === 0) {
    return <p className="muted">{empty}</p>
  }
  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>{columns.map((c) => <th key={c.header} className={c.align}>{c.header}</th>)}</tr>
        </thead>
        <tbody>
          {rows.map((r, i) => (
            <tr key={i} className={onRow ? 'clickable' : undefined} onClick={onRow ? () => onRow(r) : undefined}>
              {columns.map((c) => <td key={c.header} className={c.align}>{c.cell(r)}</td>)}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

export function fmtDate(value: unknown): string {
  if (!value) {
    return ''
  }
  const d = new Date(String(value))
  return Number.isNaN(d.getTime()) ? String(value) : d.toLocaleString()
}

export function fmtQty(value: unknown): string {
  if (value === null || value === undefined || value === '') {
    return ''
  }
  const n = Number(value)
  return Number.isNaN(n) ? String(value) : n.toLocaleString(undefined, { maximumFractionDigits: 3 })
}
