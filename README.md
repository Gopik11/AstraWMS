# AstraWMS

AstraWMS is an enterprise Warehouse Management System (WMS). It is the **warehouse execution system** that sits under an ERP **system of record** (SAP ECC / S/4HANA, Oracle E-Business Suite, Oracle Fusion Cloud SCM).

This repository contains:
- the **Scope & Solution Definition** document set, written to be used directly as the basis for an RFP response, an SRS and the target architecture design;
- the **platform code**: the platform library; the Master Data, Inventory, Inbound and Task services; and the SAP adapter.

## Platform

| Module | Purpose |
|---|---|
| [`platform/astra-common`](platform/astra-common) | Tenancy + Postgres RLS binding, RFC 9457 errors, transactional outbox/relay, inbox de-duplication, 16-char `WmsTxnId`, shared event contracts |
| [`services/master-data-service`](services/master-data-service) | Items (UoMs, GTINs, site control data), sites, zones, locations with zone inheritance and bulk generation; publishes `ItemUpserted` / `LocationUpserted` |
| [`services/inventory-service`](services/inventory-service) | Bin/LPN/lot inventory with an append-only ledger; receipts, moves (qty and whole LPN), adjustments, status changes; publishes `InventoryChanged` and ERP `GoodsMovement` (IF-INV-001) |
| [`services/inbound-service`](services/inbound-service) | Receipt expectations from the ERP with the IF-IB-001 change matrix and application acks; RF line and SSCC receiving with tolerance and lot rules; receipt close with short reasons; `ReceiptConfirmation` (IF-IB-002), ERP result tracking and repost |
| [`services/task-service`](services/task-service) | Directed putaway: tasks created when LPNs arrive at the dock; engine with temperature, hazmat, mixing and capacity rules, consolidate then nearest-empty; RF next / confirm (LPN and check-digit scan) / exception with re-planning |
| [`adapters/sap-adapter`](adapters/sap-adapter) | DELVRY07 → `ReceiptExpectation`; confirmations → `BAPI_INB_DELIVERY_CONFIRM_DEC`; goods movements → `BAPI_GOODSMVT_CREATE`; IDoc status; `SapGateway` with a simulated SAP backend (fault injection, duplicate check) |
| [`platform/astra-test-support`](platform/astra-test-support) | Shared Testcontainers setup (Postgres as a non-owner role, Kafka) |

Design decisions are recorded in [docs/architecture/adr](docs/architecture/adr/README.md).

**Build and test.** No local JDK is needed; Maven runs in Docker, and Testcontainers starts Postgres 16 and Kafka:

```bash
scripts/mvn-docker.sh -B verify
```

**Run locally and smoke test.** This starts Postgres, Kafka, the four services and the SAP adapter with its simulated SAP.
- `smoke-test.sh` covers master data, then Kafka, then inventory.
- `smoke-inbound.sh` covers the whole inbound flow: SAP DELVRY07, then receiving (SSCC and line), then close, then the goods receipt in simulated SAP. It also covers a period-closed failure with repost, an inventory adjustment posted as movement type 702, and an RF directed putaway of the received pallet into storage.

```bash
docker compose -f deploy/docker-compose.yml up -d --build
```

```bash
scripts/smoke-test.sh && scripts/smoke-inbound.sh
```

**APIs.**

| Service | Port | Base path |
|---|---|---|
| Master data | 8081 | `/api/v1/items`, `/api/v1/sites/...` |
| Inventory | 8082 | `/api/v1/sites/{siteId}/inventory/...` |
| Inbound | 8083 | `/api/v1/sites/{siteId}/receipts/...` |
| Task | 8084 | `/api/v1/sites/{siteId}/tasks/...` (`next`, `{id}/confirm`, `{id}/exception`, `{id}/replan`) |
| SAP adapter | 8090 | `/api/v1/sap/...` (IDoc port, site map), `/mock-sap/...` (simulated SAP) |

Every request needs an `X-Tenant-Id` header; in deployed environments the API gateway sets it from the token. Commands (inventory and RF receiving) also need `Idempotency-Key`.

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
