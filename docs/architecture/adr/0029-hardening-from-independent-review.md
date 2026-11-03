# ADR-0029: Hardening from the Independent Architecture Review

- **Status:** Accepted
- **Date:** 2026-11-03
- **Supersedes in part:** ADR-0002 (single relay lock), ADR-0018 ("not built yet": Kafka ACLs, VPS backups), ADR-0025
  (silent fail-open of label checks)

## Context

Two independent reports reviewed AstraWMS on 3 November 2026:

- **Source architecture and concurrency review** (checkout `1ad4f4e`): no Critical findings, four High (H-01 to
  H-04), five Medium/Low (M-01 to M-05).
- **Gap analysis and product roadmap** (read-only review of the test environment): seven P0 gaps to close before a
  production pilot, then P1/P2 product capabilities against Manhattan, SAP EWM, Blue Yonder and Körber.

Each finding was checked against the code before changing anything:

- Most were confirmed as described.
- Two had a sharper cause than the report gives:
  - **H-04:** the real risk is a reservation left behind when a refused or failed strict transfer rolls back, not
    only the advisory preflight.
  - **Operations page "Loading…":** dead letters were read topic by topic, up to 15 s each, and the page waited on
    all six services together.

## Decision

### A. Outbox relay: partitioned, no transaction across Kafka I/O (H-01, M-04)

`OutboxRelay` no longer holds one transaction-scoped lock for a whole batch while each send waits for Kafka.

- **Relay partitions.** Rows are split into `astra.outbox.partitions` relay partitions (default 8) by
  `hashtext(message_key)`. Each partition is held by one replica, through a session-level advisory lock on a
  connection that is not in a transaction: no row locks, no long transaction, no held MVCC horizon during a send.
- **Ordering.** Rows are sent in id order, one acknowledged send at a time. A key always maps to the same partition,
  so per-key order holds across replicas, while unrelated keys go through other partitions in parallel
  (`astra.outbox.parallelism`, default 2).
- **Publishing.** Each acknowledged row is marked published at once. Delivery stays at-least-once.
- **Failure isolation.**
  - A failed send stops its topic for the rest of the batch, so no later message of a key overtakes it.
  - The topic is paused for `astra.outbox.breaker-open` (30 s), a per-topic circuit breaker; other topics keep
    flowing.
  - A partition's batch stops at `astra.outbox.cycle-budget` (30 s), which bounds how long a lock is held.
- **Rolling upgrade.** Partition workers also hold the old relay lock in shared mode, so an old and a new replica
  never publish at the same time.
- **Constraint.** Change the partition count only with the relay stopped on every replica.
- **Metrics.**
  - `astra_outbox_relay_cycle_seconds`
  - `astra_outbox_relay_partition_hold_seconds`
  - `astra_outbox_send_seconds{topic,outcome}`
  - `astra_outbox_breaker_opened_total{topic}`
  - `astra_outbox_relay_skipped_total{reason}`
  - `GET /api/v1/ops/status` lists the paused topics.

### B. Inbox per consumer group, one listener wrapper (M-04)

- **Inbox key.** The inbox key is now `(source_system, message_id, consumer_group)`; there is a migration in every
  service.
  - Two groups of one service that read the same topic each process a message once. Example: the SAP adapter's
    postings and its webhooks. The webhook listeners had no inbox record before.
  - Rows from before this change have the group `''` and count as processed for every group until housekeeping
    purges them.
- **`InboxProcessing.once`** is the one way a listener applies a message:
  - in the message's tenant;
  - in one transaction with its inbox record;
  - with the consumer group taken from the listener thread.

  Every listener uses it.
- **`scripts/check-architecture.sh`** runs in CI and fails when:
  - a `@KafkaListener` does not use `InboxProcessing`;
  - code calls `InboxGuard` directly;
  - a read of the RLS-free platform tables (`outbox`, `dlq_replay`) has no `tenant_id` filter (M-05);
  - a state-changing endpoint has no `@PreAuthorize`.

### C. Dead-letter replay safety and a bounded listing (M-01, gap P0 "operations monitoring")

- **Replay refusals.**
  - Refused with `DLQ_ALREADY_PROCESSED` when the failing group processed the message since it was dead-lettered.
  - Refused with `DLQ_REPLAY_UNSAFE` when the message is older than the inbox retention (less one day). Its inbox
    record may be purged, so a replay could apply it twice. Only a forced replay with a reason passes; it is
    recorded in `dlq_replay.forced` and `force_reason`.
- **Listing.** The listing reads every dead-letter topic in one pass, under a 6 s budget, and never auto-creates a
  topic. It answers `complete=false` rather than hanging. Each message says `replay`: SAFE, REPLAYED,
  ALREADY_PROCESSED or TOO_OLD.
- **`GET /api/v1/ops/status`** returns the backlog, the oldest pending message, the paused topics and the time of the
  answer, without reading Kafka.
- **Operations page.**
  - Each service and each check loads on its own, with a timeout (health 5 s, status 8 s, dead letters 12 s).
  - Each check ends in a definite state: checking, ok, timed out or failed.
  - Each check shows its latency and when it was taken. The page refreshes every 30 s.
  - There is no indefinite spinner, and one slow service no longer blanks the others.
  - `api()` gains `timeoutMs`.

### D. Label checks when master data is down (M-03)

- **Client.** The label client reports `UNREACHABLE` instead of "never printed".
- **Site policy** (`/tasks/label-checks/policy`):

  | Mode | What RF does when master data cannot answer |
  | --- | --- |
  | FAIL_OPEN (default, as ADR-0025) | Accepts the scan and records a bypass: who, task, label and when, in its own transaction. Also counts `astra_label_check_bypassed_total{site,type,mode}` and writes `LABEL_CHECK_BYPASSED` to the task history. |
  | FAIL_CLOSED | Refuses the scan with `TSK_LABEL_CHECK_UNAVAILABLE` (503). |

- **Reconciliation.** Supervisors list open bypasses and close them with a note
  (`/tasks/label-checks/bypasses`).

### E. Strict transfers never strand a reservation (H-04)

Inventory reserves stock in its own transaction. Before this change, a refused strict transfer was released by a call
made just before the outbound rollback. If that call failed, or any later step did, the reservation stayed, held by
an order that does not exist. `StrictReservations` replaces this:

1. **Collect.** It records every allocation of the attempt on the transaction.
2. **Release on rollback.** On rollback it releases exactly those ids. Inventory's release gains `allocationIds`.
3. **Retry.** What cannot be released at once goes into `reservation_compensation`, and a retrier releases it.
4. **Unanswered allocations.** An allocation call that ended without an answer marks the attempt uncertain. The
   retrier then also releases by order reference, under the advisory lock that transfer numbering takes, and only
   while no order holds that number.

The advisory preflight stays: it gives the explanation ("held by …"). The allocation in inventory remains the
authority.

### F. Synchronous calls between services (H-03, M-02)

- **`ServiceClients`** builds every service-to-service client, seven in all.
  - It keeps the existing timeouts.
  - It is a bulkhead: at most `astra.downstream.max-concurrent` (32) calls are in flight per client. A further call
    waits 250 ms, then fails as `DownstreamBusyException`, instead of tying up request threads and database
    connections behind a slow service.
  - Metrics: `astra_downstream_call_seconds{client,method,outcome}` and `astra_downstream_in_flight{client}`.
- **Problem handler.** A downstream timeout or refusal (`ResourceAccessException`, including busy) is now 503
  `DOWNSTREAM_UNAVAILABLE` with `Retry-After`, not a bare 500. The RF client retries the same key, and the
  downstream idempotency record answers it.
- **Lock scope.** Lock scope inside the receiving and task transactions is unchanged. Narrowing it changes
  ADR-0005's contract; it waits for the latency data these metrics now provide.

### G. Security evidence (gap P0 "security and SoD")

- **Permission matrix.** `scripts/permission_matrix.py` generates `docs/security/permission-matrix.md` from every
  controller's mappings and roles: 228 endpoints, the state-changing endpoints per role, and every endpoint per
  service. CI fails when it is stale.
- **Concurrency test.** `TenancyAndConcurrencyIT` now covers 100 concurrent retries of one command with one key:
  exactly one operation, one ledger line and one event, and a changed body with that key is refused.

### H. Kafka ACLs and database backups on the VPS (H-02)

- **ACL spec.** `scripts/kafka_acls.py` derives each service account's ACLs from the code into
  `deploy/kafka/acls.txt`; CI checks that it is current. The rules:

  | Topics | ACLs |
  | --- | --- |
  | Outbox topics | WRITE |
  | Listener topics | READ, plus WRITE because a DLQ replay goes back to the original topic |
  | `<topic>.dlq` | READ, WRITE, CREATE, DESCRIBE |
  | Own consumer-group prefixes | READ |
  | Declared topics | DESCRIBE, CREATE, ALTER |

- **Broker.** The VPS broker runs `StandardAuthorizer`, deny by default. The super users are `broker` and the
  controller listener (`ANONYMOUS`), which is not reachable outside the broker's network.
- **Deploy.** `deploy/vps/kafka-acls.sh` applies the ACLs before the services start. Listeners retry after an
  authorization error.
- **Verified on a local SASL broker:**
  - allowed: a service's own topic and group;
  - denied: another service's topic, a topic the service only writes, and another service's group.
- **Backups.** `deploy/vps/pg-backup.sh` runs nightly from cron:
  - `pg_dumpall`, gzip, then AES-256 (PBKDF2) with the generated `BACKUP_PASSPHRASE`;
  - 14 days kept;
  - an off-host copy when `BACKUP_REMOTE` is set (rsync target or `rclone:` remote).
- **Restore drill.** `deploy/vps/pg-restore-drill.sh` runs weekly. It restores the newest backup into a throwaway
  container, checks every database, and appends the recovery point and recovery time to `backups/drills.log`. A
  failing drill exits non-zero, so cron mails root.
- **Passphrase.** Keep `BACKUP_PASSPHRASE` off the server as well: without it no backup can be restored.

## Not addressed here

These remain open:

- **Load and fault testing.**
  - Relay benchmarks at 5 ms to 10 s broker latency.
  - 1 s, 3 s and 5 s downstream delays.
  - A broker timeout after send but before `published_at`.
  - Two replicas against one database.

  The metrics above are what those tests measure.
- **Lock scope.** Narrowing the lock scope of the synchronous RF chains (F).
- **SAP.** End-to-end SAP contract tests against a real system. The simulated SAP and the smoke tests remain the
  evidence.
- **Product roadmap (P1/P2 of the gap analysis).** These are product decisions, not hardening:
  - MFA and conditional access (Keycloak configuration);
  - immutable before/after audit records;
  - master-data governance;
  - WES and automation orchestration;
  - engineered labor;
  - yard and dock;
  - omnichannel and VAS;
  - QA and CAPA;
  - analytics;
  - device certification;
  - AI;
  - digital twin.
- **Off-host backup target.** It needs a destination and credentials that only the operator can provide.
- **Local stack.** The Docker Compose stack (`deploy/docker-compose.yml`) keeps Kafka without SASL and without ACLs.

## Consequences

- **Migrations.**
  - Every service: inbox `consumer_group` and replay audit columns (`V*__messaging_hardening`).
  - Task V15: label-check policy and bypasses.
  - Outbound V14: `reservation_compensation`.
- **CI** runs three more checks: architecture rules, permission matrix and Kafka ACLs.
- **Deployment.**
  - The VPS deploy applies ACLs before starting the services.
  - It installs `/etc/cron.d/astrawms-backup`.
  - It generates `BACKUP_PASSPHRASE`.
  - The first deploy pulls the `apache/kafka` image for its CLI.
- **ACL trade-off.** A consumer can write to the topics it consumes, because DLQ replay needs it. A dedicated replay
  path would remove that.
- **Relay connections.** A relay partition holds a database connection while it publishes: up to `parallelism` per
  replica, outside any transaction.
