# IF-INV-003 — Stock Reconciliation Snapshot (Bidirectional)

| Attribute | Value |
|---|---|
| Interface ID | IF-INV-003 |
| Name | Stock Reconciliation Snapshot (Bidirectional) |
| Version / Status | 0.1 — Draft for design review |
| Direction | Bidirectional (ERP snapshot → WMS; WMS results to monitor) |
| Source → Target | ERP ↔ Adapter ↔ AstraWMS (Reconciliation service) |
| Pattern | Scheduled bulk extract + on-demand |
| Trigger | Daily per site at configured quiet time; on demand; before period close |
| Frequency | Daily + on demand |
| Design peak volume | Full snapshot: up to 2,000,000 stock keys per site |
| Latency SLA | ≤ 30 min from start to variance report |
| Priority | M |
| Canonical message | `StockSnapshot v1` |
| Business key (ordering) | siteId + snapshotId |
| Traced requirements | INV-EX-01, INT-011, INT-015, RPT-004, RPT-063, MWH-004 |
| Conventions | [ISD-00 Common Interface Conventions](00-common-conventions.md) apply unless stated otherwise |

---

## 1. Purpose & Scope

Compares ERP stock with AstraWMS stock at the level of site, bucket, item, batch, stock type and special stock/owner. It adjusts for in-flight messages and classifies every variance, so that the ERP and the WMS reconcile to zero unexplained variance, in particular before period close (§D.6.4).

**In scope**

- ERP stock extract for WMS-managed buckets
- WMS snapshot at the same point in time
- In-flight compensation and variance classification
- Document reconciliation (open ERP deliveries vs WMS status)

**Out of scope**

- Automatic correction postings: corrections are made through IF-INV-001 or ERP postings after investigation and approval

## 2. Process Flow

```mermaid
sequenceDiagram
  participant SCH as Scheduler
  participant REC as AstraWMS Reconciliation
  participant INV as AstraWMS Inventory
  participant AD as Adapter
  participant ERP
  SCH->>REC: Start snapshot (site, T)
  REC->>INV: Snapshot at T (consistent read)
  REC->>AD: Request ERP stock (site buckets)
  AD->>ERP: API / RFC / SQL / BIP extract
  ERP-->>AD: Stock rows + extraction time T'
  AD-->>REC: StockSnapshot (ERP)
  REC->>REC: Compensate in-flight (WMS unposted before T; ERP postings between T and T')
  REC->>REC: Classify: TIMING / FAILED_MESSAGE / UNEXPLAINED
  REC-->>SCH: Variance report + workbench items
```

## 3. Technical Realisation

| ERP Variant | Mechanism | Object / Endpoint | Trigger & Transport | ERP Authorisation (technical user) |
|---|---|---|---|---|
| SAP S/4HANA | OData API_MATERIAL_STOCK_SRV | A_MatlStkInAcctMod (Material, Plant, StorageLocation, Batch, InventoryStockType, InventorySpecialStockType, Supplier/Customer, MatlWrhsStkQtyInMatlBaseUnit) *(confirm)* | Paged read ($top 5,000) filtered by plant and WMS SLocs | S_SERVICE, M_MATE_WRK display |
| SAP ECC | Custom RFC (read-only) | MARD (LABST, INSME, SPEME), MCHB (CLABS, CINSM, CSPEM), MKOL/MSKU for special stock | RFC with package size 10,000 | S_RFC for custom FM; S_TABU_DIS not required (FM reads) |
| Oracle EBS | SQL extract via OIC DB adapter | MTL_ONHAND_QUANTITIES_DETAIL grouped by organization, subinventory, item, lot, (owning party for consignment) | Read in chunks by item range | Read-only grants |
| Oracle Fusion | BIP report (scheduled) or on-hand REST *(confirm)* | On-hand balances by org/subinventory/item/lot | BIP output CSV to UCM → OIC | BI report access |

## 4. Canonical Message — `StockSnapshot v1`

Card = cardinality; Req: M mandatory, C conditional, O optional. **PII** fields follow ISD-00 §7.

| Field | Type | Card | Req | Description / Validation |
|---|---|---|---|---|
| snapshot.snapshotId | UUID | 1 | M |  |
| snapshot.siteId | string(20) | 1 | M |  |
| snapshot.source | code | 1 | M | ERP, WMS |
| snapshot.asOfUtc | date-time | 1 | M | T (WMS) or T' (ERP extraction time) |
| snapshot.rows[] | object | 0..n | M |  |
| rows[].bucket / itemNo / lotNo | string | 1 | M | lotNo empty for non-lot items |
| rows[].stockType | code | 1 | M | AVAILABLE, QI, BLOCKED, RETURNS, IN_TRANSIT |
| rows[].ownerId | string(20) | 1 | M | Special stock / consignment owner or default |
| rows[].qtyBaseUom | decimal(15,3) | 1 | M |  |
| result.variances[] | object | 0..n | M | {key, erpQty, wmsQty, inFlightQty, variance, class, linkedMessageIds[]} |

## 5. Field Mapping

| Canonical | SAP ECC / S/4HANA | Oracle EBS | Oracle Fusion | Notes |
|---|---|---|---|---|
| rows[].bucket | StorageLocation / MARD-LGORT | SUBINVENTORY_CODE | Subinventory |  |
| rows[].itemNo / lotNo | Material, Batch / MATNR, CHARG | INVENTORY_ITEM_ID, LOT_NUMBER | Item, Lot |  |
| rows[].stockType | InventoryStockType (01 unrestricted, 02 QI, 07 blocked *(confirm codes)*) / LABST, INSME, SPEME | Subinventory status map / material status | Same | WMS ALLOCATED and IN_TRANSIT (internal) roll up to AVAILABLE |
| rows[].ownerId | InventorySpecialStockType K + Supplier (consignment) *(confirm)* | OWNING_ORGANIZATION / PLANNING party (consigned) | Owning party |  |
| rows[].qtyBaseUom | MatlWrhsStkQtyInMatlBaseUnit / LABST etc. | SUM(PRIMARY_TRANSACTION_QUANTITY) | Primary quantity |  |
| In-flight ERP postings T..T' | MATDOC / MKPF CPUDT+CPUTM > T | MTL_MATERIAL_TRANSACTIONS.CREATION_DATE > T | Transaction date > T | For compensation |

## 6. Processing Rules

| ID | Rule |
|---|---|
| INV003-R01 | WMS snapshot is a consistent read at T. The ERP extract records its own extraction time T'. The run is only valid if T and T' are no more than 15 min apart. |
| INV003-R02 | In-flight compensation: WMS→ERP messages created before T and not POSTED at T' are added to the ERP side. ERP postings between T and T' affecting WMS buckets are reversed out of the ERP side. |
| INV003-R03 | Classification: TIMING (explained by compensation), FAILED_MESSAGE (linked to an error-queue item), UNEXPLAINED (workbench task to Inventory Control). |
| INV003-R04 | Unexplained variances are never auto-corrected. Resolution is either a WMS count task (physical check) or an approved ERP correction with dual approval. |
| INV003-R05 | The KPI ERP–WMS reconciliation accuracy (RPT-004) and the period-end report (RPT-063) are computed from each run. |
| INV003-R06 | Document reconciliation: ERP deliveries open > 48 h vs WMS SHIPPED, and inbound deliveries open vs WMS CLOSED, are listed as missed confirmations. |

## 7. Error Handling

Error classes and default retry behaviour: ISD-00 §6.

| Code | Condition | Class | Handling |
|---|---|---|---|
| INV003-E01 | ERP extract timeout / partial | Transient technical | Retry by chunk; run marked incomplete if not recovered |
| INV003-E02 | T and T' more than 15 min apart | Permanent technical | Run invalid; rerun at next quiet window |
| INV003-E03 | Unmapped bucket or stock type in ERP extract | Permanent technical | Configuration fix |

## 8. Test Scenarios

| ID | Cat | Scenario | Expected Result |
|---|---|---|---|
| TS-INV003-01 | POS | Clean site: no in-flight | 0 variances |
| TS-INV003-02 | VAR | GR confirmation in store-and-forward at T | Variance classified TIMING |
| TS-INV003-03 | VAR | Adjustment failed in ERP (error queue) | FAILED_MESSAGE with linked message |
| TS-INV003-04 | NEG | Manual ERP posting at WMS SLoc (unwhitelisted) | UNEXPLAINED; workbench task |
| TS-INV003-05 | VOL | 2,000,000 keys | ≤ 30 min |

## 9. Open Points

| # | Item to Confirm | Owner |
|---|---|---|
| 1 | S/4 stock API entity/field names and stock type codes | SAP integration lead |
| 2 | Consignment / special stock handling for 3PL owners | SAP / Oracle functional leads |

## Change Log

| Version | Date | Change |
|---|---|---|
| 0.1 | 2026-09-30 | Initial draft generated from scope §D |
