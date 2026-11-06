import { describe, expect, it } from 'vitest'
import { parseScope, TRANSITIONS } from './tenancy'

describe('user scope input (ADR-0031)', () => {
  it('reads ids separated by commas, and * as all', () => {
    expect(parseScope('DC1, DC2 ,')).toEqual(['DC1', 'DC2'])
    expect(parseScope('DC1, *')).toEqual(['*'])
    expect(parseScope('  ')).toEqual([])
  })
})

describe('tenant status changes offered (ADR-0030 A)', () => {
  it('never offers purging, and lets an offboarding tenant only be reactivated', () => {
    expect(Object.values(TRANSITIONS).flat()).not.toContain('PURGED')
    expect(TRANSITIONS.OFFBOARDING).toEqual(['ACTIVE'])
    expect(TRANSITIONS.PURGED).toEqual([])
  })
})
