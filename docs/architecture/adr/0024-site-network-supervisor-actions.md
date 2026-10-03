# ADR-0024: Site Network, Supervisor Tile Actions, Label Lifecycle and Explanations

- **Status:** Accepted
- **Date:** 2026-10-03

## Context

An expert review of the live system (one main warehouse DC1 and satellite stores) asked for four things:

- **Fix defects first.**
  - The overview's material-issue count did not match the list it opened.
  - A navigation link appeared twice.
  - Work assigned to operators who had left (29 h old) and late yard appointments could not be cleared from the
    overview.
  - Dock stock lingered.
- **Make the satellite scope visible.**
  - Store sites as a type, a site selector, and a network view.
  - A store home without DC work.
  - A stated offline behaviour.
- **Ease of use.**
  - One supervisor action on each red tile.
  - Explained rules on RF.
  - "Unrated" billing instead of a silent 0.00.
- **Futuristic items.**
  - A network digital twin.
  - An ask box.
  - A cutoff labor forecast.

The constraint: extend the status model and the running services; add no new product (yard, labor and billing stay
in the existing services).

## Decision

### 1. Defects

- **Tile equals list.**
  - The material-issue list accepts a comma-separated status set (`?status=APPROVED,PARTIALLY_ISSUED`). An empty
    status means all.
  - The overview tile links to exactly that filtered list.
  - Tile labels use the singular for one item.
- **Supervisor unassign.**
  - `POST /tasks/{id}/unassign` (SUPERVISOR, optional reason) puts an ASSIGNED task back to RELEASED.
  - `POST /tasks/unassign-stale?minutes=30[&type=]` does the same for every task assigned longer than that.
  - The task history records who held the task and who took it back. Nothing has moved while a task is only
    assigned, so this is always safe.
- **Dock sweep.**
  - Already idempotent: one putaway per LPN, no second task for an LPN that has one.
  - Already never targets STAGING_OUT or shipping (ADR-0019).
  - A test now proves the dock is empty after the putaway is confirmed and that a second sweep does nothing.

### 2. Supervisor actions on tiles

An overview tile can carry one action, run on the tile after a confirmation, with its result shown there:

| Tile | Action |
| --- | --- |
| Pallets or loose stock waiting on dock | Create putaways (dock sweep) |
| Tasks assigned > 30 min (all types, with who holds them) | Unassign |
| Order lines short | Reallocate (each short order) |
| Appointments late | Mark no-show |
| Cutoffs at risk | Opens the labor forecast |

### 3. Site network

- **Site type.**
  - `site.site_type` is MAIN or STORE; a store may name its `supplying_site` (a MAIN site).
  - `GET /api/v1/sites` lists the sites of the user's site scope; `GET /api/v1/sites/{id}` returns one site.
  - The header selector uses this list (marking stores) and keeps it on the device for offline use.
- **Network view** (`/network`). Each service reports its own half across the sites of the user's scope:
  - `GET /api/v1/network/inventory`: on hand, allocated, held, aged dock stock, PI freeze.
  - `GET /api/v1/network/inbound`: receipts not started, transfers in transit in, failed postings, late appointments.
  - `GET /api/v1/network/outbound`: open orders and transfers, short lines, refused goods issues, due soon.
  - `GET /api/v1/network/tasks`: open tasks, exceptions, stale tasks.
- **Map and digital twin.**
  - The page draws the sites (main sites in the middle, stores around them, a line per supply relation), coloured
    by aged exceptions: failed postings, refused goods issues, task exceptions, stale tasks and aged dock stock.
    0 is green, 1–2 amber, 3+ red.
  - The main warehouse's aisles (`/sites/{s}/inventory/aisles`, `/sites/{s}/tasks/aisles`) are shown as tiles with
    the same colours and a dashed border when frozen.
  - The aisle is the location ID up to its second dash.
  - Clicking a site opens its overview; clicking an aisle opens its open work.
- **Store home.**
  - At a STORE site the overview shows a banner with the offline behaviour, "My work" (with sync state) and the
    store's work: deliveries and transfers to receive, issues to issue or approve, and counts.
  - The menu hides DC work: waves, packing, loads, labor, yard, billing, slotting, replenishment, returns and
    outbound orders.
- **Seed.** The demo seed creates ST01..ST25 as stores supplied by DC1 (`STORE_COUNT` to change) and saves DC1's
  allocation policy explicitly.

### 4. Offline: the server wins

- RF work stays offline-capable (ADR-0023).
- When the device sends a queued confirmation and the server refuses it (the stock or the task changed meanwhile),
  the client calls `POST /tasks/{id}/sync-conflict` with the refusal.
  - An open task becomes EXCEPTION with reason SYNC_CONFLICT, unassigned, and is visible to the supervisor.
  - The supervisor checks the location and puts the task back with Unassign.
- The device keeps the refused scan under "needs attention" (retry or discard).
- Lists, approvals and transfers are online only, and the store banner says so.

### 5. Label lifecycle

- **Recording.**
  - Every label made is recorded in `printed_label`, one row per site, type and barcode, holding the ZPL, the
    printer (null means browser preview) and the print count.
  - `scan_key` is the barcode as a scanner reads it: no symbology identifier, no GS1 parentheses.
- **Verify.** `POST /labels/printed/verify {scan}` after the label is applied. An unknown label is refused (404) and
  so is a void one (409 MD_LABEL_VOID).
- **Reprint.** `POST /labels/printed/{id}/reprint {printer?}` sends to a printer, or returns the ZPL for the browser
  preview.
- **Void.** `POST /labels/printed/{id}/void {reason}`.
- **No reuse.** A voided label is never printed again; an LPN number stays unused.

### 6. Explanations, billing, forecast, ask box

- **RF "Why this task?".**
  - Next because: priority, replenishment before picks, pick path, then the oldest permitted work.
  - The putaway rule in words: pick face, consolidate, nearest empty, slow mover far, slotting zone, QC.
  - For picks, the allocation rule.
  - The engine's suggestion when it was overridden.
- **Billing.** Events without a rate are reported with amount null ("unrated") and counted. The totals say how many
  are unrated, and an admin tile shows this month's unrated events.
- **Cutoff labor forecast.**
  - The labor board adds `pickWorkByOrder` (open pick work per order in standard minutes).
  - The UI sets it against the order cutoffs: earliest cutoff first, cumulative work, shared by the operators active
    now. A cutoff is at risk when that work does not finish before it.
  - The forecast shows the operators each cutoff needs (Labor page and a tile).
- **Ask box.**
  - A question in plain words ("what is late for UPSN", "which orders are short", "transfers to ST03", ...) is
    answered from the live lists, with links.
  - It is a fixed set of recognised questions, not a language model, and says so when it does not know a question.

### 7. Identity

- The passkey (ADR-0023) is the face or fingerprint check. The device does the biometric; AstraWMS never sees a face
  and never uses one as the only credential.
- Recording the authenticator on each task is **not** done: Keycloak's events record how each user signed in.

## Consequences

- No new service and no new status: SYNC_CONFLICT is an exception reason; site type, the printed label and the
  forecast data are additive.
- **Contracts unchanged.** The network endpoints are reads that each service answers for its own data. The gateway
  routes `/api/v1/network/{inventory|inbound|outbound|tasks}`.
- **The network view is eventually consistent** across services (each half is read separately), which is enough for a
  colour per site.
- **Stuck work on a live system is cleared by a supervisor** with the new tile actions. The system never unassigns or
  no-shows anything on its own.
