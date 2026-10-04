/**
 * GS1-128 / GS1 DataMatrix element strings as handhelds deliver them (ADR-0022), mirroring the server's parser:
 * "(01)09506000134352(10)LOT(17)271231" or raw "]C10109506000134352" + "10LOT" + GS + "17271231".
 * Returns null for anything that is not GS1 element-string data (an item number, a bare GTIN).
 */
export interface Gs1Data {
  sscc?: string
  gtin?: string
  contentGtin?: string
  lot?: string
  expiry?: string // ISO date
  bestBefore?: string
  serial?: string
  count?: number
}

const GS = '\u001d'
const FIXED: Record<string, number> = { '00': 18, '01': 14, '02': 14, '11': 6, '13': 6, '15': 6, '17': 6 }
const VARIABLE: Record<string, number> = { '10': 20, '21': 20, '30': 8, '37': 8, '400': 30 }

export function checkDigitOk(digits: string): boolean {
  let sum = 0
  const n = digits.length
  for (let k = 0; k < n - 1; k++) {
    const d = Number(digits[n - 2 - k])
    sum += k % 2 === 0 ? d * 3 : d
  }
  return (10 - (sum % 10)) % 10 === Number(digits[n - 1])
}

function check(ai: string, value: string): void {
  const fixed = FIXED[ai]
  const max = VARIABLE[ai]
  if (fixed === undefined && max === undefined) throw new Error(`AI ${ai}`)
  if (fixed !== undefined && (value.length !== fixed || !/^\d+$/.test(value))) throw new Error(`AI ${ai} length`)
  if (max !== undefined && (value.length === 0 || value.length > max)) throw new Error(`AI ${ai} length`)
  if (['00', '01', '02'].includes(ai) && !checkDigitOk(value)) throw new Error(`AI ${ai} check digit`)
  if (['30', '37'].includes(ai) && !/^\d+$/.test(value)) throw new Error(`AI ${ai} numeric`)
}

function date(v: string | undefined): string | undefined {
  if (!v) return undefined
  const yy = Number(v.slice(0, 2))
  const mm = Number(v.slice(2, 4))
  let dd = Number(v.slice(4, 6))
  const now = new Date().getFullYear()
  let year = Math.floor(now / 100) * 100 + yy
  const diff = yy - (now % 100)
  if (diff >= 51) year -= 100
  else if (diff <= -50) year += 100
  if (mm < 1 || mm > 12) throw new Error('month')
  if (dd === 0) dd = new Date(Date.UTC(year, mm, 0)).getUTCDate()
  const d = new Date(Date.UTC(year, mm - 1, dd))
  if (d.getUTCMonth() !== mm - 1) throw new Error('day')
  return d.toISOString().slice(0, 10)
}

function aiAt(s: string, i: number): string | null {
  for (let len = 2; len <= 3 && i + len <= s.length; len++) {
    const ai = s.slice(i, i + len)
    if (ai in FIXED || ai in VARIABLE) return ai
  }
  return null
}

const LINK_AIS = new Set(['00', '01', '02', '10', '11', '13', '15', '17', '21', '30', '37', '400'])

/** A GS1 Digital Link URL (from a QR code) as a bracketed element string; null without a GTIN or SSCC (ADR-0025). */
export function digitalLink(url: string): string | null {
  let u: URL
  try {
    u = new URL(url)
  } catch {
    return null
  }
  const parts = u.pathname.split('/')
  const start = parts.findIndex((p) => p === '01' || p === '00')
  if (start < 0) return null
  let out = ''
  for (let i = start; i + 1 < parts.length; i += 2) {
    if (LINK_AIS.has(parts[i])) out += `(${parts[i]})${decodeURIComponent(parts[i + 1])}`
  }
  u.searchParams.forEach((v, k) => { if (LINK_AIS.has(k)) out += `(${k})${v}` })
  return out || null
}

export function parseGs1(scan: string | null | undefined): Gs1Data | null {
  if (!scan) return null
  let s = scan.trim()
  if (s.startsWith(']')) {
    if (s.length < 3) return null
    s = s.slice(3)
  }
  if (/^https?:\/\//i.test(s)) {
    const link = digitalLink(s)
    if (!link) return null
    s = link
  }
  const el: Record<string, string> = {}
  try {
    if (s.startsWith('(')) {
      const re = /\((\d{2,4})\)([^(]*)/g
      let consumed = 0
      for (const m of s.matchAll(re)) {
        if (m.index !== consumed) throw new Error('garbage')
        const value = m[2].split(GS).join('')
        check(m[1], value)
        el[m[1]] = value
        consumed = m.index + m[0].length
      }
      if (consumed !== s.length) throw new Error('garbage')
    } else {
      if (/^\d{1,14}$/.test(s)) return null // a bare GTIN or number
      let i = 0
      while (i < s.length) {
        if (s[i] === GS) { i++; continue }
        const ai = aiAt(s, i)
        if (!ai) throw new Error('unknown AI')
        i += ai.length
        let value: string
        if (FIXED[ai] !== undefined) {
          if (i + FIXED[ai] > s.length) throw new Error('short')
          value = s.slice(i, i + FIXED[ai])
          i += FIXED[ai]
        } else {
          let end = s.indexOf(GS, i)
          if (end < 0) end = s.length
          value = s.slice(i, end)
          i = end
        }
        check(ai, value)
        el[ai] = value
      }
    }
    if (Object.keys(el).length === 0) return null
    const count = el['37'] ?? el['30']
    return {
      sscc: el['00'], gtin: el['01'], contentGtin: el['02'], lot: el['10'], expiry: date(el['17']),
      bestBefore: date(el['15']), serial: el['21'], count: count === undefined ? undefined : Number(count),
    }
  } catch {
    return null
  }
}
