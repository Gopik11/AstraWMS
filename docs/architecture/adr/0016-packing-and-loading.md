# ADR-0016: Packing with SSCC Cartons and Carrier Labels; Loads Closed into Shipments

- **Status:** Accepted
- **Date:** 2026-10-01

## Context

Outbound previously ended at "picked, then ship". §5 requires:
- **Packing:** scan-to-verify packing (SHP-001), GS1 SSCC cartons, carrier labels and tracking (§5.2).
- **Loading:** orders can't be loaded unpacked (SHP-002), no cross-loading (SHP-003), trailer close with seal and BOL as the ship-confirm moment (§5.3).

## Decision

1. **Cartons are packing records in the outbound service** (`carton`, `carton_item`). Stock stays on the order's pick LPN at outbound staging until ship.
   - Packing never moves allocated stock between LPNs. The inventory ledger stays one PICK and one ISSUE per allocation.
   - Each carton gets a GS1 SSCC: extension digit + company prefix (`astra.outbound.gs1-company-prefix`) + serial from a database sequence + mod-10 check digit.
2. **Scan-to-verify:** packed quantity per line can never exceed picked minus already packed (`OUT_PACK_EXCEEDS_PICKED`). An empty carton cannot be closed.
3. **Close carton:** the weight is recorded, and a carrier label and tracking number come from the `CarrierGateway` boundary.
   - The simulated carrier returns UPS-style tracking numbers for UPSN and a ZPL label with the tracking and SSCC barcodes, like the simulated SAP backend.
   - The label is stored with the carton; the UI shows it and its ZPL.
4. **Per-site `packRequired`** (`PUT /outbound/config`, `SOLUTION_ADMIN`). When on, an order ships or loads only when everything picked is in closed cartons (`OUT_NOT_PACKED`, SHP-002). It is off by default, so waveless sites without packing keep working.
5. **Loads (trailers):** a load has a carrier, door and trailer.
   - Orders are loaded by delivery number or by scanning one of their cartons (SSCC with or without the `(00)` identifier).
   - An order for another carrier is refused (`OUT_CROSS_LOAD`, SHP-003).
   - Closing the load records the seal, generates the BOL and ships every order on it. The goods issue to the ERP (IF-OB-003) carries the BOL and the first carton's tracking number.

## Consequences

- `smoke-packing.sh` runs the full chain on the real stack: ship refused while unpacked, SSCC carton with label, cross-load refused, trailer closed with seal, goods issue posted in simulated SAP.
- A real multi-carrier platform (rate shopping, manifest close SHP-004, label API failover SHP-EX-02) plugs in behind `CarrierGateway`.
- Not built yet:
  - **Cartonization** (§C.5) and weight verification against master data weights (SHP-EX-01); the weight is recorded, not compared.
  - **Packing documents** (packing slip, commercial invoice, DG documents, SHP-006) and RFID.
  - **Shipping pallets, load planning** (stop sequence, axle weight), split loads (SHP-EX-04) and delivery splits (IF-OB-004).
  - **Handling units in the ERP confirmation:** SSCCs are not yet sent as handling units in the goods issue.
