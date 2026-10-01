# ADR-0017: Customer Returns in the Inbound Service, Graded per Unit and Posted in Two Steps

- **Status:** Accepted
- **Date:** 2026-10-02

## Context

§8 asks for customer returns:
- RMAs arrive from the ERP (IF-RET-001); unexpected returns arrive without one (RET-EX-01).
- Each unit is graded and given a disposition (RET-001). Over-returns need a supervisor (RET-002). Recalls and serial mismatches go to quarantine (RET-EX-03).
- The ERP is told what came back and what happened to it, as a receipt into returns stock then a disposition movement (IF-RET-002, RET002-R01).

## Decision

1. **Returns live in the inbound service** (`return_order`, `return_line`, `return_unit`), next to receipts.
   - A return is physically a receipt: the same dock, the same `InventoryClient.receive` with an idempotency key (`RET-<key>`).
   - A separate service would duplicate the receiving plumbing for no gain.
2. **Lifecycle:**
   - EXPECTED (RMA from the ERP) or IN_PROGRESS (blind return, number `BLIND-nnnnnn`)
   - → IN_PROGRESS on the first unit
   - → CLOSED on close
   - → CONFIRMED, or POSTING_FAILED on the ERP result. A supervisor can repost a POSTING_FAILED return with the same transaction IDs.
   - An RMA change or cancel from the ERP is applied only while nothing has been received; after that it is rejected with an application ack.
3. **Units are received one scan at a time**, each with its own `Idempotency-Key`, and each carries:
   - a condition grade A–E;
   - a disposition that drives the stock status at the returns location:

     | Disposition | Stock status |
     |---|---|
     | RESTOCK | AVAILABLE |
     | QUARANTINE | QI |
     | REFURBISH / RTV / LIQUIDATE | BLOCKED |
     | SCRAP | DAMAGED |

   - The disposition is suggested when left blank:

     | Situation | Suggested disposition |
     |---|---|
     | Grade A or B | RESTOCK |
     | Grade C | REFURBISH |
     | Grade D | RTV |
     | Grade E | QUARANTINE |

   - Recalls and serials not on the RMA are forced to QUARANTINE, even if the user picked something else.
   - An item not on the RMA is received as a **wrong item** (RET-EX-02): flagged, with no RMA line, and the owner must be given.
   - More than the RMA quantity is refused (`RET_QTY_OVER_RMA`) unless a SUPERVISOR overrides it; the unit is then flagged `over_rma`.
4. **Two transaction IDs** per close: `receiptTxnId` and `dispositionTxnId`.
   - The ISD's `-R` / `-D` suffixes don't fit SAP's 16-character XBLNR, so each step gets its own WMS transaction ID.
   - The SAP adapter posts:
     - **Step 1:** movement 651 (GM code 01) for everything received, into blocked returns stock.
     - **Step 2:** movement 453 (GM code 04) for the RESTOCK units only.
   - Both are idempotent in SAP by XBLNR, so a repost or redelivery never double-posts.
   - The posting result is reported against `receiptTxnId`. If step 2 fails, the whole return is POSTING_FAILED and a repost re-runs both steps; step 1 is then a duplicate, so SAP does not post it again.

## Consequences

- `ReturnsIT` (inbound), `SapAdapterIT` (LR mapping and two-step posting) and `smoke-returns.sh` (the full chain on the real stack) cover it. The UI has a **Customer returns** page (receive, grade, close, repost) and an RMA card in the ERP simulator.
- Not built yet:
  - **Follow-up postings for non-restock dispositions:** SCRAP (551), RTV to the vendor, refurbish completion, QA release of quarantine. Those units stay blocked in the WMS and in SAP returns stock.
  - **Mapping SAP return reasons** to `returnReason` (the reason is `NOT_SPECIFIED` for now, an IF-RET-001 open point).
  - **Return tasks:** putaway tasks from the returns location to stock, and RF screens for returns. Receiving uses the web UI for now.
  - **E-commerce batching** of confirmations (RET002-R04) and the RPT-048 processing-time report.
  - **Regulated items** defaulting to quarantine (RET002-R03): this needs an item attribute.
