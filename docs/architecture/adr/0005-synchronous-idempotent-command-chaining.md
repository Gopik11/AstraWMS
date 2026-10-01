# ADR-0005: Synchronous, Idempotent Command Chaining for RF Paths

- **Status:** Accepted
- **Date:** 2026-09-30

## Context

An RF receipt must update two services: the Inbound service (expectation progress) and the Inventory service (stock on the dock). The operator needs an immediate answer: accepted, over tolerance, or incompatible location. A distributed transaction is not available, and an asynchronous saga would report inventory-side rejections only after the operator has moved on. Scope §F.5 prescribes synchronous orchestration with idempotency keys for latency-critical RF steps.

## Decision

1. The RF client sends an `Idempotency-Key` per user action.
2. Inbound reserves the key in `receive_request` (same transaction), validates the expectation rules, and then calls Inventory **synchronously** with the derived key `INB-<key>` (`INB-<key>#<line>` for each SSCC content line).
3. Inventory's problem codes are passed through unchanged (e.g. `INV_LOCATION_INCOMPATIBLE`), so the RF device sees one error vocabulary.
4. Failure handling:

   | Situation | Outcome |
   |---|---|
   | Inventory rejects | Inbound rolls back and records nothing. The same key may be retried after the cause is fixed. |
   | Inventory is unreachable or returns 5xx | 503 `INB_INVENTORY_UNAVAILABLE`, which is safe to retry with the same key |
   | Inbound fails after Inventory succeeded (crash, commit failure) | The retry reaches Inventory with the same derived key. Inventory replays its original operation, and Inbound records the receipt exactly once. |

5. The Inventory call is made inside the Inbound transaction, so row locks on the expectation are held for at most the HTTP timeout (3 s). That is acceptable for one expectation per dock door.

## Consequences

- There is no orphan stock and no double receipt, provided clients retry with the same key. Clients must persist the key per scan and not per HTTP attempt.
- If an operator abandons an action after Inventory succeeded and never retries, stock exists in Inventory without a receipt record in Inbound. Two mechanisms catch this:
  - The dock-to-stock reconciliation (next slice) compares dock-location stock with open expectations.
  - The key is visible in both ledgers (`inventory_operation.idempotency_key` = `INB-<key>`).
- The pattern is reusable for pick and pack confirmations, e.g. Task → Inventory.
