# H — Reporting & Analytics

---

## H.1 Reporting Architecture

| Tier | Technology | Latency | Use |
|---|---|---|---|
| Real-time operational | Stream processing on Kafka (materialised views in Redis/OLAP store) | ≤ 15 s | Control tower, dashboards, alerts |
| Operational reporting | Read replicas / reporting schema | Minutes | Shift reports, document lists, exception lists |
| Analytical | Lakehouse (CDC from OLTP + events) with semantic layer | ≤ 1 h (hourly), daily curated | KPIs, trends, management reports, ML features |
| Customer BI | Governed data share / connectors | Daily or hourly | Power BI, Tableau, SAP Analytics Cloud, Oracle Analytics |

Embedded reporting: a report designer for operational lists and documents, and embedded analytics (dashboard builder) with row-level security inherited from AstraWMS RBAC (site/owner scope).

---

## H.2 Operational Dashboards

Defined in §G.6. Each dashboard metric has a formal KPI definition (H.3/H.4) so that the same number appears in operational and management views.

---

## H.3 Inventory KPIs

| ID | KPI | Formula | Target (typical) | Grain | Pri |
|---|---|---|---|---|---|
| RPT-001 | Location inventory accuracy | Locations counted with no variance ÷ locations counted | ≥ 99.5% | Site/zone/day | M |
| RPT-002 | Unit accuracy (absolute) | 1 − Σ\|count − system\| ÷ Σ system qty (counted locations) | ≥ 99.8% | Site/owner/month | M |
| RPT-003 | Value accuracy (net) | 1 − \|Σ variance value\| ÷ Σ counted value | ≥ 99.9% | Site/month | M |
| RPT-004 | ERP–WMS reconciliation accuracy | Keys with zero unexplained variance ÷ total keys | 100% at period close | Site/day | M |
| RPT-005 | Cycle count compliance | Counts completed ÷ counts scheduled | ≥ 98% | Site/week | M |
| RPT-006 | Dock-to-stock time | Putaway confirm − unload scan (median, p90) | ≤ 4 h (p90) | Site/day/vendor | M |
| RPT-007 | Inventory turns | COGS ÷ avg inventory value (from ERP valuation) | Industry-specific | Owner/month | S |
| RPT-008 | Days on hand | On-hand qty ÷ avg daily demand | Item-specific | Item/week | S |
| RPT-009 | Location utilisation | Occupied capacity ÷ total capacity (by volume and by position) | 80–88% | Zone/day | M |
| RPT-010 | Expiry exposure | Value of stock expiring in ≤ 30/60/90 days | Trend ↓ | Owner/week | S |
| RPT-011 | Aged / slow-moving inventory | Qty/value with no movement > N days | Trend ↓ | Item/month | S |
| RPT-012 | Hold duration | Avg days on hold by reason | ≤ SLA per reason | Reason/week | S |
| RPT-013 | Shrink | Net negative adjustments (non-count reasons excluded) ÷ throughput value | ≤ 0.1% | Site/month | S |

## H.4 Labor KPIs

| ID | KPI | Formula | Grain | Pri |
|---|---|---|---|---|
| RPT-020 | Performance to standard | Earned standard hours ÷ actual direct hours | Operator/task type/shift | S |
| RPT-021 | Utilisation | Direct hours ÷ total paid hours | Operator/department/day | S |
| RPT-022 | Units / lines per hour (UPH / LPH) | Units (lines) ÷ direct hours | Process/zone/hour | M |
| RPT-023 | Indirect % | Indirect hours ÷ total hours | Department/week | S |
| RPT-024 | Unaccounted time | Paid hours − (direct + indirect) | Operator/day | S |
| RPT-025 | Travel % | Travel standard time ÷ total standard time | Process/zone | C |
| RPT-026 | Cost per unit / order / line | Labor cost ÷ volume | Owner/site/month | S |
| RPT-027 | Error rate per operator | Pick errors (from audits, weight checks, customer claims) ÷ lines picked | Operator/month | M |
| RPT-028 | Training curve compliance | New-hire performance vs ramp curve target | Operator/week | C |

## H.5 Operational / Service KPIs

| ID | KPI | Formula | Target | Pri |
|---|---|---|---|---|
| RPT-040 | On-time shipment | Shipments departed ≤ planned time ÷ total | ≥ 99% | M |
| RPT-041 | Order cycle time | Ship confirm − order receipt (median, p90) | By order type | M |
| RPT-042 | Perfect order rate | Orders on time, complete, damage-free, with correct documentation ÷ total | ≥ 98% | M |
| RPT-043 | Order/line fill rate | Shipped qty ÷ ordered qty | ≥ 99% | M |
| RPT-044 | Pick accuracy | 1 − (mis-picks ÷ lines picked) | ≥ 99.9% | M |
| RPT-045 | Short pick rate | Short-picked lines ÷ lines picked | ≤ 0.3% | M |
| RPT-046 | Dock door utilisation | Occupied door-hours ÷ available door-hours | 70–85% | S |
| RPT-047 | Carrier appointment compliance | On-time arrivals ÷ appointments | Carrier scorecard | S |
| RPT-048 | Returns processing time | Disposition − arrival | ≤ 48 h | S |
| RPT-049 | Integration timeliness | Confirmations posted ≤ 2 min ÷ total | ≥ 99% | M |

---

## H.6 Management Reports (Standard Catalogue)

| ID | Report | Frequency | Audience | Contents | Pri |
|---|---|---|---|---|---|
| RPT-060 | Daily Operations Summary | Daily (auto-email 06:00 local) | Site leadership | Volumes in/out, KPI vs target, exceptions, backlog carried, labor hours | M |
| RPT-061 | Weekly Performance Pack | Weekly | Ops director | KPI trends, top variances, customer SLA performance, labor productivity by process | S |
| RPT-062 | Inventory Accuracy & Adjustment Report | Weekly / monthly | Inventory manager, finance | Count results, adjustments by reason/user/value, reconciliation status | M |
| RPT-063 | Period-End Stock Reconciliation | Monthly (pre-close) | Finance controller | ERP vs WMS by plant/SLoc/material/batch with classification, sign-off | M |
| RPT-064 | Customer SLA Scorecard | Monthly | Account managers, clients | On-time, accuracy, fill rate, dock-to-stock, claims | M |
| RPT-065 | Vendor Compliance Scorecard | Monthly | Procurement | ASN accuracy, appointment compliance, label compliance, discrepancies | S |
| RPT-066 | Carrier Scorecard | Monthly | Transportation | On-time pickup, no-shows, damage claims | S |
| RPT-067 | Labor Productivity Report | Weekly | Labor manager, HR | Performance distribution, indirect breakdown, coaching actions | S |
| RPT-068 | Capacity & Space Report | Monthly | Network planning | Utilisation trends, forecasted capacity shortfall | C |
| RPT-069 | 3PL Billing Summary | Per billing cycle | Billing, clients | Billable events by service, storage, VAS, invoice reconciliation | M (3PL tenants) |
| RPT-070 | Compliance Reports | On demand | QA / regulatory | Recall trace, temperature excursion log, DG storage quantities, Part 11 audit review | M |

---

## H.7 Data Warehouse Integration

| Aspect | Design |
|---|---|
| Extraction | CDC (Debezium) from service databases + domain events to lakehouse bronze layer; no direct queries on OLTP by external BI |
| Modelling | Silver (conformed entities) → Gold (star schemas): `fact_inventory_snapshot_daily`, `fact_inventory_transaction`, `fact_order_line`, `fact_task`, `fact_labor_event`, `fact_shipment`, `fact_receipt_line`, `fact_billable_event`, `fact_sensor_reading_hourly`; conformed dimensions `dim_item`, `dim_location`, `dim_owner`, `dim_customer`, `dim_vendor`, `dim_carrier`, `dim_user`, `dim_date`, `dim_time`, `dim_site` (SCD Type 2 for item/location/user) |
| Semantic layer | Governed metric definitions (H.3–H.5) exposed via semantic layer (dbt Semantic Layer / Cube or equivalent) to guarantee consistent KPIs |
| Delivery to enterprise DWH | Scheduled exports (Parquet/Delta) to customer lake (S3/ADLS/GCS), Delta Sharing / Snowflake data share, or direct connectors to **SAP Datasphere / BW/4HANA** (via ODP-compatible extraction or file) and **Oracle Autonomous Data Warehouse / Fusion Analytics** |
| ERP join keys | All facts carry ERP keys (plant/org, SLoc/subinventory, material/item, batch/lot, delivery no., PO no.) for joint analytics with ERP financials |
| Data quality | Automated tests (row counts, referential integrity, freshness) per load; DQ dashboard |
| Security | Row-level security by owner/site replicated; PII columns tagged and masked by default |
| Retention | Transaction facts ≥ 7 years; daily snapshots ≥ 3 years; sensor data hourly aggregates ≥ product shelf life + 1 year |
