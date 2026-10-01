# ADR-0009: Allocation Owned by Inventory; Outbound Orchestrated Through Pick Events

- **Status:** Accepted
- **Date:** 2026-10-01

## Context

Outbound demand (IF-OB-001) must reserve stock without ever over-reserving under concurrency. Picks must move stock to outbound staging, and the shipment must remove it and confirm to the ERP (IF-OB-003). Three services are involved: Inventory (stock), Outbound (orders) and Task (RF work).

## Decision

1. **Inventory is the allocation authority.**

   | Operation | Behaviour |
   |---|---|
   | `POST /inventory/allocations` | Reserves `allocated_qty` on balances under row locks, in rotation order: FEFO by default, FIFO optional. Only from AVAILABLE stock in active locations that are not staging (PUT-006). Returns the allocations and any `shortQty`. |
   | `POST /inventory/allocations/{id}/pick` | Moves the quantity to outbound staging **still allocated**, so picked stock can never be re-allocated or adjusted. Validates serials at the source. `shortClose` releases the remainder (PCK-003). |
   | `POST /inventory/issues` | Removes all picked stock of an order at shipment. Serials become `SHIPPED`. |
   | `POST /inventory/allocations/release` | Frees open allocations, e.g. on cancellation. |

   All four are idempotent and recorded in the ledger (PICK_OUT / PICK_IN / ISSUE).
2. **The Outbound service orchestrates.**
   - It allocates on order receipt: hard allocation, with idempotency keys that include the order revision.
   - It publishes one `PickRequested` per allocation to `wms.task.requests.v1`.
   - It follows `TaskCompleted` events to `PICKED`.
   - On `ship` it issues the stock and sends `ShipmentConfirmation`.
   - It tracks the ERP result to `CONFIRMED` or `SHIP_ERROR`. The latter stays physically shipped and is resolved by a repost with the same `wmsTxnId` (SHP-005, INT-014).
3. **The Task service executes picks.** One `PICK` task per allocation, with an RF confirmation that scans the source location check digit; quantity below the request is a short pick.
4. **Changes after release are rejected.** ERP changes after release are rejected with an `ApplicationAck` (IF-OB-002 §6.1). Cancellations are accepted until the first pick: allocations are released and pick tasks cancelled through `PickCancelled`.
5. **Shortfalls are reported to the ERP, not managed in WMS.**
   - `NO_STOCK` means the line was allocated short.
   - `SHORT_PICK` means the operator picked less than allocated.
   - Both travel in the confirmation, and the ERP decides whether the remainder is backordered or cancelled (OUT-EX-01).

## Consequences

- The WMS never ships stock that another order holds, because the allocation constraint lives in the same database transaction as the balance.
- Order release currently happens at order receipt (waveless, one order at a time). Wave planning (§C.4) will put a release step between receipt and `PickRequested` without changing the contracts.
- There is no re-allocation after a short pick yet (PCK-003 (c)), and no return-to-stock for cancelled picked orders. Both are scheduled for the next outbound increment.
- Packing, cartonization and carrier labels (§5) are not part of this slice. `ship` corresponds to trailer close or carrier handover of the staged order.
