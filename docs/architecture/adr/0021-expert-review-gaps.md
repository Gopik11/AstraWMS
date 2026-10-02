# ADR-0021: Explainable Execution, Slotting, Release Policy, Labor, Yard, 3PL Billing, Automation and RF-Only Floor Work

- **Status:** Accepted
- **Date:** 2026-10-02

## Context

An expert review compared AstraWMS with leading WMS products (Manhattan, Blue Yonder, Körber). It listed what was
missing:

1. **Slotting.** No item–location master: fixed pick face, reserve zone, velocity, cube or units per pallet, golden-zone suggestions from pick history, reslot move.
2. **Visible allocation and wave policy.** FEFO, lot affinity, ship-complete versus partial and cutoff priority were not visible. Nothing said why a line was short. Waves could not be held or released by carrier cutoff.
3. **Demand replenishment interleaved with picks.** Shipped in ADR-0019/0020.
4. **Labor standards.** No expected time per task. No supervisor board comparing active users with the standard. No skill or equipment filter.
5. **Yard and dock.** No inbound appointment, no door booked before the ASN, no trailer dwell next to the receipt.
6. **3PL.** No owner-specific rules (allocation, pack list, label). No billing events (receipt, storage day, pick, VAS, return).
7. **Automation.** No task API for a light or robot to claim, confirm or raise an exception on a pick.
8. **Control tower ages.** Receipt not started > 30 min, dock stock > 15 min, pick assigned without confirmation, posting failed.

It also asked for:

- Every short and every override states the rule that fired.
- Backorder recovery is visible: which receipt freed which order.
- RF is the only path for receiving, putaway, picking, replenishment and counting; the desktop is the exception console.
- No second product for yard, labor and slotting: build them as tasks and policies on the services already running.

## Decision

Each gap is closed in the service that owns the data. No new service is added.

### 1. Explainable execution (inventory, outbound, task, UI)

- **Shorts.** An allocation shortfall returns `shortReason` and `shortDetail`, most actionable first:
  - `WAITING_FOR_REPLENISHMENT`, `POLICY_NO_SPLIT` (stock the policy held back);
  - `AWAITING_PUTAWAY` (stock still at the dock);
  - `LOT_UNAVAILABLE`, `ALLOCATED_ELSEWHERE`, `NOT_AVAILABLE` (with the statuses), `NO_STOCK`;
  - `SHIP_COMPLETE` (in stock, held for a ship-complete order).

  The order line keeps the latest explanation, and the order page shows it.
- **Overrides.** A putaway to a location other than the engine's needs an `overrideReason` (LOCATION_FULL, LOCATION_BLOCKED, LOCATION_DAMAGED, CLOSER_LOCATION, CONSOLIDATE, OTHER). It is stored on the task and shown in the task list.
- **Recovery log.** `outbound_recovery` records every recovered quantity: AUTOMATIC or MANUAL, and the transaction type, location, LPN and operation that freed it. The order page shows it under "Recovered shorts".
- **Control tower ages.** Each overview tile shows its oldest item against a limit:

  | Tile | Limit |
  |---|---|
  | Receipt not started | 30 min |
  | Dock stock | 15 min |
  | Pick assigned, not confirmed | 15 min |
  | Posting failed | 30 min |
  | Trailers in the yard | 120 min |

### 2. Slotting (inventory and task services)

- **Item–location master.** `item_slotting` holds the reserve zone, units per pallet and a velocity class set by hand. Pick faces remain the min/max rules.
- **Velocity.** Velocity comes from 30 days of picks (Pareto by pick operations: A = 80 %, B = next 15 %, C = rest) unless set by hand.
- **Golden-zone suggestions.** Fast movers outside the best pick-path slots get a suggestion: the closest free pick-zone slot.
- **Reslot.** A reslot (SOLUTION_ADMIN, INV_MANAGER) deactivates the old face rule and creates the new one. Free stock at the old face becomes RF **MOVE** tasks (`MoveRequested`).
- **Putaway.** Putaway prefers the item's reserve zone (strategy suffix `_ZONE`) and sends slow movers to the furthest empty slot (`EMPTY_FAR_SLOW_MOVER`).

### 3. Release policy (outbound and inventory services)

- **Carrier cutoffs.** Cutoffs are set per site and carrier, in the site time zone. An order's cutoff is its carrier's cutoff on the planned goods-issue date; without a cutoff it is the goods issue itself.
- **What follows from the cutoff:**
  - Waves, wave release and backorder recovery take the earliest cutoff first, then order priority.
  - Picks rise in priority as the cutoff nears: +5 within 4 h, +9 within 1 h. They stay below replenishment, which feeds them.
- **Wave hold.** A planned wave can be HELD with a reason; it cannot be released until the hold is lifted.
- **Release by cutoff.** "Release by cutoff" plans and releases, in one step, every pooled order due within N minutes.
- **Ship complete** applies from the site default, the owner's rule, or a per-order override (allowed until release). A ship-complete order:
  - is released only when every line is fully allocated;
  - otherwise gives its stock back and waits as BACKORDERED, and backorder recovery retries the whole order;
  - never ships short from the floor: a SHIP_SHORT decision becomes BACKORDER.
- **Lot affinity** (allocation policy): a line is filled from the first lot, in rotation order, that can cover it whole. Lots are mixed only when no single lot can.
- **Owner allocation policy.** An owner can have its own policy at a site, which replaces the site's for its lines.
- **Order priority** (0–100) is set per order by a supervisor.
- **Substitution is not implemented.** A substitute item changes what the ERP delivered. It needs an IF-OB-003 contract change and customer consent per order, so it stays out of scope until the ERP side defines it.

### 4. Labor (task service)

- **Standards.** Each task type has an engineered standard: base seconds plus seconds per unit. Defaults apply until a site sets its own.
- **Requirements.**
  - A standard may require a **skill**.
  - A zone may require **equipment** (e.g. REACH_TRUCK).
  - Operators carry equipment and skills. `next()` hands a task only to an operator who has both.
- **Labor board** (`GET /tasks/labor`), per operator:
  - tasks done in the window, standard against actual minutes, and performance;
  - the current task and its age against its standard;
  - whether the operator is active.

  It also shows the backlog in standard hours.

### 5. Yard and dock (inbound service)

- **Appointments.** `dock_appointment` follows SCHEDULED → CHECKED_IN (gate) → AT_DOOR → CHECKED_OUT, or ends as CANCELLED or NO_SHOW.
- **Door before ASN.** A door can be booked before the ASN exists; the delivery is linked later.
- **One booking, one trailer.** A door has one booking per time slot and holds one trailer at a time.
- **Dwell.** Dwell and door time are computed per trailer. The receipt page shows its appointment and dwell. The overview counts trailers in the yard, and the Yard page shows the door board and late arrivals.
- **Routing.** The gateway routes `/sites/{site}/yard` to the inbound service.

### 6. 3PL (outbound and inventory services)

- **Owner rules** (`outbound_owner_policy`):
  - ship complete;
  - a pack list per carton;
  - a label template. RETAIL adds a content label after the carrier label.
- **Billing** (inventory service): events are captured from the inventory ledger, idempotently, and priced when captured:

  | Event | Source |
  |---|---|
  | RECEIPT | Receipt operation |
  | RETURN | Receipt from `RMA …` |
  | PICK | Pick operation |
  | STORAGE | End-of-day stock per owner and UTC day, from the ledger, back-filled up to 62 days |
  | VAS | Entered by a supervisor |

  - **Rates** are set per owner (or a default) per event type, and per service for VAS. The basis is unit, line, LPN or event; storage is billed per unit-day or LPN-day.
  - **Pricing.** Events without a rate are kept at zero, so later rates do not reprice history.

### 7. Automation adapter (task service)

- **Automated zones.** Tasks starting in an automated zone are not offered on RF.
- **Device API.** A device controller (new role AUTOMATION; its token carries the tenant claim) calls:
  - `claim` with a device ID, to take the next task;
  - `confirm` with the quantity done;
  - `exception`, which hands the task back to people on RF.
- **Confirmations** reuse the RF validation and inventory calls. The device is the actor (`device:<id>`), and its position stands in for the scans.

### 8. RF-only floor work

- **Inbound.** Receipts (line, item, SSCC) and return units are accepted from the RF channel or from a supervisor. A receiver at the desktop gets `403 RF_ONLY`.
- **RF channel.** The task service's calls to inbound are marked RF. The gateway strips `X-Channel` from outside requests, so only services can set it.
- **Already RF-only.** Putaway, picking, replenishment, moves and counts were already done only through RF tasks.
- **Desktop.** The desktop receipt and returns pages send receivers to RF and keep exception receiving for supervisors.

### Event stream

Inventory, task and outbound changes already flow through one transactional outbox per service, with per-tenant
dead-letter replay (ADR-0018). The new events (`SlottingChanged`, `MoveRequested`) use the same path. Billing reads
the inventory ledger and can be re-captured at any time without double counting.

## Consequences

- **Supervisors** get new pages: Slotting, Labor (with automation zones), Yard and Billing. Waves gain cutoffs and hold, and Master data gains owner rules, site time zone, ship complete and lot affinity.
- **RF behaviour.**
  - Receivers must use RF to receive.
  - Operators without the required equipment or skill no longer see such tasks.
  - Tasks of automated zones go to devices first.
- **Keycloak.** The AUTOMATION role is in the realm export. Existing realms add it by hand before a device controller is registered.
- **Storage billing** uses the UTC day. A site-local day would need the site time zone in the inventory service.
