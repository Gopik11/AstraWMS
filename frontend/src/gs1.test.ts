import { describe, expect, it } from 'vitest'
import { parseGs1 } from './gs1'

describe('parseGs1', () => {
  it('reads bracketed GTIN, lot, expiry and serial', () => {
    expect(parseGs1('(01)09506000134352(17)271231(10)LOT-7(21)SN1')).toEqual({
      gtin: '09506000134352', lot: 'LOT-7', expiry: '2027-12-31', serial: 'SN1',
      sscc: undefined, contentGtin: undefined, bestBefore: undefined, count: undefined,
    })
  })

  it('reads raw scans with a symbology identifier and group separators', () => {
    const d = parseGs1(']C10109506000134352' + '10LOT-7\u001d' + '17270600' + '3712')
    expect(d?.gtin).toBe('09506000134352')
    expect(d?.lot).toBe('LOT-7')
    expect(d?.expiry).toBe('2027-06-30')
    expect(d?.count).toBe(12)
  })

  it('reads a pallet SSCC with its content GTIN', () => {
    const d = parseGs1('(00)106141410000000019(02)09506000134352(37)24')
    expect(d?.sscc).toBe('106141410000000019')
    expect(d?.contentGtin).toBe('09506000134352')
    expect(d?.count).toBe(24)
  })

  it('leaves item numbers, bare GTINs and bad data alone', () => {
    expect(parseGs1('SKU-1')).toBeNull()
    expect(parseGs1('09506000134352')).toBeNull()
    expect(parseGs1('(01)09506000134353')).toBeNull()
    expect(parseGs1('(17)271399')).toBeNull()
  })
})
