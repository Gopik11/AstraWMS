# Architecture Decision Records

| ADR | Decision |
|---|---|
| [0001](0001-service-stack.md) | Java 21, Spring Boot 4.1, PostgreSQL (JdbcClient, no JPA), Kafka, Maven monorepo, Testcontainers |
| [0002](0002-transactional-outbox-and-inbox.md) | Transactional outbox with per-key sequences, single ordered relay, inbox de-duplication |
| [0003](0003-tenant-isolation-with-row-level-security.md) | Tenant isolation with Postgres RLS and a non-owner application role |
| [0004](0004-idempotent-commands-and-wms-transaction-ids.md) | Idempotency-Key on all commands; 16-char time-ordered WMS transaction IDs for ERP de-duplication |
| [0005](0005-synchronous-idempotent-command-chaining.md) | RF paths call downstream services synchronously with derived idempotency keys (Inbound → Inventory) |
| [0006](0006-erp-adapter-architecture.md) | ERP adapters: pure mapping code, `SapGateway` boundary, simulated SAP backend, posting results and application acks |
| [0007](0007-dead-letter-topics-and-messaging-housekeeping.md) | Platform consumer error policy: backoff, then `<topic>.dlq`; outbox/inbox retention purge |

New ADRs use the next number. The files are immutable once accepted: to change a decision, write a new ADR that supersedes the old one.
