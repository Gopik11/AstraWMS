# AstraWMS

AstraWMS is an enterprise Warehouse Management System (WMS). It is the **warehouse execution system** that sits under an ERP **system of record** (SAP ECC / S/4HANA, Oracle E-Business Suite, Oracle Fusion Cloud SCM).

This repository currently contains the **Scope & Solution Definition** document set. It is written to be used directly as the basis for an RFP response, a Software Requirements Specification (SRS), and the target architecture design.

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
