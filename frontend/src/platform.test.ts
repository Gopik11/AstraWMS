import { describe, expect, it } from 'vitest'
import { TENANT_ID, TRANSITIONS, USERNAME } from './platform'

describe('platform view rules (match TenantDirectory)', () => {
  it('accepts the tenant ids the API accepts', () => {
    for (const ok of ['demo', 'acme_eu-1', 'A'.repeat(64)]) expect(TENANT_ID.test(ok)).toBe(true)
    for (const bad of ['', 'acme corp', 'acme.eu', 'A'.repeat(65)]) expect(TENANT_ID.test(bad)).toBe(false)
  })

  it('accepts the usernames the API accepts', () => {
    for (const ok of ['ann', 'ann.lee@acme', 'admin_1']) expect(USERNAME.test(ok)).toBe(true)
    for (const bad of ['an', 'Ann', '.ann', 'ann lee']) expect(USERNAME.test(bad)).toBe(false)
  })

  it('offers only the allowed status changes, never PURGED', () => {
    expect(TRANSITIONS.ACTIVE).toEqual(['SUSPENDED', 'OFFBOARDING'])
    expect(TRANSITIONS.OFFBOARDING).toEqual(['ACTIVE'])
    expect(TRANSITIONS.PURGED).toBeUndefined()
    expect(Object.values(TRANSITIONS).flat()).not.toContain('PURGED')
  })
})
