import { describe, expect, it } from 'vitest'
import { code128Values } from './code128'

describe('code128Values', () => {
  it('encodes text in code set B with the mod-103 checksum', () => {
    // Wikipedia example "Wikipedia": Start B, then values, checksum 88, stop
    expect(code128Values('Wikipedia')).toEqual([104, 55, 73, 75, 73, 80, 69, 68, 73, 65, 88, 106])
  })

  it('switches to code set C for digit runs', () => {
    const v = code128Values('A-01-1234')
    expect(v[0]).toBe(104) // start B
    expect(v).toContain(99) // code C for "1234"
    expect(v.at(-1)).toBe(106)
  })

  it('starts GS1-128 with FNC1 and packs the GTIN as pairs', () => {
    const v = code128Values('(01)10614141000415', true)
    expect(v.slice(0, 3)).toEqual([105, 102, 1]) // start C, FNC1, "01"
    expect(v).toHaveLength(2 + 8 + 2) // start + FNC1 + 8 pairs + checksum + stop
  })
})
