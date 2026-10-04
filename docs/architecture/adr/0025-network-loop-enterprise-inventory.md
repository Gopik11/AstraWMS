# ADR-0025: Network Loop, Enterprise Inventory, Predictive Replenishment and Integration Hooks

- **Status:** Accepted
- **Date:** 2026-10-04

## Context

A 17-point brief from the 4 October 2026 review (test1–test5 on astrawms.cloud) asked AstraWMS to close the loop
between the main warehouse and its 25 stores before adding an advanced layer. The constraints:

- **Extend, don't replace.** Keep the status model and the six running services.
- **Floor work is an RF task.** Supervisor work goes on the existing control tower.
- **No new products.** No separate BI, TMS, repair or RFID product.

ADR-0024 already delivered the site network, the tile actions, offline sync conflicts and labels. This ADR records
the rest.

## Decision

### 1. Stock in transit is on the inventory ledger

- **Transfer goods issue.** The transfer's goods issue (outbound `ship` → inventory `issues` with
  `transferToSite`) writes `stock_in_transit` rows: transfer, from and to site, owner, item, lot and quantity.
- **Receipt.** At the receiving site, a receipt whose source document is the transfer (`TR-DC1-000001/000010`)
  reduces them, same lot first.
- **Network balance.** Main-site reduction = store increase + what is still in transit, on one ledger, without
  reconciliation.
- **Idempotent.** Replays of the issue or the receipt do not count twice.

### 2. Enterprise item balance

`GET /api/v1/network/items/{owner}/{item}` returns per site:

- on hand, available, allocated
- picked (at outbound staging); packed (in cartons, from outbound)
- in transit in and out
- quarantine (QI), blocked, damaged, expired (status or past its expiry date)
- returned (returns zone)
- available for transfer (available less the store's safety stock)
- open demand (from outbound)

The network total is on hand everywhere plus in transit.

### 3. Ownership is an attribute of the owner

- **Types.** `owner_profile.ownership_type` is OWN, CONSIGNMENT, CUSTOMER_OWNED or SUPPLIER_OWNED.
- **One ledger.** The same balances and moves apply to every type.
- **Reporting.** Value and recall show the type, and the management view separates the company's own assets from
  stock held for others.

### 4. Predictive store replenishment

**Policy.** Each store and item has a `store_stock_policy`: min, max, safety stock and transit days.

**Recommendation** (`GET /api/v1/network/replenishment`):

- **Projected:** available + pipeline − daily usage × transit days.
  - Pipeline is what is in transit to the store plus accepted, unshipped recommendations.
  - Daily usage is the store's issues over the last 28 days.
- **Trigger:** recommend when projected < min + safety, for max − projected.
- **Source:**
  - the warehouse first (a site without store policies);
  - else a store holding more than its max (store to store);
  - among those, most available first.
- **Required date:** when the store falls below min + safety at its usage.
- **Confidence:**
  - HIGH: usage on 10 or more days of the last 28;
  - MEDIUM: 3 or more days;
  - LOW: otherwise, or when no single source covers the quantity.
- **Reason and stockout risk:** a plain-words reason, and a stockout-risk flag.

**Accept** (Store replenishment page, or the tower tile "Store replenishments to send"):

- creates the transfer at the source, the same document as in §1, with:
  - planned ship = required date − transit days;
  - priority 80 and criticality HIGH when stock-out is at risk;
- records the acceptance, so the need is not recommended again.

### 5. Allocation rule on the line; cross-site queue

- **Explicit site policy.** DC1's policy is saved explicitly by the seed: FEFO for lot-controlled items, pick face
  first, whole pallet only when the order covers it.
- **Rule on the line.** Inventory returns the rule that fired with each allocation, for example "FEFO
  (lot-controlled) · site policy · pick face first · whole pallet only when the order covers it". Outbound stores it
  on the line (`allocation_rule`).
- **Short-stock queue.** Backorder recovery and the supervisor's Reallocate serve short lines in this order, not by
  request time alone:
  1. priority;
  2. promised date (cutoff or planned goods issue);
  3. criticality (CRITICAL, HIGH, NORMAL, LOW);
  4. distance (km, nearest first);
  5. age.

  The line records where the order stood in that queue.
- **Settings.** Criticality and distance are set on the order policy and on transfers.

### 6. Physical inventory and risk-based cycle counting

- **Physical inventory (ADR-0022).**
  - Plan by zone, freeze, blind RF count, approve.
  - Differences post to SAP as 701/702 with reason PI_DIFF: the inventory-difference posting of an SAP physical
    inventory document.
  - A frozen location is never picked or moved.
- **Cycle plan** (`/inventory/counts/plan`):
  - **Location class:** the fastest velocity class (A/B/C) of the items it holds.
  - **When due:** after the class interval (site setting, default A 30 / B 90 / C 180 days) since the last finished
    count; at half that interval after a count with a variance; at once when never counted.
  - **Opening counts:** "Open counts" creates blind RF counts (trigger CYCLE) for the most at-risk due locations.

### 7. Identity and labels

- **Scan formats on one RF field:** GS1-128, GS1 DataMatrix (`]d2`) and QR (`]Q3`), including a GS1 Digital Link
  URL. All of them give the item, lot, expiry and quantity.
- **Labels are active only after verification.**
  - A printed (PRINTED) LPN or bin label is refused on RF: `TSK_LABEL_NOT_VERIFIED` on receive (LPN) and on
    putaway (target bin).
  - The RF screen offers "Verify label", and that scan is the verification.
  - A VOID label is always refused.
  - Barcodes AstraWMS did not print (vendor SSCCs) pass.
  - The task service asks master data, and fails open if master data is unreachable: this is a quality gate, not
    access control.
- **RFID later.** An RFID reader would be another reader of the same identity record (`printed_label`: the LPN,
  bin or item), not a second inventory.

### 8. Picking modes

- **Batch and cluster.**
  - `POST /tasks/pick-group?mode=CLUSTER|BATCH` gives the operator a group for one trip, chosen exactly as "next"
    chooses (role, zone, owner, skill, equipment, priority, pick path).
  - CLUSTER: all picks of up to N orders.
  - BATCH: one item from one bin for several orders.
  - Each pick is still a PICK task confirmed with the normal API.
- **Wave release** uses the configured carrier cutoffs (ADR-0021).
- **Other front ends.** Voice, pick-to-light and robots consume the same task API
  (`docs/integration/task-api.md`).

### 9. Quality and returns

- **Damage at receive.**
  - A damage reason (CRUSHED, WET, TORN, BROKEN, CONTAMINATED, OTHER) and an optional photo come from the device
    camera, downscaled to a JPEG of at most 1280 px, 1 MB on the server.
  - The units are received as DAMAGED stock, which is never allocated (like QC hold).
- **Recall** (`/api/v1/network/recall`): by lot or serial, every location, LPN and owner at any site, plus what is
  in transit.
- **Returns.**
  - Returns keep their grade and disposition.
  - The return rate per item and reason is units returned ÷ units shipped from the ledger.
  - The repair chain is a status on the refurbished unit: AWAITING_REPAIR → IN_REPAIR → REPAIRED or
    NOT_REPAIRABLE.

### 10. Dashboards on the existing tower

- **Warehouse manager:** keeps the control tower. It gains "Store replenishments to send" with Accept, and "Check
  out" on trailers past their dwell limit.
- **Satellite manager:** the store home shows available, demand today, recommended replenishment, in transit and
  stockout risk.
- **Management:** the Management page shows inventory value at standard cost, fill rate (units and lines) and
  transfer on-time (shipped by the planned time).
- **Digital twin:**
  - Aisles show occupancy, open tasks, exceptions and last movement.
  - A store node opens a panel with its numbers and links.

### 11. Integration

- **SAP IDoc stays.**
- **Webhooks (in the integration adapter):**
  - Events: `transfer.shipped`, `transfer.received`, `issue.posted`, `issue.returned` and `count.variance`.
  - Each delivery is a JSON POST signed with HMAC-SHA256 of `timestamp.body`, under its own consumer groups so a
    webhook never delays an SAP posting.
  - Retries back off exponentially, up to 8 attempts.
  - Targets must be HTTPS and must not resolve to private, loopback or link-local addresses; local development can
    allow them.
  - The secret is shown once.
- **Carrier tracking** is a status on the load (PICKED_UP … DELIVERED, EXCEPTION), set by the carrier's webhook or
  by hand. This is not a TMS.

### 12. Clearing a demo board

`scripts/clear-board.sh` applies the same supervisor actions as the tiles: dock sweep, unassign stale tasks, no-show
late appointments, check out trailers over 12 h. It shows the list first and asks once. Unrated billing events stay
on their tile until rates are set; the seed now rates ACME.

## Not in this change

- **Not built:** RFID portals, IoT sensors, van stock, a robot fleet.
- **Ready to attach:** the identity record (labels / LPN) and the task API are the attachment points.

## Consequences

- **Migrations:**

  | Service | Migration |
  | --- | --- |
  | inventory | V16 |
  | outbound | V10 |
  | task | V13 |
  | inbound | V6 |
  | master-data | V6 (with ADR-0024) |
  | sap-adapter | V4 |

- **Contracts.** The only additive contract changes are an `IssueRequest.transferToSite` field and a `rule` on the
  inventory allocation result; events are unchanged.
- **Enforcement.** The label check depends on master data being reachable. When it is not, RF work continues and the
  miss is logged.
