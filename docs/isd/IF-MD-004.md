# IF-MD-004 — Batch / Lot Master and Status (ERP → WMS)

| Attribute | Value |
|---|---|
| Interface ID | IF-MD-004 |
| Name | Batch / Lot Master and Status (ERP → WMS) |
| Version / Status | 0.1 — Draft for design review |
| Direction | Inbound to AstraWMS |
| Source → Target | ERP → Middleware → AstraWMS (Master Data + Inventory services) |
| Pattern | Asynchronous publish (change-driven); status changes prioritised |
| Trigger | Batch/lot create or change, including restriction status and expiry changes |
| Frequency | Real-time |
| Design peak volume | 20,000 changes/day |
| Latency SLA | Status change ≤ 60 s (QM-004); other attributes ≤ 5 min |
| Priority | M |
| Canonical message | `LotMaster v1` |
| Business key (ordering) | ownerId + itemNo + lotNo |
| Traced requirements | INT-001, QM-004, INV-007, INB-005, INB-EX-08, QM-EX-01 |
| Conventions | [ISD-00 Common Interface Conventions](00-common-conventions.md) apply unless stated otherwise |

---

## 1. Purpose & Scope

Keeps lot attributes (expiry, manufacturing date, vendor lot, country of origin, grade, classification characteristics) and the ERP batch *restriction* status aligned with AstraWMS. A restriction set in the ERP (for example by a QM usage decision or a manual batch block) must be enforced on the warehouse floor within 60 seconds.

**In scope**

- Batch/lot create and change
- Restricted/unrestricted status
- Expiry / retest date changes
- Selected classification characteristics (configurable list)

**Out of scope**

- Batch creation *by* WMS at receipt: handled by the receipt confirmation (IF-IB-002), which creates the batch in ERP
- Stock quantity changes: IF-INV-002

## 2. Process Flow

```mermaid
sequenceDiagram
  participant ERP
  participant AD as Adapter
  participant MD as Master Data
  participant INV as Inventory
  ERP->>AD: BATMAS / CLFMAS / batch event / lot change
  AD->>MD: LotMaster.upsert (key=owner+item+lot)
  MD->>INV: LotStatusChanged (if restriction/expiry changed)
  INV->>INV: Place/release hold ERP-BATCH; re-evaluate allocations (FEFO/expiry)
  MD-->>AD: Applied
  AD-->>ERP: Application ACK
```

## 3. Technical Realisation

| ERP Variant | Mechanism | Object / Endpoint | Trigger & Transport | ERP Authorisation (technical user) |
|---|---|---|---|---|
| SAP ECC | ALE IDoc BATMAS03 + CLFMAS02 (classification) | BATMAS: batch attributes incl. expiry, production date, vendor batch, restriction flag; CLFMAS: characteristic values (class type 023) | Change pointers with a dedicated fast BD21 job every 1 min for message type BATMAS (status latency) | Job user only |
| SAP S/4HANA | OData API_BATCH_SRV (read) on batch event, or BATMAS | A_Batch (ShelfLifeExpirationDate, ManufactureDate, MatlBatchIsInRstrcdUseStock, BatchBySupplier, Supplier, CountryOfOrigin *(confirm fields)*), A_BatchCharc* | Batch changed business event *(confirm name)* → read-back | S_SERVICE, M_MATE_CHG display |
| Oracle EBS | Polling extract (30 s) via OIC | MTL_LOT_NUMBERS (+ MTL_MATERIAL_STATUSES_VL) | Poll LAST_UPDATE_DATE every 30 s for status; 5 min for other attributes | Read-only grants |
| Oracle Fusion | REST lot resource (read) polling / event | Inventory lots resource *(confirm name)* | Poll every 30 s (status) | Lot read privilege *(confirm)* |

## 4. Canonical Message — `LotMaster v1`

Card = cardinality; Req: M mandatory, C conditional, O optional. **PII** fields follow ISD-00 §7.

| Field | Type | Card | Req | Description / Validation |
|---|---|---|---|---|
| header.ownerId | string(20) | 1 | M | Owner |
| lot.itemNo | string(40) | 1 | M | Item (must exist, else parked) |
| lot.siteId | string(20) | 0..1 | C | Mandatory when batch is plant-level (SAP batch level = plant) or Oracle org-level lot |
| lot.lotNo | string(40) | 1 | M | ERP batch/lot number |
| lot.vendorLotNo | string(40) | 0..1 | O | Supplier batch |
| lot.vendorId | string(40) | 0..1 | O | Supplier |
| lot.manufacturingDate | date | 0..1 | O |  |
| lot.expiryDate | date | 0..1 | C | Mandatory for shelf-life items |
| lot.retestDate | date | 0..1 | O | Next inspection / retest date |
| lot.restricted | boolean | 1 | M | ERP restriction (restricted-use stock / lot on hold status) |
| lot.statusCode | string(20) | 0..1 | O | Oracle material status code (informational) |
| lot.countryOfOrigin | string(2) | 0..1 | O | ISO 3166-1 alpha-2 |
| lot.grade | string(20) | 0..1 | O | Grade / quality class |
| lot.characteristics[] | object | 0..n | O | {name, value, uom}; only characteristics in the configured whitelist |
| lot.deleted | boolean | 1 | M | Deletion flag |
| lot.sourceChangedAtUtc | date-time | 1 | M | Stale-message rule |

## 5. Field Mapping

| Canonical | SAP ECC / S/4HANA | Oracle EBS | Oracle Fusion | Notes |
|---|---|---|---|---|
| lot.itemNo / lotNo / siteId | MATNR / CHARG / WERKS (MCH1 or MCHA level) | INVENTORY_ITEM_ID→item / LOT_NUMBER / ORGANIZATION_ID→org | ItemNumber / LotNumber / OrganizationCode |  |
| lot.vendorLotNo | MCH1-LICHA | SUPPLIER_LOT_NUMBER | SupplierLotNumber |  |
| lot.vendorId | MCH1-LIFNR | n/a (from receipt) | n/a |  |
| lot.manufacturingDate | MCH1-HSDAT | ORIGINATION_DATE | OriginationDate |  |
| lot.expiryDate | MCH1-VFDAT | EXPIRATION_DATE | LotExpirationDate |  |
| lot.retestDate | MCH1-QNDAT | RETEST_DATE | RetestDate |  |
| lot.restricted | MCH1-ZUSTD = 'X' | STATUS_ID → status with reservable/transactable restrictions | StatusCode → material status map | Status map per customer (§6 MD004-R02) |
| lot.countryOfOrigin | MCH1-HERKL | Lot attribute (C_ATTRIBUTE* ) *(confirm)* | Lot DFF *(confirm)* |  |
| lot.grade | Characteristic (CLFMAS) *(confirm name)* | GRADE_CODE | GradeCode |  |
| lot.characteristics[] | CLFMAS E1AUSPM (ATINN/ATWRT) | Lot attributes / DFF | Lot DFF / EFF | Whitelist |
| lot.deleted | MCH1-LVORM | Disabled / expired | n/a |  |

## 6. Processing Rules

| ID | Rule |
|---|---|
| MD004-R01 | Upsert by (ownerId, itemNo, lotNo[, siteId]). A lot for an unknown item is parked (dependency). |
| MD004-R02 | Status mapping: restricted = true places WMS hold `ERP-BATCH` on all stock of the lot at the site (all LPNs, all locations, including stock at dock). restricted = false releases only hold code `ERP-BATCH`; other holds (QA, recall, WMS count) remain. |
| MD004-R03 | Hold placement must complete ≤ 60 s from ERP change (QM-004). Allocations on held stock for released orders trigger re-allocation (INV-005). |
| MD004-R04 | An expiry date change triggers re-evaluation: stock with expiry < today moves to EXPIRED (INV-007); allocations violating customer shelf-life rules are re-allocated. |
| MD004-R05 | Loop prevention: status changes originating from AstraWMS (via IF-INV-001) that come back as batch changes are recognised by correlation (movement reference) and not re-applied. |

## 7. Error Handling

Error classes and default retry behaviour: ISD-00 §6.

| Code | Condition | Class | Handling |
|---|---|---|---|
| MD004-E01 | Item unknown | Dependency (parked) | Park; auto-retry on IF-MD-001 arrival; alert after 30 min |
| MD004-E02 | Expiry date earlier than manufacturing date | Business: correctable | Reject attribute change; alert QA |
| MD004-E03 | Hold could not be applied within 60 s (inventory lock contention) | Transient technical | Retry every 5 s up to 5 min; P1 alert if still failing |
| MD004-E04 | Oracle status code not in status map | Business: correctable | Park; configuration fix |

## 8. Test Scenarios

| ID | Cat | Scenario | Expected Result |
|---|---|---|---|
| TS-MD004-01 | POS | Batch restricted in ERP (SAP: change batch → restricted; Oracle: lot status Hold) | All LPNs of lot on hold ERP-BATCH ≤ 60 s; pick tasks for the lot cancelled and re-allocated |
| TS-MD004-02 | POS | Restriction removed | ERP-BATCH hold released; QA hold on same lot remains |
| TS-MD004-03 | VAR | Expiry date moved into the past | Stock → EXPIRED; ERP status change posted by IF-INV-001 |
| TS-MD004-04 | VAR | Characteristic outside whitelist changed | Ignored |
| TS-MD004-05 | NEG | Lot for unknown item | Parked MD004-E01, applied after item arrives |
| TS-MD004-06 | DUP | Duplicate status message | Single hold |
| TS-MD004-07 | VOL | 5,000 status changes in 10 min (mass recall) | All holds applied; p95 ≤ 60 s |

## 9. Open Points

| # | Item to Confirm | Owner |
|---|---|---|
| 1 | Batch level (material vs plant) in SAP client | SAP functional lead |
| 2 | Oracle material status codes and their mapping to restricted | Oracle functional lead / QA |
| 3 | List of classification characteristics to replicate | QA process owner |

## Change Log

| Version | Date | Change |
|---|---|---|
| 0.1 | 2026-09-30 | Initial draft generated from scope §D |
