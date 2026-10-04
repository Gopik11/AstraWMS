import type { Role } from './auth'

/**
 * The menu (ADR-0024, ADR-0026). At a satellite store only store work is shown: RF work and RF material issue, the
 * overview (store home), receipts and transfers coming in, material issues, stock inquiry and cycle counts. Waves,
 * packing, loads, yard, billing, management, the ERP simulator, integration and the other DC screens stay at the DC.
 */
export interface NavItem {
  to: string
  label: string
  roles?: Role[]          // undefined = every signed-in user (reads)
  /** Shown at a satellite store too (ADR-0024: a store works transfers in, issues, counts and its sync state). */
  store?: boolean
}

export const NAV: { group: string; items: NavItem[] }[] = [
  { group: 'Floor', items: [{ to: '/rf', label: 'RF work', roles: ['RECEIVER', 'PICKER', 'INV_ANALYST', 'SUPERVISOR'], store: true },
    { to: '/rf/issue', label: 'RF material issue', roles: ['RECEIVER', 'PICKER', 'INV_MANAGER', 'SUPERVISOR'], store: true }] },
  {
    group: 'Operations',
    items: [
      { to: '/', label: 'Overview', store: true },
      { to: '/network', label: 'Site network', roles: ['SUPERVISOR', 'INV_MANAGER', 'SOLUTION_ADMIN'] },
      { to: '/management', label: 'Management', roles: ['INV_MANAGER', 'SOLUTION_ADMIN'] },
      { to: '/receipts', label: 'Receipts', store: true },
      { to: '/returns', label: 'Customer returns' },
      { to: '/orders', label: 'Outbound orders' },
      { to: '/waves', label: 'Waves', roles: ['SUPERVISOR'] },
      { to: '/pack', label: 'Pack station', roles: ['PICKER', 'SUPERVISOR'] },
      { to: '/loads', label: 'Loads', roles: ['PICKER', 'SUPERVISOR'] },
      { to: '/tasks', label: 'Tasks' },
    ],
  },
  {
    group: 'Inventory',
    items: [
      { to: '/inventory', label: 'Stock inquiry', store: true },
      { to: '/items', label: 'Items across sites' },
      { to: '/store-replenishment', label: 'Store replenishment', roles: ['SUPERVISOR', 'INV_MANAGER', 'SOLUTION_ADMIN'] },
      { to: '/recall', label: 'Recall', roles: ['QA_MANAGER', 'INV_MANAGER', 'SUPERVISOR', 'SOLUTION_ADMIN'] },
      { to: '/counts', label: 'Cycle counts', roles: ['INV_ANALYST', 'INV_MANAGER', 'SUPERVISOR'], store: true },
      { to: '/replenishment', label: 'Replenishment', roles: ['SUPERVISOR', 'INV_MANAGER', 'SOLUTION_ADMIN'] },
      { to: '/slotting', label: 'Slotting', roles: ['SUPERVISOR', 'INV_MANAGER', 'SOLUTION_ADMIN', 'INV_ANALYST'] },
      { to: '/labor', label: 'Labor', roles: ['SUPERVISOR', 'SOLUTION_ADMIN'] },
      { to: '/yard', label: 'Yard', roles: ['SUPERVISOR', 'RECEIVER'] },
      { to: '/billing', label: 'Billing', roles: ['SOLUTION_ADMIN', 'INV_MANAGER', 'SUPERVISOR'] },
      { to: '/transfers', label: 'Transfers', roles: ['SUPERVISOR', 'INV_MANAGER', 'RECEIVER', 'PICKER'], store: true },
      { to: '/labels', label: 'Labels', roles: ['SOLUTION_ADMIN', 'SUPERVISOR', 'INV_MANAGER', 'INV_ANALYST', 'RECEIVER'] },
      { to: '/material-issues', label: 'Material issues', roles: ['RECEIVER', 'PICKER', 'INV_ANALYST', 'INV_MANAGER', 'SUPERVISOR', 'SOLUTION_ADMIN'], store: true },
      { to: '/adjust', label: 'Adjust / status', roles: ['INV_ANALYST', 'INV_MANAGER', 'SUPERVISOR', 'QA_MANAGER'] },
    ],
  },
  {
    group: 'Setup',
    items: [
      { to: '/master-data', label: 'Master data', roles: ['SOLUTION_ADMIN'] },
      { to: '/erp', label: 'ERP simulator', roles: ['ERP_INTEGRATION', 'SOLUTION_ADMIN'] },
      { to: '/integration', label: 'Integration', roles: ['ERP_INTEGRATION', 'SOLUTION_ADMIN'] },
      { to: '/operations', label: 'Operations', roles: ['SOLUTION_ADMIN'] },
    ],
  },
]

/** The groups and items a user sees: their roles, and at a store only the store items. */
export function visibleNav(hasRole: (...roles: Role[]) => boolean, isStore: boolean): { group: string; items: NavItem[] }[] {
  return NAV.map((g) => ({ group: g.group, items: g.items.filter((i) => (!i.roles || hasRole(...i.roles)) && (!isStore || i.store)) }))
    .filter((g) => g.items.length > 0)
}
