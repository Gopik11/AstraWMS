import type { Task } from './api'

/**
 * Plain-words explanations on the RF task (ADR-0024): why this task came next and why its target was chosen. They
 * mirror the task service's rules (ADR-0019 putaway, ADR-0021 labor): the next task is the highest priority released
 * task the operator may do (role, zone, owner, skill, equipment), then pick-path order, then the oldest.
 */
const PUTAWAY: Record<string, string> = {
  PICK_FACE: 'the pick face of this item: available stock fills its pick location first',
  CONSOLIDATE: 'the same item is already there: consolidate before using an empty location',
  CONSOLIDATE_ZONE: "the same item is already there, in the item's slotting zone",
  EMPTY_NEAREST: 'the nearest empty location that fits (temperature, hazmat, mixing rules)',
  EMPTY_NEAREST_ZONE: "the nearest empty location in the item's slotting zone",
  EMPTY_FAR_SLOW_MOVER: 'a slow mover: a far empty location keeps the near ones for fast movers (slotting)',
  EMPTY_FAR_SLOW_MOVER_ZONE: "a slow mover: a far empty location in the item's slotting zone",
  QC_EMPTY: 'the stock is not available (QC, blocked or damaged): it goes to the QC area',
  QC_CONSOLIDATE: 'the stock is not available: consolidated in the QC area',
  OVERRIDE: 'the operator chose this location (override reason recorded)',
  ALLOCATION: "the location the order's allocation chose (site allocation policy: FEFO for lot-controlled items, FIFO for others, unless the site changed it)",
  RESLOT: 'the pick face of this item moved: its stock goes to the new face',
}

export function explainStrategy(strategy?: string | null): string | null {
  if (!strategy) return null
  return PUTAWAY[strategy] ?? strategy.replace(/_/g, ' ').toLowerCase()
}

export function whyNext(task: Task): string {
  const parts: string[] = []
  if (task.taskType === 'REPLEN') parts.push('replenishment comes before the picks waiting on this pick face')
  else if (task.priority > 50) parts.push(`priority ${task.priority}, above normal (50): a rush order or a supervisor raised it`)
  else parts.push(`priority ${task.priority}${task.priority === 50 ? ' (normal)' : ''}`)
  if (task.taskType === 'PICK' || task.taskType === 'REPLEN') parts.push('then the pick path from your area')
  parts.push('then the oldest work you are allowed to do (role, zone, skill, equipment)')
  return parts.join(', ')
}
