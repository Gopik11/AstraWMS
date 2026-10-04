# ADR-0026: Store Home and Predictive Shortage

- **Status:** Accepted
- **Date:** 2026-10-04

## Context

A review of the network loop (ADR-0025) asked for two things:

- A store-level UI, so a store works only on store work.
- A predictive shortage on top of store replenishment, without replacing min/max.

The constraints:

- Keep Accept → transfer, the TR documents and the SAP 303 column as they are.
- Show the Accept refusal in the UI, for example when the DC pick face has nothing free and the policy will not split
  a pallet.

## Decision

### A. Store home and menu

When the selected site is a STORE (ST01–ST25), the overview is the store home. A supervisor who switches to a store
gets the same page. Its sections:

- **My work.**
- **Inbound:** deliveries to receive, and transfers to this store still being prepared at the sender.
- **Issues:** material issues to issue on RF, and to approve for supervisors.
- **Inventory below min:** store items below min + safety stock or at stockout risk, with stockout date, risk and the
  recommended transfer.
- **Counts:** open counts, and variances to approve.
- **Sync status:**
  - online or offline, scans waiting, scans refused when sent, tasks on the device;
  - offline work ships (ADR-0023), so this shows the real queue state rather than "Online only";
  - it states what works offline (RF receive, issue, count) and what is online only.

The menu at a store keeps RF work, RF material issue, Overview, Receipts, Transfers, Material issues, Stock inquiry
and Cycle counts. It hides Waves, Pack, Loads, Yard, Billing, Management, ERP simulator, Integration and the other DC
screens. The menu is defined in `nav.ts` and tested.

Site-scoped users (the `wms_sites` claim) see only their sites in the selector, as before.

### B. Predictive shortage

The recommendation stays a min/max one (ADR-0025). It adds a forecast:

- **Usage per day:** the store's issues (customer orders, transfers out and material issues) over the last 28 days.
- **Cover target:** `coverDays` on the store setting, 7 by default, next to the store's transit days.
- **Reach:** available + in transit + open transfers to the store. Pipeline is always subtracted before
  recommending.
- **With history:**
  - **Projected stockout date:** today + reach ÷ usage.
  - **Risk:**

    | Risk | When |
    | --- | --- |
    | CRITICAL | Empty, or runs out before a transfer can arrive (cover < transit days) |
    | HIGH | Into safety stock before a transfer arrives (cover < transit + safety ÷ usage) |
    | MED | Under the cover target after transit |
    | none | Otherwise |

- **Without history:** the risk is "no history". It is never "none", and confidence stays LOW.
- **When a transfer is recommended:** below min + safety (as before), or at CRITICAL or HIGH risk.
- **Quantity:**
  - without history: refill to max, as before;
  - with history: usage × (transit + cover days) + safety − reach, at least back to min + safety, capped at
    max − available − pipeline. The row shows the basis.
- **Shortage:** CRITICAL or HIGH risk, or no history and below min + safety. It drives the supervisor's "Store
  shortages" tile (red when one is CRITICAL), linked to the same list (`/store-replenishment?shortage=1`).
- **Accept:** the strict transfer of ADR-0025 is unchanged, and so is its refusal. The refusal text now stays on its
  row on the Store replenishment page.

## Consequences

- One column added: inventory V18, `store_site_setting.cover_days`.
- The store home loads five small lists; each section reports its own loading and errors.
- The forecast is linear (average usage over 28 days): no seasonality and no promotions. Confidence says how much
  history it rests on.
