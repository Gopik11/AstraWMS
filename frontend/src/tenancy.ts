/** Tenant registry and tenant user helpers shared by the platform console and the Users page (ADR-0030, ADR-0031). */

export type TenantStatus = 'ACTIVE' | 'SUSPENDED' | 'OFFBOARDING' | 'PURGED'

/** The status changes the platform API makes; PURGED is the purge procedure's alone (ADR-0030 D). */
export const TRANSITIONS: Record<TenantStatus, TenantStatus[]> = {
  ACTIVE: ['SUSPENDED', 'OFFBOARDING'],
  SUSPENDED: ['ACTIVE', 'OFFBOARDING'],
  OFFBOARDING: ['ACTIVE'],
  PURGED: [],
}

/** "DC1, DC2" or "*" → list; empty stays empty (the server asks for a value). */
export function parseScope(text: string): string[] {
  const parts = text.split(',').map((v) => v.trim()).filter(Boolean)
  return parts.includes('*') ? ['*'] : parts
}
