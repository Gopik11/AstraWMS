/** Rules of the platform API (ADR-0030 A), mirrored for the platform view's forms; the API stays the authority. */

/** Status changes the platform API allows (TenantDirectory.TRANSITIONS); PURGED is the purge procedure's alone. */
export const TRANSITIONS: Record<string, string[]> = {
  ACTIVE: ['SUSPENDED', 'OFFBOARDING'],
  SUSPENDED: ['ACTIVE', 'OFFBOARDING'],
  OFFBOARDING: ['ACTIVE'],
}

/** Plans with a quota of their own (TenantQuotaProperties); any other name falls back to STANDARD's quota. */
export const PLANS = ['STANDARD', 'LARGE']

export const TENANT_ID = /^[A-Za-z0-9_-]{1,64}$/
export const USERNAME = /^[a-z0-9][a-z0-9._@-]{2,63}$/
