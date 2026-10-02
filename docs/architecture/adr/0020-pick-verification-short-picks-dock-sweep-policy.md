# ADR-0020: Pick Verification, Short-Pick Decisions, Dock Sweep and Explicit Allocation Policy

- **Status:** Accepted
- **Date:** 2026-10-02

## Context

The second review of the SAP flow test (after ADR-0019) asked for:

1. **Dock sweep.** AVAILABLE stock left at the dock must get a putaway. The overview showed one unit waiting: the RMA 9000001 unit, received loose before returns got LPNs, which never got a task.
2. **Search by LPN.** Search must cover receipts, orders and tasks, and the status-filtered pages the overview tiles link to.
3. **Demand replenishment.** It must release a REPLEN task before the pick. This shipped in ADR-0019; it only applies to items with a min/max rule.
4. **Pick verification.** An RF pick asked only for the location check digit and the quantity. Like Manhattan and Körber, a location-only pick must be rejected; the item or its GTIN is scanned.
5. **Short-pick decisions on the device.** A short pick needs a reason and a decision: deallocate, reallocate elsewhere, or hand the line to backorder recovery.
6. **Explicit allocation policy per site.** FEFO for lot-controlled items and full-LPN versus split were implied by the code.
7. **Visible RF actions.** "Close and confirm to ERP" and "Stop for now" sat in a collapsed `<details>`. A scanner session read them as empty buttons.

## Decision

1. **Dock sweep** (task service, `POST /tasks/sweep-dock`, SUPERVISOR).
   - **Scope:** everything at inbound staging (dock, door, receiving and returns locations or zones).
   - **LPNs:** an LPN without an open putaway task gets one.
   - **Loose stock** is first moved onto a generated LPN (`DK…`) at the same location. The inventory quantity move now defaults to the base unit. That LPN's arrival at the dock is a normal putaway trigger.
   - **When it runs:** whenever a receipt or return is closed, since everything then on the dock is older than the close. It also runs from the overview tile ("Create putaways for dock stock").
   - **Failures:** a stale projection or concurrent move is reported per item and retried by the next sweep.
2. **Search** now also matches LPNs and SSCCs:

   | Page | Matches |
   |---|---|
   | Receipts | Received LPNs, expected SSCCs |
   | Orders | Pick LPN, allocated LPNs, carton SSCCs |
   | Returns | Unit LPNs |
   | Tasks | Source and destination LPNs |

   The search box sits next to the status filter on every page a tile links to, and both are kept in the URL.
3. **Pick verification.** `POST /tasks/{id}/pick` requires `item`: the item number, or a GTIN of one of its units.
   - GTINs reach the task service through `ItemUpserted` 1.3 (`Uom.gtin`, additive).
   - GTIN-8/12/13/14 compare without leading zeros.
   - Missing scan → `TSK_ITEM_SCAN_REQUIRED`. Another item → `TSK_WRONG_ITEM`.
4. **Short picks** need `shortReason` (NOT_FOUND, QTY_LESS, DAMAGED, WRONG_ITEM, OTHER) and take `shortAction`:

   | Action | Effect |
   |---|---|
   | `REALLOCATE` (default) | Reallocate elsewhere now, as before. What cannot be reallocated ships short. |
   | `BACKORDER` | The quantity is deallocated and the line is put on hold. The order stays RELEASED (not PICKED) until backorder recovery allocates it when stock arrives, or a supervisor closes it. |
   | `SHIP_SHORT` | Deallocate; the line ships short. |

   - Both fields travel on `TaskCompleted` 1.1.
   - `outbound_line.qty_short_closed` marks short quantity that ships short. Automatic recovery only takes `qty_short - qty_short_closed`.
   - A supervisor's "Reallocate shorts" also reopens closed shorts. The new "Close shorts" (`POST /orders/{doc}/close-shorts`) makes the rest ship short.
5. **Allocation policy per site** (`GET/PUT /inventory/allocation-policy`, PUT by SOLUTION_ADMIN; Master data → Allocation policy). Defaults are the ADR-0019 behaviour:

   | Setting | Values (default first) |
   |---|---|
   | Lot-controlled items | FEFO, FIFO |
   | Other items | FIFO, FEFO |
   | Pick faces first | On, off |
   | Full LPN | `COVERED_ONLY`, `SPLIT_ALLOWED`, `NEVER_SPLIT` |

   - `COVERED_ONLY`: take a whole pallet when the order covers it; split pallets only for items without a face.
   - `SPLIT_ALLOWED`: split freely in rotation order.
   - `NEVER_SPLIT`: whole pallets only; the rest comes from the face.
   - A request that names a rotation still overrides the policy.
6. **RF finish actions** are always visible in a labelled "Finish" section, with `aria-label`s naming the document.

## Consequences

- **Tests:**
  - `TaskIT`: item and GTIN verification, short reason and action validation, dock sweep.
  - `OutboundIT`: BACKORDER keeps the order open until recovery; SHIP_SHORT goes PICKED and is not recovered; close shorts; search by LPN.
  - `AllocationPolicyIT`: policy defaults, admin-only change, NEVER_SPLIT, SPLIT_ALLOWED, lot rotation.
  - `smoke-flow.sh`: now also checks a refused location-only pick and the dock sweep of a loose unit.
- **Clients must change:** RF clients and scripts must now send `item` on every pick, and a reason on a short pick. All smoke tests were updated.
- **Existing data:**
  - Shorts recorded before this release are marked closed: they already shipped short.
  - Items need to be saved once in master data before their GTINs scan on RF; item-number scans work immediately.
- **Not built yet:** serial-level pick verification beyond the existing serial scan, and LPN scan on pick (picks from a whole pallet).
