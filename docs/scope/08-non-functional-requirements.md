# E — Non-Functional Requirements

All NFRs apply per tenant unless stated otherwise. The **reference design load** is:

| Parameter | Reference Load (per large site) | Platform Ceiling (per tenant) |
|---|---|---|
| Concurrent RF / mobile users | 800 | 10,000 |
| Concurrent web users | 150 | 2,000 |
| Order lines shipped / day | 500,000 (peak day 1.2 M) | 10 M |
| Inventory records (LPN × item × lot) | 5 M | 200 M |
| Locations | 150,000 | 3 M |
| Tasks created / hour | 120,000 | 2 M |
| Sites | 1 | 200 |
| Automation telegrams / s | 1,500 | 20,000 |

---

## E.1 Scalability

| ID | Requirement | Pri |
|---|---|---|
| NFR-001 | Horizontal scaling of stateless services (API, task, allocation, integration) via container orchestration autoscaling on CPU, queue depth, and p95 latency. | M |
| NFR-002 | Data partitioned by `tenant_id` + `site_id`; large tenants can be placed on dedicated database clusters (cell-based architecture) without code change. | M |
| NFR-003 | Adding a site requires no downtime and no schema change. | M |
| NFR-004 | Linear throughput scaling to 3× reference load demonstrated in performance test with ≤ 10% p95 degradation. | M |
| NFR-005 | Peak handling: 4× average hourly volume sustained for 4 h (e.g., Black Friday), pre-scaling via scheduled capacity profiles. | M |

## E.2 Performance SLAs

| Transaction | Metric | Target |
|---|---|---|
| RF screen transition (scan → next prompt) | Server response p95 / p99 | ≤ 250 ms / ≤ 500 ms |
| Task request (get next task, incl. interleaving) | p95 | ≤ 300 ms |
| Pick confirmation | p95 | ≤ 300 ms |
| Web UI page load (standard list, 50 rows) | p95 | ≤ 1.5 s |
| Inventory inquiry (by item, all locations) | p95 | ≤ 1 s |
| Wave release (10k orders / 50k lines) | Completion | ≤ 60 s |
| Allocation (single order, 20 lines) | p95 | ≤ 200 ms |
| Cartonization (multi-item) | p95 | ≤ 50 ms |
| Label print job dispatched to printer | p95 | ≤ 1 s |
| Sorter routing decision | p99 | ≤ 100 ms |
| Dashboard refresh (operational) | Data freshness | ≤ 15 s |
| Report (operational, 1 day, 1 site) | Completion | ≤ 10 s |
| Integration: ERP delivery → available in WMS pool | p95 | ≤ 60 s |

Measurement is server-side at the API gateway, excluding network and device time. Performance targets are validated in performance testing at reference load (§I.2) and monitored in production through SLOs with error budgets.

## E.3 High Availability & Disaster Recovery

| ID | Requirement | Target |
|---|---|---|
| NFR-020 | Service availability (monthly, excluding agreed maintenance) | **99.95%** (≤ 22 min/month) |
| NFR-021 | Planned maintenance | Zero-downtime deployments (rolling / blue-green); schema migrations backward-compatible (expand/contract) |
| NFR-022 | Multi-AZ deployment of all tiers | 3 availability zones; synchronous DB replication within region |
| NFR-023 | **RPO** (regional disaster) | ≤ 5 min (asynchronous cross-region replication) |
| NFR-024 | **RTO** (regional disaster) | ≤ 1 h (warm standby region; runbook-automated failover) |
| NFR-025 | DR testing | Full failover test at least twice per year with evidence report |
| NFR-026 | Backups | Continuous PITR (35 days); daily snapshot copied cross-region; monthly immutable backup retained 12 months (WORM) |
| NFR-027 | **Site resilience (edge)** | Optional *site edge node* for automation-heavy sites: local continuation of sorter routing & automation orchestration for ≥ 4 h of WAN loss, sync on reconnect |
| NFR-028 | RF device offline mode | Limited offline continuation for putaway/pick confirmations for ≤ 15 min with queued sync & conflict detection |
| NFR-029 | ERP outage | Warehouse continues operating indefinitely on already-received orders; confirmations queued (INT-010) |
| NFR-030 | Degraded modes | Documented playbooks: carrier API down (pre-printed label pools / later manifest), print server down (alternate printer routing), WCS down (manual task conversion) |

## E.4 Security & Compliance

| ID | Area | Requirement | Pri |
|---|---|---|---|
| NFR-100 | Identity | SSO via SAML 2.0 / OIDC with customer IdP (Entra ID, Okta, Ping); SCIM 2.0 user provisioning; MFA enforced for web; RF login via badge scan + PIN, backed by IdP-issued device session | M |
| NFR-101 | Authorisation | RBAC with attribute-based constraints (site, owner, zone, function) (§G.5); least privilege; segregation-of-duties rules enforced (e.g., count vs approve) | M |
| NFR-102 | Encryption | TLS 1.2+ in transit; AES-256 at rest; customer-managed keys (BYOK/HYOK via cloud KMS) option | M |
| NFR-103 | Data isolation | Tenant isolation at the data layer (row-level security + tenant-scoped encryption keys); dedicated-cell option | M |
| NFR-104 | Application security | OWASP ASVS Level 2; SAST/DAST/SCA in CI; SBOM (CycloneDX) per release; critical vulnerabilities remediated ≤ 7 days, high ≤ 30 days; annual third-party penetration test | M |
| NFR-105 | Certifications | SOC 2 Type II, ISO/IEC 27001 (and 27017/27018); GDPR compliance with DPA; data residency per region (EU, US, APAC) | M |
| NFR-106 | Privacy | PII minimisation (ship-to data only as needed), retention & deletion policies, subject access/erasure support for consignee data, pseudonymised labor analytics option | M |
| NFR-107 | Regulated industries | 21 CFR Part 11 / EU Annex 11 support: validated release process, audit trail, e-signatures, validation package (IQ/OQ scripts, traceability matrix) delivered per release | M (regulated tenants) |
| NFR-108 | Devices | MDM-managed RF devices (Intune / SOTI / Zebra StageNow); kiosk mode; certificate-based Wi-Fi (WPA2/3-Enterprise EAP-TLS) | M |
| NFR-109 | Logging for security | Security events to customer SIEM (syslog / CEF / API); login failures, privilege changes, data exports | M |

## E.5 Configurability & Extensibility

| ID | Requirement | Pri |
|---|---|---|
| NFR-040 | Business rules (allocation, putaway, replenishment, wave templates, cartonization constraints, disposition, tolerances) are configured through UI/rule tables, not code. | M |
| NFR-041 | Configuration is versioned, promotable between environments (export/import as signed packages), and diff-able; effective-dated changes. | M |
| NFR-042 | Custom attributes (user-defined fields) on item, LPN, inventory, order, order line, location, with validation and UI/reporting exposure, without schema change by the customer. | M |
| NFR-043 | RF workflow configuration: step order, optional/mandatory captures, prompts, and validations adjustable per site/owner/process through a flow designer. | S |
| NFR-044 | Extension points: (a) synchronous hooks (pre-/post-validation) as sandboxed serverless functions with ≤ 50 ms budget; (b) asynchronous event subscriptions; (c) custom REST endpoints under extension namespace. Extensions do not modify core code and survive upgrades. | S |
| NFR-045 | Label and document templates editable (ZPL / designer; HTML-to-PDF for documents), versioned per owner/customer. | M |
| NFR-046 | Localisation: UI in ≥ 12 languages, per-user language on RF; locale-aware numbers/dates/UoM; right-to-left capable. | S |
| NFR-047 | Upgrade cadence: continuous delivery with monthly feature releases; feature flags for opt-in behaviour changes; regulated tenants on quarterly validated release train. | M |

## E.6 API Governance

| ID | Aspect | Standard | Pri |
|---|---|---|---|
| NFR-120 | API style | REST (JSON) with OpenAPI 3.1 specs; async events documented with AsyncAPI 2.x/3.x; GraphQL read API optional for UI aggregation | M |
| NFR-121 | Versioning | URI major version (`/v1/`); additive changes non-breaking; breaking changes only in new major; deprecation notice ≥ 12 months; `Sunset` headers | M |
| NFR-122 | Naming & design | Resource-oriented, plural nouns, consistent error model (RFC 9457 Problem Details), pagination (cursor-based), filtering, sparse fieldsets | S |
| NFR-123 | Idempotency | `Idempotency-Key` header required on all POST that create transactions; 24-h dedupe window | M |
| NFR-124 | Concurrency | Optimistic concurrency via `ETag`/`If-Match` on mutable resources | M |
| NFR-125 | Rate limiting | Per client & tenant; 429 with `Retry-After`; burst and sustained quotas published | M |
| NFR-126 | Security | OAuth 2.0 (client credentials, auth code + PKCE for UIs); scopes per domain; mTLS for B2B | M |
| NFR-127 | Lifecycle | API review board: design review, linting (Spectral rules), contract tests (consumer-driven with Pact), changelog | S |
| NFR-128 | Developer experience | Developer portal, sandbox tenant, SDKs (Java, TypeScript, Python, C#), Postman collections, webhooks with signed payloads (HMAC-SHA256) and replay | S |
| NFR-129 | Observability | Every request carries W3C `traceparent`; correlation ID propagated to ERP messages | M |

## E.7 Logging & Audit Trails

| ID | Requirement | Pri |
|---|---|---|
| NFR-060 | **Business audit trail**: every create/update/delete on master data, configuration, inventory, and orders records who, when (UTC + site local), from where (device/IP), what (before/after values), why (reason code), and via which channel (UI/RF/API/integration/job). | M |
| NFR-061 | Audit trail is append-only and tamper-evident (hash-chained records per partition; periodic anchor hash stored in WORM storage). | M |
| NFR-062 | Retention: inventory & financial-relevant audit ≥ 7 years (configurable up to 15); operational technical logs 90 days hot, 1 year archive. | M |
| NFR-063 | Audit query UI and export (CSV/PDF) with filters; regulated tenants: audit review report for Part 11. | M |
| NFR-064 | Technical logging: structured JSON logs, OpenTelemetry traces & metrics, centralised (customer-exportable); PII redaction in logs. | M |
| NFR-065 | Configuration change log with diff and approver; emergency changes flagged. | M |
| NFR-066 | Clock synchronisation: all services NTP-synchronised; timestamps in UTC ISO-8601 with ms precision. | M |

## E.8 Usability & Operability

| ID | Requirement | Pri |
|---|---|---|
| NFR-080 | RF task flows achievable with ≤ 1 keystroke per step on average (scan-driven); new operator productive after ≤ 4 h training for core tasks. | S |
| NFR-081 | Accessibility: web UI WCAG 2.2 AA. | S |
| NFR-082 | Operability: runbooks for all alerts; SLO dashboards; synthetic transaction monitoring per site (RF login, task fetch, confirm). | M |
