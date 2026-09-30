# 00 — Document Control & Conventions

## 0.1 Document Metadata

| Attribute | Value |
|---|---|
| Product | AstraWMS |
| Document | Scope & Solution Definition |
| Version | 1.0 (Baseline for RFP / SRS) |
| Date | 2026-09-30 |
| Status | Draft for review |
| Classification | Confidential |

## 0.2 Intended Audience

| Audience | Primary Use |
|---|---|
| Executive sponsors | §A Executive Summary, §I Roadmap |
| Process owners (Inbound, Outbound, Inventory, Quality) | §B Functional Scope |
| Solution / enterprise architects | §D, §E, §F |
| ERP integration team (SAP / Oracle) | §D ERP Integration |
| Vendors responding to RFP | Entire set; requirement IDs are referenced in the response matrix |
| QA / test leads | Requirement IDs, exception flows, §I.2 Testing |

## 0.3 Requirement Identifier Scheme

Every requirement that can be tested carries an ID in the format `<AREA>-<NNN>`.

| Prefix | Area | Prefix | Area |
|---|---|---|---|
| INB | Inbound | QM | Quality Management |
| PUT | Putaway & Storage | MWH | Multi-warehouse / 3PL |
| OUT | Outbound order management | CCH | Cold chain & HazMat |
| PCK | Picking | ADV | Advanced features |
| SHP | Packing, staging, shipping | INT | ERP integration |
| INV | Inventory management | NFR | Non-functional |
| RPL | Replenishment | ARC | Architecture |
| RET | Returns | UX | UI/UX |
| XDK | Cross-docking | RPT | Reporting |
| VAS | Value-added services | IMP | Implementation |
| KIT | Kitting / light manufacturing | | |

## 0.4 Priority Classification (MoSCoW)

| Code | Meaning | RFP Treatment |
|---|---|---|
| **M** | Must have: go-live blocker | Vendor must be compliant out of the box or by configuration |
| **S** | Should have: high value, a workaround exists for go-live | Configuration or low-effort extension acceptable |
| **C** | Could have: differentiator | Roadmap commitment acceptable |
| **W** | Won't have in this release | Documented for future phases |

Requirement tables in this document use the column `Pri` for this code.

## 0.5 Terminology Mapping (SAP ↔ Oracle ↔ AstraWMS)

| AstraWMS Concept | SAP ECC / S/4HANA | Oracle EBS | Oracle Fusion Cloud SCM |
|---|---|---|---|
| Company / Legal entity | Company Code | Operating Unit / Legal Entity | Business Unit / Legal Entity |
| Site | Plant | Inventory Organization | Inventory Organization |
| Stock owner partition | Storage Location | Subinventory | Subinventory |
| Warehouse (physical) | Warehouse Number (LE-WM / EWM) | Organization (WMS-enabled) | Organization |
| Item | Material (MARA/MARC/MARD) | Item (MTL_SYSTEM_ITEMS_B) | Item (EGP_SYSTEM_ITEMS_B) |
| Lot | Batch (MCH1/MCHA) | Lot (MTL_LOT_NUMBERS) | Lot |
| Serial | Serial Number (EQUI/OBJK) | Serial (MTL_SERIAL_NUMBERS) | Serial |
| Inbound expectation | Inbound Delivery (LIKP/LIPS, VBTYP 7) | ASN / Receipt Routing | ASN / Expected Receipt |
| Outbound demand | Outbound Delivery (VBTYP J) | Delivery (WSH_NEW_DELIVERIES) / Shipment Request | Shipment / Shipment Request |
| Stock status | Stock type: unrestricted / QI / blocked | Material status / onhand status | Material status |
| Goods movement | Material Document (MKPF/MSEG, MATDOC) | Material Transaction (MTL_MATERIAL_TRANSACTIONS) | Inventory Transaction |
| Handling unit | HU (VEKP/VEPO) | LPN (WMS_LICENSE_PLATE_NUMBERS) | Packing Unit / LPN |

## 0.6 Assumptions

1. The ERP remains the **system of record** for master data, orders, financial inventory valuation, and costing. AstraWMS never posts financial values.
2. SAP deployments run the **decentralised WMS interface** pattern: stock at an ERP storage location that is assigned to an external warehouse; deliveries are distributed to AstraWMS; confirmations are posted back. This applies to ECC and S/4HANA with the delivery-based decentralised interface. Where S/4HANA embedded EWM is already in use for a site, AstraWMS does not replace it within that site.
3. Oracle EBS deployments use the **Third-Party Warehousing / XML Gateway** pattern (Shipment Request / Shipment Advice) together with Open Interface tables for receiving and inventory transactions. Oracle Fusion deployments use REST resources and B2B messaging. Exact resource versions (for example `11.13.18.05`) are confirmed per customer release during design.
4. All sites have Wi-Fi coverage that is adequate for RF devices (≥ -67 dBm RSSI at 95% of the pick face). Offline mode is designed for coverage gaps, not for full outages.
5. GS1 standards (GTIN, SSCC, GLN, GS1-128, GS1 DataMatrix) are the default for identification and labelling.
6. Carrier integration uses a multi-carrier shipping (MCS) platform or direct carrier APIs; AstraWMS natively manufactures labels only for LTL/FTL bills of lading and SSCC pallet labels.

## 0.7 Out of Scope (Release 1)

| Item | Rationale |
|---|---|
| Transportation Management (load building across sites, freight tendering, freight audit) | Owned by the TMS; AstraWMS provides dock scheduling and site-level load planning only |
| Yard Management beyond dock/door and trailer check-in/out | Full YMS (yard jockey tasks, trailer pools) is a Phase 3 option (`C`) |
| Financial postings, costing, valuation, inter-company billing | ERP |
| Production planning / MRP | ERP / MES; AstraWMS supports only light assembly (§11) |
| Procurement and sales order entry | ERP / OMS |
| Customs brokerage filing | Global trade system; AstraWMS supplies the data |
