# ADR-0008: Directed Putaway in a Task Service Fed by Event-Carried State

- **Status:** Accepted
- **Date:** 2026-10-01

## Context

Received pallets must be directed from the dock to a storage location that respects compatibility, mixing and capacity rules (scope §2.3, PUT-001/002). Operators execute the move on RF. The engine needs current stock per location, but querying the Inventory service synchronously for every candidate location would couple the services tightly and be slow.

## Decision

1. **A separate `task-service`** owns tasks: their lifecycle, assignment, exceptions and audit events (input for labor management, §C.2).
2. **Event-carried state.** The task service keeps its own projections:
   - items and locations from `MasterDataEvents`;
   - stock per balance key from `InventoryChanged.lines[].qtyAfter`.

   Applying the quantity after the change, not the delta, makes redelivery harmless. `InventoryChanged` therefore moved to the shared contracts, and `LocationUpserted` gained `checkDigit` and `pickSeq` (additive changes).
3. **Trigger.** A `RECEIPT`/`MOVE_IN` of an LPN into an inbound staging location type (`DOOR`, `DOCK`, `STAGING`, `STAGING_IN`) creates one putaway task per LPN. A partial unique index enforces "one open putaway per LPN".
4. **Engine.**
   - Hard constraints first: active, not staging, temperature-class equality (ambient goods only in unclassified locations), hazmat, mixed-item/lot rules, and LPN capacity per location type including reservations.
   - Then strategies in order: `CONSOLIDATE` (same owner/item), then `EMPTY_NEAREST` (lowest pick sequence).
   - Planning is serialised per site with a transaction advisory lock, and open tasks reserve their target, so two pallets never get the same slot.
   - When no location fits, the task becomes `EXCEPTION/NO_LOCATION` (PUT-EX-01) and can be re-planned.
5. **RF execution.**
   - `next` resumes the operator's own task or assigns the highest-priority, oldest released task (`FOR UPDATE SKIP LOCKED`).
   - `confirm` requires the scanned LPN and the location's check digit. Any location other than the target must pass the same hard constraints (`OVERRIDE`).
   - The stock move is a synchronous call to Inventory with key `TSK-<taskId>` (ADR-0005), so a repeated confirm is a no-op.
   - Exceptions `LOCATION_BLOCKED` / `LOCATION_OCCUPIED` exclude the target and re-plan (PUT-EX-02).
6. **Consistency.** An LPN that leaves its staging location through another operation cancels its open task.

## Consequences

- Putaway planning works from projections that may lag inventory by the event latency (sub-second locally). The confirm step is authoritative: Inventory re-validates compatibility and stock when it executes the move.
- `stockAtSite` reads all of a site's stock for each plan. That is fine at current volumes. Large sites will need a pre-aggregated occupancy table or an indexed candidate query (the slotting work in §C.1).
- Task types beyond `PUTAWAY` (replenishment, picking, counting) use the same table, queue and RF verbs; the check constraint on `task_type` widens with them.
- Capacity is LPN-count based. Weight/volume capacity (§2.3 capacity algorithm) needs dimensions on LPNs, which comes with cartonization and measurement data.
