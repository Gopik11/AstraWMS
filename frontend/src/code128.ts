/**
 * Code 128 / GS1-128 encoder for on-screen and browser-printed labels (ADR-0022). Returns the bar/space module
 * widths; {@link code128Svg} draws them. Numeric runs use code set C, everything else code set B; GS1 element strings
 * ("(01)...(10)...") start with FNC1 and separate variable-length fields with FNC1.
 */
const PATTERNS = [
  '212222', '222122', '222221', '121223', '121322', '131222', '122213', '122312', '132212', '221213',
  '221312', '231212', '112232', '122132', '122231', '113222', '123122', '123221', '223211', '221132',
  '221231', '213212', '223112', '312131', '311222', '321122', '321221', '312212', '322112', '322211',
  '212123', '212321', '232121', '111323', '131123', '131321', '112313', '132113', '132311', '211313',
  '231113', '231311', '112133', '112331', '132131', '113123', '113321', '133121', '313121', '211331',
  '231131', '213113', '213311', '213131', '311123', '311321', '331121', '312113', '312311', '332111',
  '314111', '221411', '431111', '111224', '111422', '121124', '121421', '141122', '141221', '112214',
  '112412', '122114', '122411', '142112', '142211', '241211', '221114', '413111', '241112', '134111',
  '111242', '121142', '121241', '114212', '124112', '124211', '411212', '421112', '421211', '212141',
  '214121', '412121', '111143', '111341', '131141', '114113', '114311', '411113', '411311', '113141',
  '114131', '311141', '411131', '211412', '211214', '211232', '2331112',
]
const START_B = 104
const START_C = 105
const CODE_B = 100
const CODE_C = 99
const FNC1 = 102
const STOP = 106

type Token = number | 'FNC1'

/** Splits a GS1 bracketed string into FNC1-separated data: (01)x(10)y → FNC1 01x 10y (FNC1 after variable fields). */
function gs1Tokens(s: string): Token[] {
  const fixed = new Set(['00', '01', '02', '11', '13', '15', '17'])
  const out: Token[] = ['FNC1']
  const parts = [...s.matchAll(/\((\d{2,4})\)([^(]*)/g)]
  parts.forEach((m, i) => {
    for (const ch of m[1] + m[2]) out.push(ch.charCodeAt(0))
    if (!fixed.has(m[1]) && i < parts.length - 1) out.push('FNC1')
  })
  return out
}

export function code128Values(data: string, gs1 = false): number[] {
  const tokens: Token[] = gs1 ? gs1Tokens(data) : [...data].map((c) => c.charCodeAt(0))
  const isDigit = (t: Token | undefined) => typeof t === 'number' && t >= 48 && t <= 57
  const digitRun = (from: number) => { let n = 0; while (isDigit(tokens[from + n])) n++; return n }
  const values: number[] = []
  let set: 'B' | 'C' | null = null
  let i = 0
  while (i < tokens.length) {
    const t = tokens[i]
    const run = digitRun(i)
    const wantC = run >= 4 || (set === 'C' && run >= 2)
    if (t === 'FNC1') {
      if (set === null) { values.push(digitRun(i + 1) >= 2 ? START_C : START_B); set = digitRun(i + 1) >= 2 ? 'C' : 'B' }
      values.push(FNC1)
      i++
      continue
    }
    if (wantC) {
      if (set !== 'C') { values.push(set === null ? START_C : CODE_C); set = 'C' }
      const pairs = Math.floor(run / 2)
      for (let p = 0; p < pairs; p++) {
        values.push((Number(tokens[i]) - 48) * 10 + (Number(tokens[i + 1]) - 48))
        i += 2
      }
      continue
    }
    if (set !== 'B') { values.push(set === null ? START_B : CODE_B); set = 'B' }
    const code = t as number
    if (code < 32 || code > 126) throw new Error('Code 128 B: printable ASCII only')
    values.push(code - 32)
    i++
  }
  const checksum = values.reduce((sum, v, k) => sum + v * (k === 0 ? 1 : k), 0) % 103
  return [...values, checksum, STOP]
}

/** Module widths, bar first, alternating bar/space. */
export function code128Modules(data: string, gs1 = false): number[] {
  return code128Values(data, gs1).flatMap((v) => [...PATTERNS[v]].map(Number))
}

/** An SVG barcode, `height` px tall, scaled to `width` px with a 10-module quiet zone each side. */
export function code128Svg(data: string, opts: { gs1?: boolean; height?: number } = {}): string {
  const modules = code128Modules(data, opts.gs1)
  const total = modules.reduce((a, b) => a + b, 0) + 20
  const h = opts.height ?? 60
  let x = 10
  const bars: string[] = []
  modules.forEach((w, k) => {
    if (k % 2 === 0) bars.push(`<rect x="${x}" y="0" width="${w}" height="${h}"/>`)
    x += w
  })
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${total} ${h}" preserveAspectRatio="none" role="img" aria-label="${data.replace(/"/g, '')}"><g fill="#000">${bars.join('')}</g></svg>`
}
