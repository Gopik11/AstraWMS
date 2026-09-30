# AstraWMS Interface Specification Documents (ISDs)

One ISD per interface ID in the [interface catalogue §D.4.2](../scope/07-erp-integration.md#d42-interface-catalogue-summary). Shared rules (envelope, data types, UoM, acknowledgements, idempotency, retry, security, monitoring, test categories) are in [ISD-00 Common Interface Conventions](00-common-conventions.md) and are not repeated in the individual ISDs.

Status of all ISDs: **0.1 Draft**. SAP/Oracle field and object names marked *(confirm)* must be verified against the customer's ERP release (SE37/SE11/WE60 for SAP; eTRM / REST API catalogue for Oracle) before sign-off at the Blueprint gate.

| Interface | Name | Direction | Canonical Message | Design Peak | Latency SLA |
|---|---|---|---|---|---|
| [IF-MD-001](IF-MD-001.md) | Item / Material Master (ERP → WMS) | Inbound to AstraWMS | ItemMaster v2 | 50,000 item-site changes/day; initial load up to 500,000 items | ≤ 5 min from ERP change to item usable in WMS |
| [IF-MD-002](IF-MD-002.md) | Customer / Ship-to Master (ERP → WMS) | Inbound to AstraWMS | PartnerMaster v1 (role CUSTOMER / SHIP_TO) | 10,000 changes/day; initial load up to 1,000,000 ship-to parties | ≤ 15 min |
| [IF-MD-003](IF-MD-003.md) | Vendor / Supplier Master (ERP → WMS) | Inbound to AstraWMS | PartnerMaster v1 (role VENDOR) | 2,000 changes/day | ≤ 15 min |
| [IF-MD-004](IF-MD-004.md) | Batch / Lot Master and Status (ERP → WMS) | Inbound to AstraWMS | LotMaster v1 | 20,000 changes/day | Status change ≤ 60 s (QM-004); other attributes ≤ 5 min |
| [IF-IB-001](IF-IB-001.md) | Inbound Delivery / ASN (ERP → WMS) | Inbound to AstraWMS | ReceiptExpectation v3 | 5,000 documents/day (peak 1,500/h) | ≤ 2 min |
| [IF-IB-002](IF-IB-002.md) | Receipt Confirmation / Goods Receipt (WMS → ERP) | Outbound from AstraWMS | ReceiptConfirmation v3 | 5,000 confirmations/day (peak 1,500/h) | ≤ 2 min to ERP document number returned |
| [IF-OB-001](IF-OB-001.md) | Outbound Delivery / Shipment Request (ERP → WMS) | Inbound to AstraWMS | OutboundOrder v4 | 200,000 documents/day (peak 25,000/h; bursts 150/s) | ≤ 1 min to order in WMS pool |
| [IF-OB-002](IF-OB-002.md) | Outbound Change / Cancel Response (WMS → ERP) | Outbound from AstraWMS | OrderChangeResponse v1 | 5,000 responses/day | ≤ 1 min from request receipt (or from supervisor decision when manual) |
| [IF-OB-003](IF-OB-003.md) | Shipment Confirmation and Goods Issue (WMS → ERP) | Outbound from AstraWMS | ShipmentConfirmation v4 | 200,000 deliveries/day (peak 30,000/h at carrier cut-offs) | ≤ 2 min to ERP goods issue document |
| [IF-OB-004](IF-OB-004.md) | Outbound Delivery Split (WMS → ERP) | Outbound from AstraWMS | DeliverySplitRequest v1 | 5,000 splits/day | ≤ 2 min |
| [IF-INV-001](IF-INV-001.md) | WMS-Initiated Goods Movements (WMS → ERP) | Outbound from AstraWMS | GoodsMovement v2 | 30,000 movements/day | ≤ 2 min |
| [IF-INV-002](IF-INV-002.md) | ERP-Originated Stock Status Changes (ERP → WMS) | Inbound to AstraWMS | ErpStockChange v1 | 2,000 notifications/day | ≤ 60 s |
| [IF-INV-003](IF-INV-003.md) | Stock Reconciliation Snapshot (Bidirectional) | Bidirectional (ERP snapshot → WMS; WMS results to monitor) | StockSnapshot v1 | Full snapshot: up to 2,000,000 stock keys per site | ≤ 30 min from start to variance report |
| [IF-INV-004](IF-INV-004.md) | Physical Inventory Documents (WMS → ERP) | Outbound from AstraWMS | PhysicalInventoryResult v1 | 10,000 count items/day; wall-to-wall up to 150,000 items | ≤ 5 min per count batch |
| [IF-RET-001](IF-RET-001.md) | Returns Expectation / RMA (ERP → WMS) | Inbound to AstraWMS | ReturnExpectation v1 | 10,000 RMA lines/day | ≤ 5 min |
| [IF-RET-002](IF-RET-002.md) | Returns Receipt and Disposition (WMS → ERP) | Outbound from AstraWMS | ReturnConfirmation v1 | 10,000 lines/day | ≤ 5 min |
| [IF-KIT-001](IF-KIT-001.md) | Kitting Consumption and Production (WMS → ERP) | Outbound from AstraWMS | KitCompletion v1 | 5,000 completions/day | ≤ 5 min |

## Traceability

Each ISD is the *Design Reference* for its IF-* row in the [RTM](../rtm/AstraWMS_RTM.xlsx). Test scenarios `TS-<code>-NN` in each ISD are the test cases for that row and for the requirements listed in its header.
