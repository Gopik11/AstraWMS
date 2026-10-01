# ADR-0015: Min/Max Replenishment Triggered by Inventory, Executed as RF Tasks

- **Status:** Accepted
- **Date:** 2026-10-01

## Context

Forward pick locations must be refilled from reserve before they run empty (§7.1 min/max). This needs:
- **Rotation:** replenishment in FEFO/FIFO order (RPL-001).
- **No over-replenishment:** a forward location never receives more than its rule allows (§7.2 step 3).
- **An RF task** that moves the stock.

## Decision

1. **Rules:** `replen_rule` per forward location, owner and item, with min and max quantity in the base unit.
   - Edited by `SOLUTION_ADMIN` or `INV_MANAGER`.
   - A location with a rule is a forward location: it is never used as a replenishment source.
2. **Trigger:** inventory evaluates the rule of every location that an operation took AVAILABLE stock out of (pick, move, adjustment, issue, count). If on hand + incoming (open replenishments) ≤ min, it replenishes up to max.
   - Supervisors can also run a top-off over all rules (`POST /replenishments/evaluate`).
3. **Source:** the allocation engine's candidates in rotation order (FEFO, then oldest receipt), excluding staging and forward locations.
   - The source stock is reserved like an allocation (`order_ref = REPL-<id>`), so orders cannot take it.
   - There is one replenishment per source balance.
4. **Execution:** `ReplenRequested` on the task request topic, then a REPLEN task. On RF the operator:
   1. takes the stock from the reserve location / LPN;
   2. drops it at the forward location;
   3. scans that location's check digit.

   Inventory then moves the reserved stock (`REPLEN_OUT` / `REPLEN_IN`, ERP bucket transfer if the buckets differ). It arrives unallocated, and the allocation ends `REPLENISHED`.

## Consequences

- `smoke-replenishment.sh` shows the loop on the real stack: an order picks a forward location below its minimum, a replenishment from reserve is created at once, and the RF task brings the location back to its maximum.
- Rotation also applies across locations. If newer stock sits in the forward location while older stock was moved elsewhere, the older stock is used first. The tests found this.
- Not built yet:
  - wave-driven (demand) and emergency replenishment, and dynamic priority (RPL-002, RPL-004);
  - rounding to case or pallet quantities;
  - two-step replenishment through P&D drop zones;
  - min/max recalculation by slotting (RPL-005);
  - a preference for forward locations in order allocation (allocation is purely by rotation today).
