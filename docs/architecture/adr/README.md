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
| [0008](0008-directed-putaway-and-task-model.md) | Task service with directed putaway from event-carried projections; reservations, RF check-digit confirmation, re-planning |
| [0009](0009-allocation-in-inventory-and-outbound-flow.md) | Inventory owns allocation (FEFO/FIFO, pick to staging still allocated, issue); outbound orchestrates via pick events; shortfalls reported to the ERP |
| [0010](0010-token-based-identity-and-api-gateway.md) | OAuth2 resource servers: tenant and user from the token, role checks on every write, approver proof, service-account tokens, Keycloak locally, nginx API gateway |
| [0011](0011-waves-reallocation-and-reverse-picks.md) | Per-site wave or waveless release with wave plan/create/release; re-allocation after short picks; reverse picks for cancelled picked orders, ERP ack only when stock is back |
| [0012](0012-attribute-scopes-on-roles.md) | Scope claims on roles: sites, owners (3PL clients), zones and approval value limits; deny when missing; item standard cost |
| [0013](0013-web-ui.md) | React single-page web UI (RF, operations, inventory, admin, ERP simulator) with PKCE sign-in, served by the gateway image |
| [0014](0014-cycle-counting.md) | Cycle counts owned by inventory, executed as blind RF count tasks; tolerance auto-adjust, independent recounts, approval with SoD and value limits; counts after short picks |
| [0015](0015-min-max-replenishment.md) | Min/max replenishment: inventory triggers on stock leaving forward locations, reserves reserve stock in rotation order, RF REPLEN tasks |
| [0016](0016-packing-and-loading.md) | Packing records with GS1 SSCC cartons and carrier labels (simulated carrier); optional pack-before-ship per site; loads with cross-load check, closed with seal and BOL to ship |
| [0017](0017-customer-returns.md) | Customer returns in the inbound service: RMAs from SAP returns deliveries and blind returns; units graded A–E with a suggested disposition; close posts 651 receipt then 453 restock under two transaction IDs |
| [0018](0018-production-hardening.md) | Production hardening: HTTPS via host nginx and Let's Encrypt (astrawms.cloud), Kafka SASL/PLAIN with one account per service, per-tenant dead-letter listing and group-targeted replay, Prometheus metrics and alert rules, Operations page |
| [0019](0019-flow-fixes-receiving-putaway-recovery.md) | RF receiving tasks for ASNs and RMAs; zone-aware putaway (never outbound staging, pick face then reserve, QC for blocked stock); allocation policy (face, full LPN, FEFO/FIFO); demand replenishment; backorder recovery and Reallocate shorts; returns putaway; role-based RF work |
| [0020](0020-pick-verification-short-picks-dock-sweep-policy.md) | RF pick verification by item or GTIN; short-pick reason and decision (reallocate, backorder, ship short) with close shorts; dock sweep to putaway; explicit per-site allocation policy; LPN search; visible RF finish actions |
| [0021](0021-expert-review-gaps.md) | Explainable shorts, overrides and recoveries; control-tower ages; slotting with velocity and reslot moves; carrier cutoffs, wave hold, release by cutoff, ship complete, lot affinity, owner policies; labor standards and board with skill/equipment routing; yard and dock appointments; 3PL billing from the ledger; automation task API; RF-only receiving |
| [0022](0022-tender-gaps-material-issue-matmas-labels-physical-inventory-gs1.md) | Controlled material issue to cost centres, WBS elements and orders (SAP 201/221/261, returns 202/222/262); SAP MATMAS05 into the item master; ZPL bin, item and LPN labels with site printers; full physical inventory with freeze and one posting; GS1-128 scans on RF |
| [0023](0023-wms-transfers-offline-rf-passkeys.md) | Transfers between sites started in the WMS (TRANSFER orders, the receiving site's expected receipt, SAP 303/305); offline RF work (service worker, downloaded task batch, ordered command queue replayed with idempotency keys, needs-attention review); passkey sign-in with device biometrics |

New ADRs use the next number. The files are immutable once accepted: to change a decision, write a new ADR that supersedes the old one.
