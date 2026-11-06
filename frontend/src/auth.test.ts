import { describe, expect, it } from 'vitest'
import type { User } from 'oidc-client-ts'
import { isPlatformOperator, toSession } from './auth'
import { query } from './api'

function tokenWith(claims: Record<string, unknown>): string {
  const b64 = (o: unknown) => btoa(JSON.stringify(o)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
  return `${b64({ alg: 'RS256' })}.${b64(claims)}.signature`
}

describe('toSession', () => {
  it('reads tenant, AstraWMS roles and access scopes from the access token', () => {
    const s = toSession({ access_token: tokenWith({
      preferred_username: 'alice', tenant_id: 'demo',
      realm_access: { roles: ['PICKER', 'offline_access', 'default-roles-astrawms', 'SUPERVISOR'] },
      wms_sites: ['DC1', 'DC2'], wms_owners: ['*'], approval_limit: '5000',
    }) } as User)
    expect(s.userName).toBe('alice')
    expect(s.tenant).toBe('demo')
    expect(s.roles).toEqual(['PICKER', 'SUPERVISOR'])
    expect(s.sites).toEqual(['DC1', 'DC2'])
    expect(s.owners).toBe('*')
    expect(s.approvalLimit).toBe('5000')
  })

  it('treats missing scopes as none', () => {
    const s = toSession({ access_token: tokenWith({ sub: 'x', tenant_id: 't' }) } as User)
    expect(s.sites).toEqual([])
    expect(s.roles).toEqual([])
  })
})

describe('platform operator (ADR-0031)', () => {
  it('is a sign-in without a tenant that holds PLATFORM_ADMIN', () => {
    const operator = toSession({ access_token: tokenWith({ preferred_username: 'ops', realm_access: { roles: ['PLATFORM_ADMIN'] } }) } as User)
    expect(operator.roles).toEqual(['PLATFORM_ADMIN'])
    expect(isPlatformOperator(operator)).toBe(true)
  })

  it('is not a tenant user, nor a user without a tenant and without the role', () => {
    const tenantUser = toSession({ access_token: tokenWith({ tenant_id: 'demo', realm_access: { roles: ['PLATFORM_ADMIN', 'SOLUTION_ADMIN'] } }) } as User)
    const nobody = toSession({ access_token: tokenWith({ realm_access: { roles: ['SOLUTION_ADMIN'] } }) } as User)
    expect(isPlatformOperator(tenantUser)).toBe(false)
    expect(isPlatformOperator(nobody)).toBe(false)
  })
})

describe('query', () => {
  it('drops empty parameters', () => {
    expect(query({ status: 'OPEN', itemNo: '', limit: 10, lot: undefined })).toBe('?status=OPEN&limit=10')
    expect(query({ a: '' })).toBe('')
  })
})
