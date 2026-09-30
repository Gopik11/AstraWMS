# ISD-00 — Common Interface Conventions

This document defines the rules that apply to **every** AstraWMS ERP interface. Each Interface Specification Document (ISD) in this folder states only what is specific to its interface and refers here for everything else. Where an ISD deviates from these conventions, it says so explicitly in its *Processing Rules* section.

| Attribute | Value |
|---|---|
| Document | ISD-00 Common Interface Conventions |
| Version | 0.1 (Draft for design review) |
| Parent documents | [D — ERP Integration Scope](../scope/07-erp-integration.md), [E — NFRs](../scope/08-non-functional-requirements.md), [F — Architecture](../scope/09-system-architecture.md) |
| Status | Draft: field names marked *(confirm)* must be verified against the customer's ERP release during design |

---

## 1. ISD Structure

Every ISD contains the following sections, in this order:

| # | Section | Content |
|---|---|---|
| 0 | Header | ID, direction, systems, pattern, trigger, volume, SLA, priority, traced requirements |
| 1 | Purpose & Scope | Business purpose, in scope, out of scope |
| 2 | Process Flow | Sequence diagram with acknowledgements |
| 3 | Technical Realisation | Mechanism per ERP variant: SAP ECC, SAP S/4HANA, Oracle EBS, Oracle Fusion |
| 4 | Canonical Message | Field-level definition of the AstraWMS canonical payload |
| 5 | Field Mapping | Canonical ↔ SAP ↔ Oracle EBS ↔ Oracle Fusion |
| 6 | Processing Rules | Interface-specific business and technical rules |
| 7 | Error Handling | Interface-specific error codes, classes and resolution |
| 8 | Test Scenarios | Minimum test set for SIT (§I.2) |
| 9 | Open Points | Items to confirm during design |

---

## 2. Message Envelope

All canonical messages use the envelope defined in [§D.4.1](../scope/07-erp-integration.md#d41-canonical-message-envelope).

| Envelope Field | Type | Req | Rule |
|---|---|---|---|
| `messageId` | UUID v4 | M | Unique per message instance. Re-sends of the *same* business event reuse the same `messageId`. |
| `messageType` | string | M | Canonical message name, e.g. `ReceiptConfirmation` |
| `schemaVersion` | string `major.minor` | M | A consumer rejects a major version it does not support. Minor versions are additive only. |
| `sourceSystem` / `targetSystem` | string(30) | M | Logical system IDs, e.g. `SAP_S4_PRD_100` (SAP: `<SID>_<client>`), `EBS_PRD`, `FUSION_PRD`, `ASTRAWMS` |
| `tenantId`, `siteId`, `ownerId` | string(20) | M | `ownerId` is mandatory even for single-owner tenants (default owner) |
| `businessKey` | string(64) | M | The ordering key: ERP document number, item number, or other key named in each ISD |
| `correlationId` | string(64) | M | Links request, response and confirmation messages across the whole flow |
| `sequence` | integer | M | Monotonic per `businessKey` from the producing system; used for ordering (§5) |
| `createdAtUtc` | date-time | M | ISO-8601 UTC with milliseconds |

---

## 3. Data Type Conventions

| Canonical Type | Format | Notes |
|---|---|---|
| `string(n)` | UTF-8, max *n* characters | Trimmed; empty string is treated as null |
| `code` | Enumerated string | Values listed in the ISD; unknown values are rejected (class *Permanent technical*) |
| `decimal(p,s)` | JSON number | Quantities `decimal(15,3)`; weights `decimal(13,3)`; dimensions `decimal(13,3)` |
| `date` | `YYYY-MM-DD` | Business dates (expiry, manufacturing) carry no time zone |
| `date-time` | ISO-8601 UTC `YYYY-MM-DDThh:mm:ss.sssZ` | Adapters convert to ERP local time where the ERP expects it (SAP posting date = plant local date) |
| `boolean` | `true` / `false` | SAP `'X'`/`' '` and Oracle `'Y'`/`'N'` are converted in the adapter |
| `uom` | Code from the AstraWMS UoM catalogue | See §3.1 |

### 3.1 Units of Measure

- Canonical messages carry **ERP-neutral ISO codes** from the AstraWMS UoM catalogue, e.g. `EA`, `CS`, `PAL`, `KG`, `L`, `M`.
- Conversion happens in the adapter only:
  - **SAP:** internal unit (T006-MSEHI) ↔ ISO code (T006-ISOCODE). Units whose ISO code is ambiguous are mapped through an explicit adapter mapping table.
  - **Oracle EBS / Fusion:** `UOM_CODE` ↔ canonical code through the adapter mapping table.
- Quantities are always sent in the **UoM stated in the message**. Conversion to base UoM uses the item's conversion factors from IF-MD-001, never hard-coded factors.

### 3.2 Identifiers

| Identifier | Canonical Rule |
|---|---|
| Item | `itemNo` as in ERP, without leading-zero stripping. SAP MATNR is stored in external (conversion-exit) format. |
| Site | `siteId` = AstraWMS site code; mapped to SAP plant (WERKS) or Oracle organization code in the adapter site map |
| Stock bucket | Mapped to SAP storage location (LGORT) or Oracle subinventory through the bucket map (§D.2, *Plant/storage location mapping*) |
| ERP document reference | `erpDocNo` + `erpLineRef` kept exactly as the ERP sent them (SAP VBELN/POSNR with leading zeros) |
| WMS transaction ID | `wmsTxnId`: ≤ 16 characters, so that it fits SAP XBLNR/REF_DOC_NO for ERP-side duplicate checks (INT-014) |
| SSCC | 18 digits, no AI prefix, check digit validated |

---

## 4. Acknowledgements

| Level | Meaning | SAP | Oracle EBS | Oracle Fusion |
|---|---|---|---|---|
| **Technical ACK** | Message received and persisted | HTTP 2xx from Integration Suite / IDoc status 03 (sent) → 12 (dispatched) at sender | HTTP 2xx / XML Gateway transaction received | HTTP 2xx |
| **Application ACK** | Business processing outcome | IDoc status 53 (posted) / 51 (error) via `ALEAUD01`; BAPI `RETURN` table (type `E`/`A` = error); returned document number | Open interface row status (`PROCESS_FLAG`, `PROCESSING_STATUS_CODE`) and error tables; `CONFIRM_BOD` for XML Gateway | REST response body and status of the created resource; ESS job status for FBDI |

A technical ACK never marks a business flow as complete (INT-012). Every WMS→ERP message must reach a terminal application state (INT-011).

---

## 5. Idempotency and Ordering

| Rule | Specification |
|---|---|
| Inbound dedupe (ERP→WMS) | The inbox rejects duplicates by `(sourceSystem, messageId)`. SAP IDocs: the key is the IDoc number of the original, and re-processed IDocs keep their number. Oracle: the event key or `interface_id` with the last-update timestamp. |
| Outbound dedupe (WMS→ERP) | Idempotency key = `wmsTxnId`. Before any re-post, the adapter runs an ERP-side existence check: SAP looks for a material document or delivery status with `XBLNR = wmsTxnId`; Oracle checks the transaction reference / source line ID. |
| Stale message rule | For master data and documents, a message whose source change timestamp or `sequence` is **older** than the last applied version is acknowledged and discarded (logged as `STALE`). |
| Ordering | Kafka partition key = `businessKey`. Within a key, messages are processed strictly in `sequence` order. A gap in `sequence` holds the key for up to 5 minutes (configurable), then alerts. |
| Dependencies | A message that references an unknown master or document is **parked**. It is re-evaluated automatically when the dependency arrives, with an alert after 30 minutes (INT-002). |

---

## 6. Retry Policy (default)

| Error Class (§D.6.1) | Retry | Escalation |
|---|---|---|
| Transient technical | Exponential backoff with jitter: 1 s, 5 s, 30 s, 2 min, 5 min, then every 15 min | Alert when the oldest message is > 60 min old |
| Permanent technical | None | Immediate alert to integration on-call |
| Business: correctable | None automatically. Manual *reprocess* after correction. | Error queue; business owner notified |
| Business: conflict | None | Conflict workflow (both-system context) |
| Dependency (parked) | On dependency arrival, plus every 15 min | Alert after 30 min |

SAP lock errors (for example material locked `M3 897` or delivery being processed) and Oracle row-lock or "interface busy" conditions are classified as **Transient technical**.

A circuit breaker applies per ERP endpoint: it opens at 50% failure over 60 s and half-opens after 30 s.

---

## 7. Security Baseline

Security follows [§D.5](../scope/07-erp-integration.md#d5-security--audit-requirements-for-integration) (INT-020 – INT-027):

- TLS 1.2+ throughout; mTLS between the middleware and AstraWMS.
- OAuth 2.0 client credentials for REST/OData; X.509 for SOAP/IDoc-XML; SNC for direct RFC.
- The ERP technical user of each interface is restricted to the objects that interface needs. Each ISD lists the ERP authorisations required.
- PII fields (names, addresses, phone, e-mail) are flagged **PII** in the canonical definition. They are encrypted at rest and masked in the integration monitor for non-privileged roles.
- All payloads are retained with a SHA-256 hash (INT-025).

---

## 8. Monitoring Defaults

Each interface emits the following metrics to the integration monitor (§D.6.3), labelled with `interfaceId`, `siteId` and `targetSystem`:

| Metric | Default Alert |
|---|---|
| `messages_total{status}` | — |
| `latency_seconds` (event → application ACK), p95 | > the SLA in the ISD header for 15 min |
| `backlog` (unprocessed) | > 500 messages for 10 min |
| `oldest_unprocessed_age_seconds` | > 2 × the latency SLA |
| `errors_total{class}` | Any *Permanent technical*; > 10 *Business* errors in 15 min |
| `parked_total` | > 0 for 30 min |

---

## 9. Standard Test Categories

Every ISD test set covers at least the following categories. The ISD lists the interface-specific cases, which are traced in the RTM against the interface ID.

| Code | Category |
|---|---|
| POS | Positive: standard business case(s) |
| VAR | Variants: batch, serial, HU/LPN, UoM, multi-line, owner |
| NEG | Negative: validation failures produce the documented error code |
| DUP | Duplicate delivery of the same message has exactly one business effect |
| ORD | Out-of-order / stale messages are handled per §5 |
| OUT | Target system unavailable: store-and-forward, then drain with no loss |
| VOL | Volume at the design peak within the latency SLA |
| SEC | Wrong credentials or missing authorisation are rejected and audited |

---

## 10. ISD Versioning

- ISD version `0.x` = draft; `1.0` = signed off at the Blueprint gate (§I.1.2).
- Any change after sign-off increments the version and records the change in the ISD *Change Log*.
- A canonical schema major-version change requires a new ISD version and consumer impact analysis.
