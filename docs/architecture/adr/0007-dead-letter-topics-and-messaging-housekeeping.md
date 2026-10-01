# ADR-0007: Dead-Letter Topics and Messaging Housekeeping

- **Status:** Accepted. Amends ADR-0002 (outbox retention) and ADR-0006 (retry exhaustion).
- **Date:** 2026-09-30

## Context

Until now, a consumer message that kept failing was logged and skipped after its retries, and published outbox rows were never deleted. Neither is acceptable for production. A skipped confirmation is an unposted goods receipt (INT-011), and an unbounded outbox degrades the relay query.

## Decision

1. **One platform error policy for every Kafka consumer** (`KafkaErrorHandling`, auto-configured in `astra-common`):

   | Failure | Handling |
   |---|---|
   | Transient | Retried in place with exponential backoff: `astra.kafka.retry.*`, default 1 s → 30 s, 5 min in total |
   | Poison: `PoisonMessageException` (e.g. `MappingException`) or unparseable JSON | Not retried; dead-lettered immediately |
   | Retries exhausted, or poison | Published to `<topic>.dlq` with the original key, value and headers, plus the `kafka_dlt-*` diagnostic headers (exception class and message, original topic, partition and offset) |

   The consumer offset is committed only after the record has been processed or dead-lettered.

2. **Housekeeping** (`MessagingHousekeeping`, hourly, in batches of 5,000):
   - Delete outbox rows **published** more than 7 days ago. Unpublished rows and `outbox_key_sequence` are never deleted.
   - Delete inbox rows older than 30 days. Inbox retention must exceed the longest redelivery window (topic retention plus DLQ replay), which the configuration enforces: inbox retention ≥ outbox retention.

## Consequences

- No message is silently lost. DLQ depth becomes an operational metric; alert on any message in a `.dlq` topic (ISD-00 §8).
- **Replay** of a dead-lettered message re-publishes it to the original topic after the cause is fixed. The inbox makes a replay idempotent, as long as it happens within the inbox retention. A replay tool (integration workbench, §D.6.3) is future work; until then replay is operator tooling (`kafka-console-*`).
- A DLQ replay older than 30 days could be applied twice. Operations must replay within that window or raise the retention.
