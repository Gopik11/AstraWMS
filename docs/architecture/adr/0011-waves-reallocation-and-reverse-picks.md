# ADR-0011: Wave Release, Re-allocation after Short Picks, Reverse Picks for Cancelled Orders

- **Status:** Accepted
- **Date:** 2026-10-01
- **Extends:** ADR-0009. That ADR listed the three gaps closed here: waveless release only, no re-allocation, no return-to-stock.

## Context

ADR-0009 allocated and released every order as soon as it arrived from the ERP. A short pick was reported to the ERP straight away. A cancellation after the first pick was rejected. The scope asks for more:

- **Wave release**, with a preview before release (§C.4, ADV-030).
- **Changes before release** (OUT-002).
- **A re-allocation attempt after a short pick** (PCK-003 c).
- **Reverse picks for cancelled picked orders.** The ERP acknowledgement is held until the stock is back in an allocable status (OUT-EX-02, IF-OB-002 rule OB002-R02).

## Decision

1. **Release mode per site** (`PUT /outbound/config`, `SOLUTION_ADMIN`).
   - **`WAVELESS`** (default) keeps the ADR-0009 behaviour.
   - **`WAVE`:** new orders are `POOLED`; nothing is allocated yet.
     - ERP changes to a pooled order replace its lines and are accepted (OUT-002). After release, changes are still rejected with `RELEASED_TO_PICK`.
     - Cancelling a pooled order needs no inventory call.

2. **Waves** (`/outbound/waves`, role `SUPERVISOR`):

   | Endpoint | Behaviour |
   |---|---|
   | `POST /waves/plan` | Preview with no side effects. Criteria: carrier, order type, goods issue before. Limits: max orders, max lines. Pooled orders are taken by planned goods issue. |
   | `POST /waves` | Creates a `PLANNED` wave of those orders; they are locked with `skip locked`, so two waves cannot take the same order. |
   | `POST /waves/{no}/release` | Allocates each order that is still pooled and requests its picks, with the same allocation code and idempotency keys as waveless release. A second release returns the wave unchanged. |

3. **Re-allocation after a short pick (PCK-003 c).**
   - When a `TaskCompleted` reports a short quantity, outbound asks inventory to allocate it again. The allocation excludes every location of that line where a pick came up short (new `excludeLocationIds` on `POST /inventory/allocations`), under the key `OUT-RA-<allocationId>`.
   - New allocations get pick tasks, linked to the allocation they replace (`replaces`). The order stays `RELEASED` until they are picked.
   - What cannot be re-allocated is counted in `qty_short_pick` and reported as `SHORT_PICK`. `NO_STOCK` now only means the quantity was never allocated.

4. **Cancellation after release (OUT-EX-02).** Allowed in `RELEASED`, `BACKORDERED` and `PICKED`.
   - Outbound releases the open allocations in inventory: inventory now releases only open quantity and leaves picked stock allocated. Outbound also cancels the open pick tasks.
   - Outbound then asks inventory which allocations are `PICKED`. Inventory is the authority: its release locks the order's allocations, so a pick that raced the cancellation is either included or rejected.
   - Each picked allocation becomes a `ReturnRequested` and the order goes to `CANCEL_REQUESTED`. The ERP acknowledgement is stored (`pending_cancel_ack`), not sent.
   - The task service creates a `RETURN` task: from outbound staging back to the location and LPN the stock came from. On RF the operator scans the check digit of that location (`POST /tasks/{id}/return`).
   - That calls `POST /inventory/allocations/{id}/return`: ledger `RETURN_OUT` / `RETURN_IN`, the stock is unallocated again, and the allocation becomes `RETURNED`.
   - When the last return completes, the order is `CANCELLED` and the stored acknowledgement is sent `ACCEPTED`. On the SAP side, the cancel IDoc reaches status 53 only then.

## Consequences

- The smoke test `smoke-waves.sh` runs the whole path against the real stack:
  - wave plan, create and release;
  - a short pick re-allocated and picked elsewhere;
  - a picked order cancelled from SAP, returned to stock and only then acknowledged;
  - the remaining order shipped complete and confirmed in simulated SAP.
- Release still happens in a single transaction, with one inventory call per order line. That is fine for waves of tens to hundreds of orders. ADV-032 (10,000 orders in 60 s) needs a batch allocation endpoint and chunked release; this is not built yet.
- Not built yet:
  - Wave templates with automatic release times.
  - Capacity checks, cartonization and work clustering (§C.4 steps 3–6).
  - The waveless WIP controller (ADV-031).
  - The automatic cycle count of a short-picked location (PCK-003 b). Until it exists, the shorted location keeps its book quantity and is only avoided for that order line.
- Picked stock of a cancelled order is returned to its original location, without re-running putaway rules. A different destination can be passed to the inventory endpoint; the task service does not choose one yet.
