# ADR-0028: Closing the Store-Replenishment Loop in the UI

- **Status:** Accepted
- **Date:** 2026-10-05

## Context

On live, Accept for ST12 (29 of 100100) could not allocate.

- The DC1 pick face P-01 had 0 free: 40 on hand, all 40 allocated to open transfers.
- The reserve is whole pallets: A-01-1 holds 240, of which 60 is reserved for the face's replenishment; B-01-2 holds
  120.
- The site policy takes a reserve pallet only when the order covers it.

The reviewer saw nothing happen on Accept. Accept asked through a browser `confirm()` dialog, which automated browsers
dismiss without a trace. The refusal was shown only at the top of the page. And there was no way to make the face
usable from that row.

## Decision

- **Confirmation on the button.** Accept, and every tower tile action, asks on the button itself (Confirm / Cancel).
  No browser dialogs are used for these actions.
- **Refusal on the row and as a toast.** The refusal stays on its row and also shows as a toast.
- **Shortage reason names the face and the rule.** Inventory's reason for a short line, which the strict transfer
  repeats, now says "pick face P-01 has 0 free (capacity 40), replenishment of 60 from A-01-1 open: confirm it on RF;
  reserve is in whole pallets (A-01-1 240 of which 60 allocated, B-01-2 120) and the site policy takes a pallet only
  when the order covers it".
- **Face status on each row.** `GET /inventory/faces/{owner}/{item}` returns an item's pick faces (on hand, allocated,
  free, capacity = the rule's max, open replenishments) and its reserve pallets. Each recommendation row shows the face
  next to the source's network free stock.
- **Create replen to P-01.** `POST /inventory/faces/{owner}/{item}/replenish` (supervisor, inventory manager)
  replenishes the faces now, even above their minimum, up to capacity. It never creates a second task: a face with an
  open replenishment returns that one, and the row links to the REPLEN task to confirm on RF.
- **Accept, once the face has stock.** After that task is confirmed on RF, the face has free units and Accept
  allocates the transfer from it, under the strict rule of ADR-0025.

## Consequences

- No new module and no new status: the replenishment and the transfer are the existing ones.
- The reason text is longer, but it is the one place that explains the policy at the point of decision.
