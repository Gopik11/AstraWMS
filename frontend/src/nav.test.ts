import { describe, expect, it } from 'vitest'
import { visibleNav } from './nav'

const everything = () => true
const labels = (isStore: boolean) => visibleNav(everything, isStore).flatMap((g) => g.items.map((i) => i.label))

describe('menu at a store (ADR-0026)', () => {
  it('hides DC modules and keeps store work', () => {
    const store = labels(true)
    for (const hidden of ['Waves', 'Pack station', 'Loads', 'Yard', 'Billing', 'Management', 'ERP simulator', 'Integration']) {
      expect(store).not.toContain(hidden)
    }
    expect(store).toEqual(expect.arrayContaining(['RF work', 'RF material issue', 'Overview', 'Receipts', 'Transfers',
      'Material issues', 'Stock inquiry', 'Cycle counts']))
  })

  it('shows the DC modules at the DC, by role', () => {
    expect(labels(false)).toEqual(expect.arrayContaining(['Waves', 'Yard', 'Billing', 'Management', 'Integration']))
    const picker = visibleNav((...r) => r.includes('PICKER'), false).flatMap((g) => g.items.map((i) => i.label))
    expect(picker).toContain('Pack station')
    expect(picker).not.toContain('Billing')
  })
})
