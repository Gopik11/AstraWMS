# ADR-0019: RF Receiving Tasks, Zone-Aware Putaway, Allocation Policy and Backorder Recovery

- **Status:** Accepted
- **Date:** 2026-10-02

## Context

A live SAP-style flow test on tenant `demo`, site DC1, found these defects. The status model is kept; only behaviour is added.

| Document | What went wrong |
|---|---|
| ASN 1800001 | Receiving was desktop-only. RF answered "No work" until the receipt was posted. Putaway was directed DOCK-01 → STAGE-OUT. An override to A-01-11 succeeded, but the task list still showed STAGE-OUT. |
| Order 8000001 | Released waveless with no stock and stayed BACKORDERED after 24 EA arrived. There was no reallocate action. |
| RMA 9000001 | Received grade A, RESTOCK, AVAILABLE at DOCK-01, then CONFIRMED, but no putaway task was created. |
| UI | A SOLUTION_ADMIN saw Send IDoc and got FORBIDDEN. A picker could not create a load (correct; the UI just offered no way to load). The overview counted none of the things that needed attention. |

**Root causes:**
- The putaway engine excluded only *inbound* staging location types, so the outbound staging location `STAGE-OUT` was the "nearest empty" slot. The task service also knew neither zones' roles nor pick faces.
- The task list showed the planned target, not the confirmed location.
- Returned units were received loose (no LPN), and putaway is LPN-based.
- Nothing re-allocated short lines when stock arrived.

## Decision

1. **Zone types are part of the location contract.**
   - `LocationUpserted` 1.2 carries `zoneType` (master data schema 1.2, additive).
   - Inventory and task projections store it. `POST /sites/{site}/locations/republish` re-sends every location so existing projections learn it.
   - Roles used:

     | Zone type | Role |
     |---|---|
     | DOCK, RECEIVING, RETURNS | Inbound staging |
     | SHIPPING (and location type STAGING_OUT) | Outbound |
     | QC, QUARANTINE | Inspection |
     | PICK, FORWARD | Pick faces |
     | Anything else (RESERVE) | Storage |

2. **RF receiving tasks.**
   - The inbound service publishes `ReceiveRequested` (ASN or RMA) when a delivery or RMA is created or changed while it can still be received, and when a blind return is opened. It publishes `ReceiveEnded` when the document is closed or cancelled.
   - The task service keeps one RECEIVE task per document (priority 40, below putaway, so the dock is cleared first).
   - The RF device scans:
     - the document;
     - item and quantity;
     - lot, expiry and serials when the item needs them;
     - the LPN;
     - the check digit of a dock, receiving or returns location.
   - The task service validates the scans and calls the inbound service with the operator's token:
     - `POST /receipts/{doc}/receive-item` (the item chooses the line);
     - or `/returns/{rma}/units`, with grade and disposition.
   - A device-generated `scanId` makes a retried scan idempotent.
   - **Finish** closes the document; lines received short need a reason. Closing still sends the ERP confirmation. Desktop receiving stays for supervisors.
3. **Putaway rules** (`PutawayEngine`):
   - Never inbound staging, outbound staging or shipping, for suggestions and for overrides.
   - AVAILABLE stock goes to the item's fixed pick face if the LPN fits under the face's maximum. Otherwise it goes to reserve: consolidate first, else the nearest empty location.
   - Pick-zone locations and other items' faces are never reserve.
   - Temperature, hazmat, mixing and LPN-capacity checks apply as before.
   - Stock that is not AVAILABLE goes to a QC zone; a site without QC zones stores it normally, and its status keeps it from being allocated.
   - Pick faces come from the inventory service's min/max rules through the new `PickFaceChanged` event.
   - On completion, `target_location` is where the LPN actually went (override included); the suggestion stays in `suggested_location`. The inventory MOVE transaction has always carried the confirmed location.
4. **Allocation policy** (inventory), each step in rotation order:
   - **Rotation:** FEFO for lot-controlled items, otherwise FIFO, unless the request names one.
   - **Step 1:** the item's pick faces.
   - **Step 2:** reserve loose stock, and full LPNs that the remaining quantity covers.
   - **Step 3:** reserve LPNs broken into, but only for items without a pick face.
   - Stock in dock, receiving, returns, shipping or QC zones is never allocable.
   - Allocation location and LPN are recorded on the order, as before.
5. **Demand replenishment.**
   - A face's trigger is its *free* quantity: on hand minus open allocations plus incoming replenishment.
   - Allocating from a face can take it to its minimum, which immediately creates a REPLEN task. REPLEN priority 70 is above pick priority 60, so the replenishment comes before the pick.
   - Within one priority, RF work follows the travel path (`pick_seq` of the source location), so replenishments and picks interleave by location.
6. **Backorder recovery** (outbound).
   - The outbound service consumes `InventoryChanged`. When AVAILABLE stock of an item increases anywhere except a pick into staging, the short lines of that item are allocated again:
     - on BACKORDERED and RELEASED orders only;
     - earliest planned goods issue first, then order priority (new column, default 50).
   - Inventory decides what is allocable, so a receipt on the dock recovers nothing until it is put away.
   - A recovered order returns to RELEASED with pick tasks.
   - Picked orders are not touched automatically, because they may already be packed. Supervisors have `POST /orders/{doc}/reallocate` ("Reallocate shorts") for BACKORDERED, RELEASED or PICKED orders that are not on a load.
   - Packing still refuses BACKORDERED orders, and loading takes only PICKED ones.
7. **Returns putaway.**
   - Every returned unit is received on an LPN: the scanned one, or `R<rma>-<n>`. It therefore gets the same putaway task as a vendor receipt.
   - Restocked units go to storage. Quarantined, scrapped and other blocked units go to QC; their status keeps them from being allocated.
8. **Roles on RF.**
   - `next` hands out only the task types of the operator's roles:

     | Role | Task types |
     |---|---|
     | RECEIVER | RECEIVE, PUTAWAY, RETURN, REPLEN |
     | PICKER | PICK, RETURN, REPLEN, COUNT |
     | INV_ANALYST | COUNT, REPLEN |
     | SUPERVISOR | All |

   - Operators can hand a started task back (`/release`).
9. **UI.**
   - The RF screen gains a Receive form for ASNs and RMAs. The putaway form shows why the location was chosen.
   - The task list shows the actual location, plus the suggestion after an override.
   - Order detail shows source location and LPN per line and a "Reallocate shorts" action.
   - The pack station lets a picker add a packed order to an OPEN load.
   - Only the ERP_INTEGRATION role sees Send IDoc.
   - The overview has "Needs attention" counts:
     - receipts not started;
     - order lines short;
     - ERP postings failed (receipts and returns);
     - goods issues failed;
     - pallets waiting at the dock.
   - Receipts, orders, tasks and returns can be searched by delivery, item and customer or vendor.

## Consequences

- `scripts/smoke-flow.sh` replays the three documents end to end. Service tests cover each rule:
  - `TaskIT`: putaway targets, pick face, QC, returns location, override, RF receiving, role routing;
  - `AllocationPolicyIT`;
  - `OutboundIT`: recovery by event, by goods-issue order, and manual;
  - `InboundIT` and `ReturnsIT`: receiving work messages, receive by item, return LPNs.
- **Existing environments** need one republish per site (`POST /api/v1/sites/{site}/locations/republish`, SOLUTION_ADMIN) so zone types reach the projections. Min/max rules created before this release must be saved once more so the task service learns the pick faces.
- **Behaviour changes:**
  - Stock at a RETURNS-zone location is no longer allocable until it is put away.
  - Pickers no longer get putaway tasks, and receivers no longer get picks.
- Not in this change: yard management, labour standards, billing.
