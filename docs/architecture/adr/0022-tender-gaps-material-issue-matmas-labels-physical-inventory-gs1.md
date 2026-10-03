# ADR-0022: Material Issue to Cost Objects, SAP Material Master, Barcode Labels, Physical Inventory and GS1-128 Scans

- **Status:** Accepted
- **Date:** 2026-10-03

## Context

A tender for a barcode WMS (a main warehouse and 25 satellite stores, integrated with SAP) was reviewed against
AstraWMS. Most of it was already covered: multi-site, RF scanning, putaway/picking, SAP deliveries, transfers via SAP
stock transport deliveries, and cycle counts. Five product gaps remained:

1. **Controlled material issue.** Stock goes out to authorized users or departments, charged to a project/WBS
   element or a cost code. There was no issue document, no account assignment and no SAP consumption posting.
2. **Material master from SAP.** The item master could only be maintained through the API or the UI.
3. **Barcode printing.** Only shipping-carton labels existed. Bin, item and pallet labels were missing.
4. **Full physical inventory.** Only per-location cycle counts existed. There was no wall-to-wall count, no stock
   freeze and no posting of all differences at once.
5. **Composite barcodes.** The tender expects simple and composite scans (GTIN, lot and expiry in one GS1-128 label).

## Decision

Each gap is closed in the service that owns the data.

### 1. Material issue (inventory service)

- **Cost objects.** Cost centres, WBS elements and internal orders are master data of the tenant (the SAP controlling
  area). Each has a description, a department, an open/closed flag, and optionally the users allowed to request
  issues to it. They are maintained by admins, inventory managers or the ERP (`PUT /cost-objects/{type}/{code}`).
- **Issue requests** follow REQUESTED → APPROVED → PARTIALLY_ISSUED → ISSUED, or end as REJECTED, CANCELLED or
  CLOSED (partly issued, the rest not needed). A request names the owner, the cost object, the recipient and the
  lines.
- **Approval** is given by a supervisor or inventory manager other than the requester (segregation of duties).
- **Issue scans (RF material issue):** the store keeper scans the bin, then the item, and keys the quantity.
  - The item scan may be the item number, a GTIN of one of its units, or a GS1 label. A GS1 lot fills the lot.
  - Each scan is an inventory operation (`MATERIAL_ISSUE`) and an ERP goods issue to the cost object:
    `ISSUE_COST_CENTER` / `ISSUE_WBS` / `ISSUE_ORDER`, which are SAP 201 / 221 / 261.
  - A retried scan (same Idempotency-Key) is answered, not done twice.
- **Returns** of unused material (`MATERIAL_RETURN`) put stock back and reverse the consumption (202 / 222 / 262), up
  to what was issued.
- **Contract change.** `GoodsMovement` 2.1 adds `account` (object type, code, recipient, issue number). The change is
  additive: older consumers ignore it. The SAP adapter maps it to BAPI_GOODSMVT_CREATE COSTCENTER / WBS_ELEM /
  ORDERID / GR_RCPT. A consumption posting without a cost object is a mapping error.

### 2. MATMAS05 (SAP adapter)

- **IDoc port.** `POST /api/v1/sap/idocs/matmas05` (ERP_INTEGRATION) maps the material master into the item master
  and writes it through master data's item API on the ERP channel. Master data then publishes `ItemUpserted` as for
  any item.
- **Mapping:**

  | SAP | AstraWMS item |
  |---|---|
  | MATNR (leading zeros removed when numeric) | Item number |
  | MAKTX (English, else the first) | Description |
  | MEINS, E1MARMM units | Base unit and units; EAN11 as GTIN when its check digit is valid; dimensions in cm, weight in kg |
  | MTART | Item type |
  | LVORM / MSTAE | Status |
  | MHDHB / MHDRZ | Shelf life |
  | STOFF | Hazardous |
  | E1MARCM per mapped plant | Site with batch and serial control and status |
  | E1MBEWM | Standard cost per base unit |
  | — | Owner: the plant's default owner |

- **Merge.** The adapter keeps what only the WMS knows: sites at plants not in the IDoc, and the temperature class.
- **IDoc status.** 53 when master data takes the item. 51 with the reason (mapping error, unknown unit, duplicate GTIN),
  and the HTTP answer is 422.

### 3. Labels (master data service)

- **ZPL labels**, 4 × 2 in:
  - **Location:** Code 128 of the location ID, the check digit in a box, and the zone.
  - **Item:** GS1-128 `(01)` GTIN of the chosen unit, otherwise Code 128 of the item number.
  - **LPN:** pre-numbered labels from a per-site series (`L<site><9 digits>`); numbers are never reused.
- **Printers.** Network label printers are set up per site. They are reached on raw ports 9100–9199 only, so the
  service cannot be pointed at other services' ports. A printer can be the default for a label type. When AstraWMS
  can reach the printer (on-premises or over a VPN), the ZPL is sent to it over TCP.
- **Without a reachable printer**, the Labels page prints from the browser, drawing its own Code 128 / GS1-128 as SVG,
  or downloads the ZPL.

### 4. Physical inventory (inventory service)

- **Document flow:** PLANNED → COUNTING → POSTED, or CANCELLED. A physical inventory covers the whole site or chosen
  zones.
- **Start** opens a blind RF count for every active location in scope.
- **Freeze** (optional): `location_freeze` stops all movement in and out of those locations, and allocation skips
  them, except the count postings themselves.
- **No count posts on its own.** Within tolerance it goes to review; disagreeing counts still go to a recount by
  another user. Approving a single count of a physical inventory is refused.
- **Post** needs every location counted. It is done by an inventory manager or supervisor who counted none of it. It
  approves all differences with reason PI_DIFF (701/702 to SAP, as MI07 would) and lifts the freeze.
- **Cancel** rejects open counts and lifts the freeze.

### 5. GS1-128 (astra-common, task service, UI)

- **One parser in Java and TypeScript.** It reads bracketed scans (`(01)…(10)…`) and raw scans (symbology ID, FNC1 as
  GS). Supported AIs: 00, 01, 02, 10, 11, 15, 17, 21, 30, 37 and 400. It checks GTIN and SSCC check digits, and reads
  YYMMDD dates (DD 00 means the end of the month). Item numbers and bare GTINs are not taken for GS1 data.
- **RF pick.** A GS1 scan matches by GTIN, and a scanned lot must be the task's lot (`TSK_WRONG_LOT`).
- **RF receive.** The GTIN resolves to the document's item. Lot, expiry, serial, count and SSCC (as the LPN) fill the
  fields the operator left empty. The RF form parses the scan on Enter or when the field is left, so a wedge scanner
  typing character by character is not parsed half-way.

## Not in this change

- **Offline operation.** It is not supported; the tender response states it. Scans already carry idempotency keys,
  which a later offline queue could use to replay them.
- **Transfers started in the WMS without an SAP stock transport order.** Transfers stay SAP-driven
  (NL/NLCC deliveries: outbound at the issuing site, ASN at the receiving one).
- **Face recognition.** It is not offered. Keycloak passkeys can use device biometrics (configuration).

## Consequences

- **New pages:** Material issues, RF material issue, Labels, and physical inventory on the Counts page. The ERP
  simulator can send a material master.
- **Gateway:** `/sites/{site}/labels` reaches master data through the existing `/sites` route. The sap-adapter needs
  `MASTER_DATA_URL` (set in both compose files).
- **Freeze:** a frozen location refuses picks too. A physical inventory is planned when the zone's work is stopped.
- **Frontend:** pages are lazy-loaded to keep the initial bundle small.
