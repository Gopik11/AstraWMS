# ADR-0006: ERP Adapter Architecture — Mapping in the Adapter, a Gateway Boundary and a Simulated ERP

- **Status:** Accepted
- **Date:** 2026-09-30

## Context

Scope §D.1 places ERP-specific mapping in adapters that translate to and from the canonical model. SAP test systems are expensive and slow to obtain, yet the mapping rules in the ISDs (DELVRY07 → ReceiptExpectation, confirmation → BAPI, movement catalogue) are the riskiest integration logic, and they need to be tested from day one.

## Decision

1. **One adapter per ERP family** (`adapters/sap-adapter`, later `oracle-*`). Domain services only ever see canonical messages (`IntegrationContracts`).
2. **Mapping is pure code** (`DelvryMapper`, `BapiMapper`, `SapCodes`) that follows the ISD field tables and is unit-tested without Spring.
   - Adapter boundary normalisations are explicit. Examples: SSCC with AI `00` → 18 digits; SAP units ↔ canonical UoM; plant-local posting dates.
3. **`SapGateway` is the only boundary to SAP.** Implementations:

   | Implementation | Use |
   |---|---|
   | `MockSapGateway` | dev/test. A simulated SAP backend with material documents, the XBLNR duplicate check, and injectable faults (`PERIOD_CLOSED`, `BATCH_MISSING`, `LOCKED`). |
   | JCo/RFC gateway | real systems (future release) |

   The configuration switch is `astra.sap.gateway`. Gateway contract:
   - check for duplicates by WMS transaction ID before posting (INT-014);
   - throw only for transient conditions;
   - return business errors as BAPIRET2-style messages.
4. **Every WMS→ERP message ends in an `ErpPostingResult`** (success with the document, or a failure classified per §D.6.1). The one exception is transient failures, which roll back the inbox record and are redelivered by the Kafka error handler with exponential backoff (1 s → 30 s, 5 min).
5. **ERP→WMS documents are acknowledged** with an `ApplicationAck` from the owning domain service. The adapter turns it into the ERP-side status: IDoc 53/51, which is the ALEAUD equivalent.
6. Adapter messages carry the ERP logical system as `sourceSystem` (e.g. `SAP_S4_DEV_100`, ISD-00 §2).

## Consequences

- The complete inbound flow runs locally and in CI without SAP (`scripts/smoke-inbound.sh`). That includes failures and reposts.
- The mock only proves the adapter's side of the contract. Real-system behaviour must be confirmed in SIT against a customer sandbox. Examples: exact BAPI field names, batch-split numbering, period-close messages. These are tracked as *(confirm)* open points in the ISDs.
- Retries that are exhausted are logged and skipped until the dead-letter topic is added. Reconciliation (IF-INV-003) detects any resulting unposted confirmation.
