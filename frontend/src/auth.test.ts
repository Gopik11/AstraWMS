import { describe, expect, it } from 'vitest'
import type { User } from 'oidc-client-ts'
import { toSession } from './auth'
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

describe('query', () => {
  it('drops empty parameters', () => {
    expect(query({ status: 'OPEN', itemNo: '', limit: 10, lot: undefined })).toBe('?status=OPEN&limit=10')
    expect(query({ a: '' })).toBe('')
  })
})
