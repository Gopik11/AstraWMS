# AstraWMS

AstraWMS is an enterprise Warehouse Management System (WMS). It is the **warehouse execution system** that sits under an ERP **system of record** (SAP ECC / S/4HANA, Oracle E-Business Suite, Oracle Fusion Cloud SCM).

This repository contains:
- the **Scope & Solution Definition** document set, written to be used directly as the basis for an RFP response, an SRS and the target architecture design;
- the **platform code**: the platform library; the Master Data, Inventory, Inbound, Task and Outbound services; and the SAP adapter.

## Platform

| Module | Purpose |
|---|---|
| [`platform/astra-common`](platform/astra-common) | OAuth2 resource server (token validation, tenant from the token, role checks, approver proof, service-to-service tokens), Postgres RLS binding, RFC 9457 errors, transactional outbox/relay, inbox de-duplication, 16-char `WmsTxnId`, shared event contracts |
| [`services/master-data-service`](services/master-data-service) | Items (UoMs, GTINs, site control data), sites, zones, locations with zone inheritance and bulk generation; publishes `ItemUpserted` / `LocationUpserted` |
| [`services/inventory-service`](services/inventory-service) | Bin/LPN/lot inventory with an append-only ledger; receipts, moves (qty and whole LPN), adjustments, status changes; publishes `InventoryChanged` and ERP `GoodsMovement` (IF-INV-001) |
| [`services/inbound-service`](services/inbound-service) | Receipt expectations from the ERP with the IF-IB-001 change matrix and application acks; RF line and SSCC receiving with tolerance and lot rules; receipt close with short reasons; `ReceiptConfirmation` (IF-IB-002), ERP result tracking and repost |
| [`services/task-service`](services/task-service) | Directed putaway: tasks created when LPNs arrive at the dock; engine with temperature, hazmat, mixing and capacity rules, consolidate then nearest-empty; RF next / confirm (LPN and check-digit scan) / exception with re-planning |
| [`services/outbound-service`](services/outbound-service) | Outbound orders from the ERP (IF-OB-001) with acks; waveless release or waves (pool, plan preview, release); allocation and pick requests; re-allocation after short picks; ship (issue + `ShipmentConfirmation`, IF-OB-003); ERP result tracking and repost; cancellation with reverse picks of picked stock |
| [`adapters/sap-adapter`](adapters/sap-adapter) | DELVRY07 → `ReceiptExpectation`; confirmations → `BAPI_INB_DELIVERY_CONFIRM_DEC`; goods movements → `BAPI_GOODSMVT_CREATE`; IDoc status; `SapGateway` with a simulated SAP backend (fault injection, duplicate check) |
| [`platform/astra-test-support`](platform/astra-test-support) | Shared Testcontainers setup (Postgres as a non-owner role, Kafka) and a test token issuer |
| [`frontend`](frontend) | Web UI (React + TypeScript): RF screens, receipts, orders and waves, tasks, stock inquiry and adjustments, master data, ERP simulator |
| [`deploy`](deploy) | Docker Compose stack with Keycloak (`deploy/keycloak`, realm `astrawms`) and the nginx API gateway (`deploy/gateway`) |

Design decisions are recorded in [docs/architecture/adr](docs/architecture/adr/README.md).

**Build and test.** No local JDK is needed; Maven runs in Docker, and Testcontainers starts Postgres 16 and Kafka:

```bash
scripts/mvn-docker.sh -B verify
```

**Run locally and smoke test.** This starts Postgres, Kafka, Keycloak, the API gateway, the five services and the SAP adapter with its simulated SAP. Only the gateway (http://localhost:8080) and Keycloak (http://localhost:8180) are published. Each smoke test creates a fresh tenant with users in Keycloak, signs them in and calls the API through the gateway.
- `smoke-test.sh` covers master data, then Kafka, then inventory.
- `smoke-inbound.sh` covers the whole inbound flow: SAP DELVRY07, then receiving (SSCC and line), then close, then the goods receipt in simulated SAP. It also covers a period-closed failure with repost, an inventory adjustment posted as movement type 702, and an RF directed putaway of the received pallet into storage.

```bash
docker compose -f deploy/docker-compose.yml up -d --build
```

- `smoke-outbound.sh` covers the outbound flow: SAP outbound delivery, then FEFO allocation, then RF picks (one short, one with serials), then ship, then goods issue posted in simulated SAP (order CONFIRMED).
- `smoke-counts.sh` covers cycle counting: tolerance auto-adjustment, an independent recount, blind views for counters, and manager approval posted to simulated SAP.
- `smoke-replenishment.sh` covers min/max replenishment: an order picks a forward location below its minimum, then reserve stock is reserved and moved by an RF replenishment task.
- `smoke-packing.sh` covers packing and loading: ship refused while unpacked, an SSCC carton with a carrier label, cross-loading refused, and a trailer closed with a seal that posts the goods issue.
- `smoke-returns.sh` covers customer returns: a SAP returns delivery becomes an RMA, units graded A (restocked) and D (RTV, blocked), over-RMA refused, then close posts the 651 receipt and 453 restock in simulated SAP; plus a blind return.
- `smoke-waves.sh` covers wave release: orders pooled in WAVE mode, wave plan / create / release, a short pick re-allocated to another location, a picked order cancelled from SAP and returned to stock by an RF return task (cancel acknowledged only then), and the other order shipped complete.

```bash
scripts/smoke-test.sh && scripts/smoke-inbound.sh && scripts/smoke-outbound.sh && scripts/smoke-waves.sh && scripts/smoke-counts.sh && scripts/smoke-replenishment.sh && scripts/smoke-packing.sh && scripts/smoke-returns.sh
```

**Monitoring.** `docker compose -f deploy/docker-compose.yml --profile monitoring up -d` adds Prometheus at http://localhost:9090 with the alert rules in `deploy/monitoring/alerts.yml`. Dead letters, outbox backlog and service health are on the **Operations** page of the web UI (SOLUTION_ADMIN). See ADR-0018.

**Deploy to a VPS (test environment).** `scripts/deploy-vps.sh` builds the images locally, uploads them over SSH in checksummed chunks and starts [deploy/vps/docker-compose.yml](deploy/vps/docker-compose.yml) in `/opt/astrawms`.
- Every container has hard memory and CPU limits (about 3.3 GB in total), so the stack can share a host with other applications.
- Secrets are generated on the server (`/opt/astrawms/.env`, root-only).
- The application is served at https://145-223-90-247.sslip.io: the host nginx terminates TLS (Let's Encrypt) and proxies to the gateway on `127.0.0.1:8088`. The gateway also serves the `astrawms` realm's token endpoints. The Keycloak admin console (`127.0.0.1:8181`) and Prometheus (`127.0.0.1:9090`) are reachable only through an SSH tunnel.
- Kafka requires SASL authentication, with one generated account per service.
- The smoke tests run against it with `GATEWAY_URL`, `KC_URL` (the tunnel), `PROVISIONER_SECRET` and `SMOKE_SKIP_DB_CHECKS=1`.

**Web UI.** Open http://localhost:8080 and sign in with a Keycloak user (see below). `scripts/dev-user.sh` creates a local user with every role; its credentials go to the git-ignored `deploy/keycloak/.dev-user`. For UI development, run `npm run dev` in `frontend/`; it proxies the API to the local gateway and serves the app at http://localhost:5173.

**APIs.** All APIs go through the gateway at http://localhost:8080:

| Service | Base path |
|---|---|
| Master data | `/api/v1/items`, `/api/v1/sites/...` |
| Inventory | `/api/v1/sites/{siteId}/inventory/...` |
| Inbound | `/api/v1/sites/{siteId}/receipts/...` |
| Task | `/api/v1/sites/{siteId}/tasks/...` (`next`, `{id}/confirm`, `{id}/pick`, `{id}/return`, `{id}/exception`, `{id}/replan`) |
| Outbound | `/api/v1/sites/{siteId}/outbound/orders/...` (`{doc}/ship`, `{doc}/repost`), `/outbound/config` (release mode), `/outbound/waves` (`plan`, create, `{no}/release`) |
| SAP adapter | `/api/v1/sap/...` (IDoc port, site map), `/mock-sap/...` (simulated SAP) |
| Health | `/health/{service}`, e.g. `/health/inventory-service` |

**Security** ([ADR-0010](docs/architecture/adr/0010-token-based-identity-and-api-gateway.md)):
- Every request needs `Authorization: Bearer <token>` from the identity provider (locally the Keycloak realm `astrawms`).
- The tenant and user come from the token (`tenant_id` claim); a different `X-Tenant-Id` is rejected.
- Endpoints that change data require roles (§G.5.2), e.g. `RECEIVER` to receive, `SUPERVISOR` to ship.
- Roles are limited by scope claims ([ADR-0012](docs/architecture/adr/0012-attribute-scopes-on-roles.md)): `wms_sites`, `wms_owners` and `wms_zones` (`*` = all; missing = none), and `approval_limit` for approvers.
- Approvals send the approver's own token in `X-Approval-Token`.
- Commands also need `Idempotency-Key`.

To create a user locally, use the Keycloak admin console (admin / admin): add a user in realm `astrawms` with the attributes `tenant_id`, `wms_sites`, `wms_owners` and `wms_zones`, and realm roles. Or use `provision` from `scripts/lib/auth.sh`.

## Document Set

| # | Document | Contents |
|---|----------|----------|
| 00 | [Document Control & Conventions](docs/scope/00-document-control.md) | Versioning, requirement ID scheme, priority (MoSCoW), assumptions, out of scope |
| A | [Executive Summary](docs/scope/01-executive-summary.md) | Purpose, business value, ERP role, capability map |
| B1 | [Functional Scope: Inbound, Putaway & Storage](docs/scope/02-functional-inbound-storage.md) | §1 Inbound Logistics, §2 Putaway & Storage |
| B2 | [Functional Scope: Outbound Execution](docs/scope/03-functional-outbound.md) | §3 Outbound, §4 Picking, §5 Packing/Staging/Shipping |
| B3 | [Functional Scope: Inventory Control](docs/scope/04-functional-inventory.md) | §6 Inventory, §7 Replenishment, §8 Returns, §9 Cross-Docking |
| B4 | [Functional Scope: Specialised Operations](docs/scope/05-functional-specialised.md) | §10 VAS, §11 Kitting/Light Mfg, §12 Quality, §13 Multi-WH & 3PL, §14 Cold Chain & HazMat |
| C | [Advanced Features](docs/scope/06-advanced-features.md) | Slotting, LMS, interleaving, waves, cartonization, robotics, IoT, AI, digital twin, computer vision |
| D | [ERP Integration Scope (SAP & Oracle)](docs/scope/07-erp-integration.md) | Architecture, master/transaction flows, message catalogue, reconciliation, monitoring, security |
| E | [Non-Functional Requirements](docs/scope/08-non-functional-requirements.md) | Scalability, SLAs, HA/DR, security, extensibility, API governance, audit |
| F | [System Architecture](docs/scope/09-system-architecture.md) | Logical/physical architecture, services, data model, RF, automation orchestration, eventing |
| G | [UI/UX Scope](docs/scope/10-ui-ux.md) | Web modules, RF flows, voice/wearables, dashboards, RBAC |
| H | [Reporting & Analytics](docs/scope/11-reporting-analytics.md) | Dashboards, inventory/labor KPIs, management reports, data warehouse |
| I | [Implementation Roadmap](docs/scope/12-implementation-roadmap.md) | Phases, testing, training/OCM, go-live, hypercare, optimisation |
| Z | [Glossary](docs/scope/13-glossary.md) | Terms and acronyms |
| ISD | [Interface Specification Documents](docs/isd/README.md) | One ISD per ERP interface (17) plus common conventions: realisation per SAP/Oracle variant, canonical message, field mapping, rules, errors, test scenarios |
| RTM | [Requirements Traceability Matrix](docs/rtm/AstraWMS_RTM.xlsx) | All 334 requirement, exception and interface IDs with fit-gap and test tracking; regenerate with `python docs/rtm/build_rtm.py` (keeps team-entered fit-gap, test and defect data; `--fresh` starts blank) |

## Core Principle

> **ERP owns *what* and *how much*. AstraWMS owns *where*, *who*, *when* and *how*.**

The ERP is authoritative for material/item master, business partners, purchase and sales orders, deliveries, valuation, and financial stock. AstraWMS is authoritative for bin-level location, license plates (LPNs), handling units, task execution, labor, and physical confirmation. Stock quantity at plant/storage-location (SAP) or organization/subinventory (Oracle) level must reconcile to zero variance between the two systems.
