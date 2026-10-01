# ADR-0014: Cycle Counting Owned by Inventory, Executed as RF Count Tasks

- **Status:** Accepted
- **Date:** 2026-10-01

## Context

Stock accuracy needs cycle counts (§6.3):
- **Blind counting** (INV-003).
- **Auto-acceptance** only within a tolerance of units and value, and never for serial items.
- **Independent recounts.**
- **Approval** by someone who did not count (INV-008), within their value limit (§G.5.1).
- **A count after every short pick** (PCK-003 b).

## Decision

1. **Inventory owns counts** (`stock_count` with results and variance; one open count per location).
   - Created ad hoc by inventory control (`POST /inventory/counts`), or automatically by a short pick at the source location (trigger `SHORT_PICK`).
   - Each count sequence is requested from the task service with `CountRequested` on the task request topic.
2. **The task service executes COUNT tasks.**
   - Recounts list the earlier counters in `excludedUsers`; `tasks/next` never hands such a task to them.
   - The RF screen shows the location only, never its contents or system quantity.
   - The counter scans the location check digit and enters what is there (owner, item, lot, LPN, quantity; none = empty).
   - The task service submits the result to inventory with the key `TSK-<taskId>`.
3. **Evaluation**, against the stock at the location at submission time (all stock statuses summed):

   | Outcome | Result |
   |---|---|
   | No variance | `CLOSED` |
   | First count within tolerance (`astra.inventory.count.tolerance-units`, default 2; `tolerance-value`, default 50.00; known cost; no serial item) | Auto-adjusted with `CC_TOL` → `ADJUSTED` |
   | Otherwise | `RECOUNT` by another user. If the recount equals the first count → `PENDING_APPROVAL`. If not, a third count → `PENDING_APPROVAL` |

4. **Decision.**
   - An `INV_MANAGER` or `SUPERVISOR` approves; the approver may not be a counter.
   - The approver's `approval_limit` must cover the variance value (standard cost × |variance|).
   - The variance is re-evaluated against current stock and posted with `CC_VAR` (ERP goods movement, approver recorded).
   - Rejecting closes the count without adjustment (investigate: mis-putaway, open tasks).
5. **Blindness in the API.** A counter's submit returns only the status. Count details (system quantities, earlier counts) are visible to inventory control roles only.

## Consequences

- Short picks now produce count tasks in the RF queue. Operators complete them like any other task, as the updated wave smoke test does.
- The smoke test `smoke-counts.sh` covers, on the real stack:
  - tolerance auto-adjustment;
  - a recount not offered to the first counter;
  - counters not seeing system quantities;
  - manager approval with the variance posted to simulated SAP.
- Not built yet:
  - scheduled counts (ABC, location-based, opportunistic) and wall-to-wall physical inventory with a freeze;
  - SAP physical inventory documents (`BAPI_MATERIAL_PHYSINV_*`) as an alternative to net goods movements;
  - serial counting;
  - blocking picks at a location while it is being counted.
- A negative variance on stock that is still allocated fails with `INV_STOCK_ALLOCATED`. De-allocation with automatic re-allocation (INV-005, OUT-EX-05) follows with the replenishment slice.
