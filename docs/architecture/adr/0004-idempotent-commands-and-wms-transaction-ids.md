# ADR-0004: Idempotent Commands and WMS Transaction IDs

- **Status:** Accepted
- **Date:** 2026-09-30

## Context

RF devices retry on flaky Wi-Fi, and integration callers retry on timeouts. A retried stock movement must never apply twice (NFR-123). Every ERP-relevant movement needs an identifier of at most 16 characters that the ERP can store for duplicate checks: SAP `XBLNR` / `REF_DOC_NO`, or the Oracle transaction reference (INT-014, ISD-00 §3.2).

## Decision

1. Every inventory POST requires an `Idempotency-Key` header of up to 100 characters. `inventory_operation` stores the key, a SHA-256 hash of the request, and the response.
   - **Same key and same body:** the stored response is returned with `replayed: true` and HTTP 200, with no second effect.
   - **Same key and a different body:** 422 `IDEMPOTENCY_KEY_REUSED`.
   - **Concurrent duplicates:** these serialise on the unique index. The loser reads the winner's committed result.
   - **Failed requests:** these roll back completely, including the operation row, so a retry re-executes.
2. `WmsTxnId` is `W` + 9 Crockford-Base32 characters of epoch milliseconds + 6 characters that are random at the start of each millisecond and then incremented. That gives 16 characters, sorted by creation order, with no collisions within a process.
3. Each ERP-relevant `GoodsMovement` carries its own `wmsTxnId`, recorded in `erp_movement`, and the business key is site + item (ISD IF-INV-001). An operation affecting several items, such as a mixed LPN transfer, yields one movement per item. The first movement reuses the operation's ID for traceability.

## Consequences

- Clients (RF app, adapters) must generate and persist the key per user action, not per HTTP attempt.
- The ERP adapter can always answer "was this already posted?" by `wmsTxnId`, which makes store-and-forward replays safe.
