# G — UI/UX Scope

---

## G.1 Design Principles

| Principle | Application |
|---|---|
| Scan-first | Every RF step is advanced by a scan wherever possible; keyboard entry is the exception, and it is logged |
| One decision per screen (RF) | Small screens show one instruction, the critical data (location, item, qty, UoM), and one expected input |
| Exception by function key | Exceptions (short, damage, tote full, skip) are reachable on dedicated function keys or a menu, never through free text |
| Glanceable feedback | Distinct sound + haptic + colour for success / warning / error; error messages state what is wrong and what to do |
| Role-based workspaces (web) | Users land on a workspace tuned to their role, not a generic menu |
| Consistency | Shared design system (tokens, components) across web, RF, portals; light/dark/high-contrast themes |

---

## G.2 Web UI Modules

| Module | Key Screens | Primary Roles |
|---|---|---|
| **Control Tower** | Site overview, backlog vs capacity by process, cut-off risk, alerts | Site Manager, Shift Supervisor |
| **Inbound** | Appointment calendar (door × time grid, drag-drop), expectation list, receipt detail, discrepancy workbench | Dock Scheduler, Receiving Supervisor |
| **Inventory** | Inventory inquiry (item/location/LPN/lot/serial), LPN history, holds manager, adjustment entry & approval, count planning & variance approval, reconciliation workbench | Inventory Control |
| **Outbound** | Order pool with filters/facets, order detail timeline, allocation results, short list, wave planner (template, preview, simulate, release), waveless controller settings | Outbound Planner |
| **Task Management** | Task queues, assignment board (operators × zones), priority changes, bulk reassign | Shift Supervisor |
| **Pack & Ship** | Pack station UI (touch-optimised), load planning board, dock door status, manifest close | Packer, Shipping Clerk |
| **Quality** | Inspection queue, usage decisions with e-signature, recall console (trace forward/backward graph) | QA |
| **Returns** | Returns station UI, grading, disposition overrides | Returns Processor |
| **VAS & Kitting** | Work order board, station instructions with media | VAS Lead |
| **Labor** | Operator performance, indirect time, labor plan vs actual | Labor Manager |
| **Slotting** | Proposals, what-if comparison, move plan tracking | Slotting Analyst |
| **Configuration** | Location builder (bulk generation by pattern: aisle/bay/level/position), rules/strategies editor with test harness ("evaluate this rule for this LPN"), RF flow designer, label designer, UDFs, reason codes | Solution Admin |
| **Integration Monitor** | §D.6.3 | Integration Analyst |
| **Billing (3PL)** | Rate cards, billable events, invoice runs | Billing Analyst |
| **Administration** | Users, roles, devices, printers, sites, audit log viewer | System Admin, Security Admin |
| **Client Portal** (external) | Inventory, orders, ASN entry, shipment tracking, reports, invoices | 3PL client users |
| **Carrier Portal** (external) | Appointment booking, check-in pre-registration | Carriers |

Common web capabilities: saved views and filters, column configuration, bulk actions with preview, export (CSV/XLSX), deep links, keyboard shortcuts, in-context audit history ("who changed this?"), and embedded help.

---

## G.3 RF / Mobile Workflows

### G.3.1 Standard RF Flow Catalogue

| Flow | Steps (scan-driven) |
|---|---|
| Login | Badge scan → PIN → select equipment (scan equipment tag) → zone assignment auto |
| Receive (ASN SSCC) | Scan door/receipt → scan SSCC → confirm (single scan) → print/apply if required → drop location |
| Receive (line) | Scan receipt → scan item → qty (pre-filled from GTIN level) → lot/expiry (GS1 auto-parse) → serials → LPN → confirm |
| Putaway | Get task → scan LPN → display target → scan location check digit → confirm |
| Pick (cluster) | Get work → build cart (scan totes) → location → item → qty → tote position → … → drop |
| Replenish | Get task → scan source LPN → qty → scan target location → confirm |
| Count | Get task → scan location → for each LPN/item: scan + qty → "location complete" |
| Move (ad-hoc) | Scan LPN or location+item → qty → scan target → reason (if required) |
| Load | Scan door/trailer → scan pallet/carton → validation → … → close (seal scan) |
| Inquiry | Scan anything (location, LPN, item, lot, serial) → contextual info |

### G.3.2 Example Screen: Pick Instruction (RF, 4" display)

```
┌─────────────────────────────┐
│ PICK  Cluster C-1182  3/27  │
│─────────────────────────────│
│ LOC   A-12-03-B   [CD: 47]  │
│ ITEM  100045                │
│       Bluetooth Speaker Mini│
│ QTY   4  EA   (Case=12)     │
│ LOT   FEFO → B2409A         │
│ TOTE  POS ③                 │
│─────────────────────────────│
│ Scan location check digit   │
│ F1 Short  F2 Skip  F3 Info  │
└─────────────────────────────┘
```

Rules shown on screen: the check digit is scanned (or spoken, for voice). The item scan follows. Quantity is entered by keypad only when the qty is > 1 and the item is not unit-scanned. Totes are confirmed by position scan.

---

## G.4 Voice & Wearable Support

| Capability | Specification |
|---|---|
| Voice picking | Voice-directed workflows via voice gateway integrating with commercial voice platforms (e.g., Honeywell Vocollect via VoiceLink/ODR-style interface) or native Android speech (TTS/ASR) with noise-robust headsets |
| Dialogue design | Prompt: "Aisle 12, slot 03 B" → operator speaks check digit "4 7" → "Pick 4" → operator confirms "4" → (optional) "Ready"; commands: "say again", "short", "skip slot", "how much more" |
| Multi-language | Operator-specific language; speaker-dependent or independent recognition |
| Wearables | Ring scanners (Bluetooth HID/SDK), wrist-mounted computers, smart glasses (vision picking showing location/qty overlay) as optional |
| Hands-free confirmations | Voice + ring-scan hybrid (scan item, speak quantity) |
| Environmental | Freezer-rated devices and headsets; glove-compatible touch targets ≥ 12 mm |

---

## G.5 Role-Based Access Control

### G.5.1 Model

- **Permission**: an atomic function (e.g., `INV_ADJUST_CREATE`, `INV_ADJUST_APPROVE_L2`, `PUT_OVERRIDE`, `QM_RELEASE_ESIG`).
- **Role**: a named bundle of permissions (e.g., *Receiver*, *Inventory Manager*).
- **Scope** (attribute constraints): site(s), owner(s), zone(s), and value thresholds (e.g., approval ≤ $5,000).
- **Assignment**: user → role(s) with scope, with optional effective dates (temporary coverage).
- **SoD policies**: conflicting permission pairs that are blocked or warned at assignment time.

### G.5.2 Standard Role Matrix (excerpt)

| Permission Area | Receiver | Picker | Inv. Analyst | Inv. Manager | Supervisor | QA Mgr | Solution Admin | 3PL Client |
|---|---|---|---|---|---|---|---|---|
| Receive against ASN | ✔ | | | | ✔ | | | |
| Receive unexpected | | | | | ✔ | | | |
| Pick / pack | | ✔ | | | ✔ | | | |
| Inventory inquiry | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ | Own |
| Adjustment create | | | ✔ | ✔ | ✔ | | | |
| Adjustment approve (≤ threshold) | | | | ✔ | ✔ (L1) | | | |
| Status change QI→Available | | | | | | ✔ (e-sig) | | |
| Location override | | | ✔ | | ✔ | | | |
| Rule/config change | | | | | | | ✔ | |
| User/role admin | | | | | | | (Security Admin only) | |
| Portal: create ASN / view orders | | | | | | | | ✔ |

---

## G.6 Dashboard Designs

### G.6.1 Control Tower (textual layout)

```
┌───────────────────────────── SITE: DC-DAL-01   Shift B   14:05 ─────────────────────────────┐
│ [KPI tiles] Lines shipped 38,412 / plan 52,000 | UPH 142 (tgt 135) | Cut-off risk: 3 carriers │
│             Dock-to-stock avg 2.6h | Inventory holds 41 | Integration errors 2 (oldest 7m)     │
├──────────────────────────────┬───────────────────────────────┬────────────────────────────────┤
│ Process flow (Sankey-style)  │ Backlog vs capacity by hour   │ Cut-off monitor                │
│ Pool→Released→Picked→Packed→ │ (stacked bars per process,    │ Carrier | Cut-off | % Ready |  │
│ Staged→Loaded, with WIP #s   │  line = available labor hrs)  │ Risk (green/amber/red)        │
├──────────────────────────────┼───────────────────────────────┼────────────────────────────────┤
│ Zone heat map (floor plan):  │ Labor board: operators by     │ Alerts & exceptions feed       │
│ congestion, open tasks, temp │ zone/task, idle >10m flagged  │ (actionable, click-through)    │
└──────────────────────────────┴───────────────────────────────┴────────────────────────────────┘
```

### G.6.2 Other Dashboards

| Dashboard | Content |
|---|---|
| Inbound | Appointments today (arrived/late/no-show), doors occupied, receipts in progress, dock-to-stock aging, discrepancies |
| Outbound | Orders by status and cut-off, short picks, pack station throughput, trailers loading with % complete |
| Inventory health | Accuracy trend, count completion vs plan, holds by reason, expiring stock (30/60/90 days), aged/obsolete inventory, location utilisation |
| Cold chain | Zone temperature tiles with sparkline, excursions open, TOR at-risk LPNs |
| Automation | Equipment availability, throughput vs capacity, faults, manual fallbacks |

The data freshness target is ≤ 15 s (NFR). Dashboards subscribe to event streams through server-sent events and do not poll heavy queries.
