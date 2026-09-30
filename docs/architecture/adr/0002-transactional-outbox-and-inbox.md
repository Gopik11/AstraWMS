# ADR-0002: Transactional Outbox, Ordered Relay and Inbox De-duplication

- **Status:** Accepted
- **Date:** 2026-09-30

## Context

A state change and the event describing it must never diverge. That is especially true for `GoodsMovement` messages, which become ERP postings (INT-010, INT-011). Consumers must tolerate redelivery, and the ERP adapter needs per-key ordering (INT-013) and gap detection (ISD-00 §5).

## Decision

1. **Outbox** (`astra-common` `OutboxWriter`): events are inserted into `outbox` in the same transaction as the business change. Calling it outside a transaction is an error.
2. **Per-key sequence**: `outbox_key_sequence` gives each `(topic, tenant:businessKey)` a gap-free, monotonic `sequence` in the envelope. It is incremented inside the business transaction, so a rollback leaves no gap.
3. **Relay** (`OutboxRelay`): publishes unpublished rows in id order.
   - A transaction-scoped Postgres advisory lock makes exactly one replica relay at a time, which preserves ordering across horizontally scaled replicas.
   - Kafka key = `tenant:businessKey`, which keeps ordering within a partition.
   - CloudEvents-style headers: `ce_id`, `ce_type`, `ce_source`, `tenantid`.
4. **At-least-once**: a crash between the Kafka send and the `published_at` update re-sends the message. The producer is idempotent (`enable.idempotence=true`, `acks=all`).
5. **Inbox** (`InboxGuard`): consumers record `(sourceSystem, messageId)` in the processing transaction. A redelivered message is skipped.
6. **Stale protection**: projections apply master data only if its `sourceChangedAt` is not older than the stored version.

## Consequences

- Relay throughput is bounded by a single active relay per service database. This is sufficient for the design volumes: 30,000 goods movements a day, and 200,000 orders a day in later services. If needed, the relay can later be partitioned by key hash with one advisory lock per partition.
- Holding the advisory lock during Kafka sends makes the relay transaction as long as the batch send, which is at most 200 messages with a 10 s timeout each.
- Outbox rows are retained. A housekeeping job (next slice) will purge rows published more than 7 days ago; that matches the Kafka retention in §F.8.1.
