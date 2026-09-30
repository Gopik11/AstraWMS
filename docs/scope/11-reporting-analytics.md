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

| KPI | Formula | Target (typical) | Grain |
|---|---|---|---|
| Location inventory accuracy | Locations counted with no variance ÷ locations counted | ≥ 99.5% | Site/zone/day |
| Unit accuracy (absolute) | 1 − Σ\|count − system\| ÷ Σ system qty (counted locations) | ≥ 99.8% | Site/owner/month |
| Value accuracy (net) | 1 − \|Σ variance value\| ÷ Σ counted value | ≥ 99.9% | Site/month |
| ERP–WMS reconciliation accuracy | Keys with zero unexplained variance ÷ total keys | 100% at period close | Site/day |
| Cycle count compliance | Counts completed ÷ counts scheduled | ≥ 98% | Site/week |
| Dock-to-stock time | Putaway confirm − unload scan (median, p90) | ≤ 4 h (p90) | Site/day/vendor |
| Inventory turns | COGS ÷ avg inventory value (from ERP valuation) | Industry-specific | Owner/month |
| Days on hand | On-hand qty ÷ avg daily demand | Item-specific | Item/week |
| Location utilisation | Occupied capacity ÷ total capacity (by volume and by position) | 80–88% | Zone/day |
| Expiry exposure | Value of stock expiring in ≤ 30/60/90 days | Trend ↓ | Owner/week |
| Aged / slow-moving inventory | Qty/value with no movement > N days | Trend ↓ | Item/month |
| Hold duration | Avg days on hold by reason | ≤ SLA per reason | Reason/week |
| Shrink | Net negative adjustments (non-count reasons excluded) ÷ throughput value | ≤ 0.1% | Site/month |

## H.4 Labor KPIs

| KPI | Formula | Grain |
|---|---|---|
| Performance to standard | Earned standard hours ÷ actual direct hours | Operator/task type/shift |
| Utilisation | Direct hours ÷ total paid hours | Operator/department/day |
| Units / lines per hour (UPH / LPH) | Units (lines) ÷ direct hours | Process/zone/hour |
| Indirect % | Indirect hours ÷ total hours | Department/week |
| Unaccounted time | Paid hours − (direct + indirect) | Operator/day |
| Travel % | Travel standard time ÷ total standard time | Process/zone |
| Cost per unit / order / line | Labor cost ÷ volume | Owner/site/month |
| Error rate per operator | Pick errors (from audits, weight checks, customer claims) ÷ lines picked | Operator/month |
| Training curve compliance | New-hire performance vs ramp curve target | Operator/week |

## H.5 Operational / Service KPIs

| KPI | Formula | Target |
|---|---|---|
| On-time shipment | Shipments departed ≤ planned time ÷ total | ≥ 99% |
| Order cycle time | Ship confirm − order receipt (median, p90) | By order type |
| Perfect order rate | Orders on time, complete, damage-free, with correct documentation ÷ total | ≥ 98% |
| Order/line fill rate | Shipped qty ÷ ordered qty | ≥ 99% |
| Pick accuracy | 1 − (mis-picks ÷ lines picked) | ≥ 99.9% |
| Short pick rate | Short-picked lines ÷ lines picked | ≤ 0.3% |
| Dock door utilisation | Occupied door-hours ÷ available door-hours | 70–85% |
| Carrier appointment compliance | On-time arrivals ÷ appointments | Carrier scorecard |
| Returns processing time | Disposition − arrival | ≤ 48 h |
| Integration timeliness | Confirmations posted ≤ 2 min ÷ total | ≥ 99% |

---

## H.6 Management Reports (Standard Catalogue)

| Report | Frequency | Audience | Contents |
|---|---|---|---|
| Daily Operations Summary | Daily (auto-email 06:00 local) | Site leadership | Volumes in/out, KPI vs target, exceptions, backlog carried, labor hours |
| Weekly Performance Pack | Weekly | Ops director | KPI trends, top variances, customer SLA performance, labor productivity by process |
| Inventory Accuracy & Adjustment Report | Weekly / monthly | Inventory manager, finance | Count results, adjustments by reason/user/value, reconciliation status |
| Period-End Stock Reconciliation | Monthly (pre-close) | Finance controller | ERP vs WMS by plant/SLoc/material/batch with classification, sign-off |
| Customer SLA Scorecard | Monthly | Account managers, clients | On-time, accuracy, fill rate, dock-to-stock, claims |
| Vendor Compliance Scorecard | Monthly | Procurement | ASN accuracy, appointment compliance, label compliance, discrepancies |
| Carrier Scorecard | Monthly | Transportation | On-time pickup, no-shows, damage claims |
| Labor Productivity Report | Weekly | Labor manager, HR | Performance distribution, indirect breakdown, coaching actions |
| Capacity & Space Report | Monthly | Network planning | Utilisation trends, forecasted capacity shortfall |
| 3PL Billing Summary | Per billing cycle | Billing, clients | Billable events by service, storage, VAS, invoice reconciliation |
| Compliance Reports | On demand | QA / regulatory | Recall trace, temperature excursion log, DG storage quantities, Part 11 audit review |

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
