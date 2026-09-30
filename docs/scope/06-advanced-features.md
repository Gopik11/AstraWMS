# C — Advanced Features

---

## C.1 Slotting & Optimisation Engine

### Purpose
The engine assigns items to forward-pick locations and storage zones so that total travel, replenishment frequency, and ergonomic cost are minimised while physical and compliance constraints are respected.

### Inputs

| Input | Source |
|---|---|
| Item cube, weight, dims per UoM, stackability, crush class | Item warehouse profile (Cubiscan-measured) |
| Pick velocity (lines/day), unit velocity, cube velocity | Order history (rolling 13 weeks, seasonality-weighted) + forecast (§C.8) |
| Item affinity (co-occurrence in orders) | Market-basket analysis (lift ≥ threshold) |
| Location attributes | Location master: type, dims, golden-zone flag, coordinates, ergonomic score |
| Constraints | Temperature, HazMat, security, family grouping (e.g., keep sizes of an apparel style together), retailer aisle sequence |

### Algorithm

1. **Classification:** ABC by pick lines (primary), XYZ by demand variability (coefficient of variation).
2. **Location scoring:** `score(location) = w1·travel_distance_to_depot + w2·ergonomic_penalty(level) + w3·equipment_cost`.
3. **Assignment:** a mixed-integer or assignment model (Hungarian / min-cost flow for one-item-per-location; MIP with CP-SAT for family and affinity constraints). The objective minimises Σ(velocity × location_score) + λ·replenishment_cost + μ·move_cost_from_current_slot.
4. **Sizing:** the forward-location capacity target is `days_of_cover × daily_unit_demand`, rounded to case/pallet, which drives min/max (RPL-005).
5. **Move plan:** re-slot moves are ranked by *benefit per move*; only moves whose annualised benefit is greater than the move labor cost are proposed. Moves are executed as swap/chain move tasks during low-activity windows, respecting the configurable maximum moves per night.

### Outputs & Governance
- Slotting proposals are presented for analyst approval, as a what-if comparison with KPI deltas for travel metres and replen/day.
- Approved moves produce interleavable relocation tasks.
- Seasonal re-slot scenarios can be run through the Digital Twin (§C.9).

| ID | Requirement | Pri |
|---|---|---|
| ADV-001 | Weekly automated slotting recommendations with approval workflow | S |
| ADV-002 | Constraint-aware (hard constraints never violated) | M |
| ADV-003 | Move plan executable as system tasks with before/after KPI tracking | S |

---

## C.2 Labor Management System (LMS)

### Engineered Labor Standards
Standards are built from **discrete elements** in a MOST/MTM-style approach, not from historical averages:

`Standard time = Σ(element_time × frequency) + travel_time(distance, equipment) + PF&D allowance`

| Element Examples | Basis |
|---|---|
| Get/place per unit by weight class | Predetermined time |
| Scan (location, item, LPN) | Measured seconds |
| Travel horizontal | Distance from location coordinates ÷ equipment speed profile, with acceleration/deceleration & turns |
| Travel vertical (lift) | Level height ÷ lift speed |
| Pallet wrap, label apply | Fixed element |
| PF&D allowance | Personal, fatigue & delay: 10–15% configurable; freezer-specific allowances |

### Functions

| Function | Description |
|---|---|
| Real-time performance | Earned hours ÷ actual direct hours per operator per task type, updated per task confirmation |
| Indirect time | Clock-in/out of indirect activities (meetings, training, cleaning, battery change) with codes |
| Unaccounted time detection | Gaps between tasks > threshold flagged to supervisor in real time |
| Incentive support | Performance export to payroll/incentive systems; performance thresholds by tenure (learning curve) |
| Engineered goals for planning | Labor demand forecast by hour = Σ(planned work × standard) → staffing plan |
| Coaching | Operator scorecards; supervisor coaching log |
| Timekeeping integration | Import punches from T&A (Kronos/UKG, Workday); export labor hours by cost centre |

| ID | Requirement | Pri |
|---|---|---|
| ADV-010 | Discrete engineered standards for all RF task types | S |
| ADV-011 | Real-time operator performance dashboard | S |
| ADV-012 | Labor planning by shift/hour from backlog and forecast | S |
| ADV-013 | Works council / privacy configuration: aggregation-only mode where individual tracking is legally restricted (e.g., DE, FR) | M |

---

## C.3 Task Interleaving Algorithms

### Concept
A single operator on a piece of equipment performs mixed task types (putaway, replenishment, pick, count, relocation) in one sequence. This reduces deadhead (empty) travel.

### Algorithm

The task-selection service runs when an operator requests work:

1. **Candidate set**: tasks where `required_equipment ∈ operator.equipment`, `zone ∈ operator.authorised_zones`, operator certifications satisfy task requirements, and status = RELEASED.
2. **Scoring** for each candidate `t`:

   `score(t) = P(t)·w_p + U(t)·w_u − D(op, t)·w_d − E(t)·w_e + C(prev, t)·w_c`

   | Term | Meaning |
   |---|---|
   | P(t) | Base priority (1–99) |
   | U(t) | Urgency = f(time to deadline): exponential ramp as the cut-off approaches |
   | D(op, t) | Deadhead travel from the operator's current position to the task source |
   | E(t) | Effort (standard time) |
   | C(prev, t) | Chaining bonus: task source near the previous task destination (e.g., a putaway drop near a replen pick) |

3. **Dual-cycle chaining**: after a putaway to a reserve location, the next task is preferred when it starts within radius R of that drop, typically a replenishment or full-pallet pick heading back toward the dock or forward area.
4. **Starvation guard**: any task older than its aging threshold receives an increasing age bonus so that it is guaranteed to be selected.
5. **Hard preemption**: emergency replenishment and hot picks can interrupt at the end of the current task.

The weights are configurable per site and shift and can be tuned through the Digital Twin.

| ID | Requirement | Pri |
|---|---|---|
| ADV-020 | Configurable interleaving by equipment class and zone | S |
| ADV-021 | Task selection latency ≤ 300 ms p95 with ≥ 50,000 open tasks | M |

---

## C.4 Wave Planning Engine

### Modes

| Mode | Description | Use |
|---|---|---|
| Wave (batch release) | Orders grouped by criteria and released together at planned times | Carrier-driven B2B, store replenishment |
| Waveless (continuous flow) | Orders released continuously based on capacity & priority | High-volume e-commerce |
| Hybrid | Waveless within guardrails (e.g., waves per carrier cut-off with continuous trickle) | Omni-channel |

### Wave Template
Selection criteria (carrier, route, ship date, order type, priority, zone, owner, cut-off window), size limits (orders, lines, units, cube, labor minutes), and release rules (auto at time T / manual / on threshold).

### Release Process
1. **Selection**: build a candidate set from the pool according to the template.
2. **Allocation**: hard-allocate, reporting shortages.
3. **Replenishment calculation**: determine the demand-driven replenishment needed.
4. **Cartonization** (§C.5).
5. **Work creation**: group picks into work units by pick method (batch/cluster/zone) using clustering. Orders are grouped by location similarity (Jaccard on zone set) to maximise pick density.
6. **Capacity check**: labor (LMS standards × available operators), pack stations, VAS, sorter capacity, and staging lanes. The system suggests reducing the wave or re-timing it if capacity is exceeded.
7. **Release**: tasks become visible to operators/automation. Replenishments are released first, with the lead time applied.

### Waveless Control Loop
Every N seconds (default 60) the release controller computes:
- work-in-process (WIP) per downstream resource (pick zones, pack, sorter),
- target WIP levels (Little's Law: WIP = throughput × cycle time).

It then releases the highest-priority pool orders until the WIP targets are reached, which smooths flow and prevents pack-station starvation or congestion.

| ID | Requirement | Pri |
|---|---|---|
| ADV-030 | Wave templates with simulation ("plan wave" preview without release) | M |
| ADV-031 | Waveless release with WIP-based throttling | S |
| ADV-032 | Wave release of 10,000 orders / 50,000 lines completes ≤ 60 s | M |

---

## C.5 Cartonization Engine

### Purpose
Determines the optimal carton type(s) and the item-to-carton assignment *before picking*. This enables pick-to-carton, accurate freight quotes, and reduced dimensional-weight charges.

### Algorithm
- A 3D bin-packing heuristic: **extreme-point / first-fit-decreasing by volume**, with orientation constraints (this-side-up, no-rotate items) and a rotation search over 6 orientations for rotatable items.
- Constraints: max carton weight, fragile-on-top, liquids not above electronics, DG segregation within a package, temperature pack-out, customer-specific carton lists, and item "ships in own container" (SIOC).
- Objective: minimise `Σ(carton cost + dim-weight freight cost estimate + dunnage cost)`, not only the number of cartons.
- Fill-rate guard: a carton is chosen only if its fill ≥ the minimum fill % (void-fill cost otherwise).
- Performance mode: a precomputed "best carton by cube" lookup is used for single-unit orders (≈ 60–70% of B2C), and the 3D search only for multi-item orders.

### Feedback Loop
Packer overrides and weight-check variances feed back to the item dimension quality score. Items with repeated overrides are flagged for re-measurement.

| ID | Requirement | Pri |
|---|---|---|
| ADV-040 | Cartonization at release with pick-to-carton support | S |
| ADV-041 | Carton recommendation ≤ 50 ms p95 per multi-item order | S |
| ADV-042 | Carton catalogue per site/owner with cost and dim-weight divisors per carrier | S |

---

## C.6 Automation & Robotics Integration (AMR / AGV / ASRS)

### Integration Layer: Automation Orchestration Service (AOS)

AstraWMS does not control PLCs directly. It integrates with vendor **WCS/WES/fleet managers** through a normalised **Automation Orchestration Service** (see §F.7).

| Equipment | Integration Pattern | Typical Messages |
|---|---|---|
| ASRS (pallet/mini-load/shuttle, cube storage) | WCS API (REST/TCP-socket/MQTT) | Store request, retrieve request, location/inventory sync, task complete, fault |
| AMR (goods-to-person shelf, collaborative pick assist) | Fleet manager API; VDA 5050 where supported | Mission create, mission status, robot position, station arrival |
| AGV / LGV (pallet) | Fleet controller; VDA 5050 (MQTT, JSON) | Transport order (from/to), status, battery, error |
| Conveyor & sorter | WCS (TCP socket telegrams or MQTT) | Induct scan, routing decision request/response (≤ 100 ms), divert confirm, no-read |
| Pick-to-light / put-wall | Controller API | Light on/off with qty display, button confirm |
| Print & apply, auto-bagger, carton erector | WCS | Label data, completion |
| Robotic piece picking | Robot cell controller | Pick request, grasp success/failure, exception to human |

### Key Design Rules
1. **Inventory ownership**: AstraWMS owns inventory at *automation-zone* level (e.g., "ASRS-1"). The WCS owns internal slot positions. A nightly (and on-demand) reconciliation compares WMS inventory by LPN to the WCS slot map.
2. **Idempotent commands**: every command carries a unique `command_id`, and the WCS acknowledges with the same ID. Replays are safe.
3. **Sub-second routing decisions**: sorter divert requests are answered from a pre-computed routing cache pushed to the AOS at release, so no synchronous database call happens per telegram.
4. **Fault handling**: a mission failure returns a reason code. The AOS retries, re-routes, or converts the work to a manual task, and the task is re-assigned to RF users.
5. **Throughput throttling**: the AOS exposes station/robot capacity to the waveless controller (§C.4).

| ID | Requirement | Pri |
|---|---|---|
| ADV-050 | Vendor-neutral automation adaptor framework; new vendor adaptor ≤ 8 weeks | S |
| ADV-051 | VDA 5050 v2 support for AGV/AMR | C |
| ADV-052 | Sorter routing response ≤ 100 ms p99 | M (if sorter in scope) |
| ADV-053 | Automation inventory reconciliation with discrepancy workflow | M |

---

## C.7 IoT Sensor Integration

| Sensor Type | Use | Protocol |
|---|---|---|
| Temperature / humidity | Cold chain monitoring, excursion detection | MQTT / LoRaWAN via gateway |
| Door / dock sensors | Door open duration, trailer presence | MQTT |
| RFID portals & handhelds (UHF, EPC Gen2) | Receipt verification, load verification, asset tracking | LLRP; reader middleware |
| BLE / UWB RTLS | Forklift and asset location, travel analytics, geofencing | Vendor RTLS API |
| Weight sensors / smart shelves | Real-time consumption, VAS consumables | MQTT |
| Forklift telematics | Utilisation, impacts, operator login (license validation) | Vendor API |
| Energy meters | Energy per zone (sustainability reporting) | Modbus → gateway → MQTT |

**Architecture:** Edge gateway → MQTT broker (TLS, per-device X.509 certificates) → IoT ingestion service → time-series store (downsampled after 90 days) → rules engine (thresholds, rate-of-change, duration) → WMS events (e.g., `ZoneTemperatureExcursion`) → automatic holds (§14).

| ID | Requirement | Pri |
|---|---|---|
| ADV-060 | Ingest ≥ 5,000 sensor readings/s per tenant | S |
| ADV-061 | Rule evaluation to WMS event latency ≤ 10 s | S |
| ADV-062 | Device identity and certificate rotation | M |

---

## C.8 AI-Driven Forecasting & Predictive Analytics

| Model | Purpose | Features | Technique |
|---|---|---|---|
| Inbound volume forecast | Dock and labor planning | ASN pipeline, PO due dates, vendor lateness history, seasonality | Gradient boosting (LightGBM) + ASN pipeline convolution |
| Outbound order forecast (hourly) | Labor planning, waveless targets, predictive replen | Order history, promotions calendar, day-of-week, holidays, weather (opt.) | Hierarchical time-series (site → channel → hour); temporal fusion transformer or gradient boosting with lag features |
| SKU-level demand (short-horizon) | Predictive replenishment, slotting | SKU history, ERP forecast (if provided), promotions | Gradient boosting; Croston/TSB for intermittent demand |
| Task duration prediction | Better than static standards for planning | Operator, equipment, location, time of day, congestion | Gradient boosting regression |
| Late-shipment risk | Proactive intervention on orders | Order backlog, WIP, labor on floor, historical cycle times | Classification with calibrated probability |
| Anomaly detection | Shrink, fraud, mis-picks, abnormal adjustments | Adjustment patterns by user/location/time | Isolation forest + rules |
| Returns disposition prediction | Pre-route returns | Reason codes, item, customer history | Classification |

**MLOps:** feature store, model registry, and shadow deployment before promotion. Forecast accuracy is tracked (WAPE, bias) against a naive baseline per site. Models are retrained automatically on a schedule, and drift alerts fire on feature and prediction distributions. Human-in-the-loop: every AI recommendation is advisory unless explicitly configured for auto-execution (e.g., predictive replenishment within guardrails).

| ID | Requirement | Pri |
|---|---|---|
| ADV-070 | Hourly outbound volume forecast with WAPE ≤ 15% at site-day level (after 12 weeks of history) | C |
| ADV-071 | Late-order risk scoring feeding exception dashboard | C |
| ADV-072 | Explainability: top feature contributions displayed for each recommendation | S |

---

## C.9 Digital Twin for Warehouse Simulation

### Components
- **Static model**: building layout, locations with coordinates, aisles, travel network graph (nodes/edges with direction and speed limits), equipment, and automation capacity.
- **Dynamic state**: current inventory, open orders, tasks, and operator positions (from RTLS or last scan).
- **Simulation engine**: discrete-event simulation (DES) that uses actual AstraWMS rule configurations (allocation, putaway, interleaving, wave templates). Each rule service runs in "simulation mode" against a sandboxed state.

### Use Cases

| Use Case | Output |
|---|---|
| Wave plan validation | Predicted completion time per wave vs cut-offs, bottleneck resources |
| Re-slotting what-if | Travel & replenishment delta |
| Peak season planning | Headcount by hour & shift, equipment needs |
| Layout change | New racking / automation throughput impact |
| Rule tuning | Interleaving weights, waveless WIP targets (parameter sweeps) |
| Intraday "look-ahead" | Every 15 min: simulate next 4 hours from live state → projected cut-off misses |

| ID | Requirement | Pri |
|---|---|---|
| ADV-080 | Simulation uses production rule engines (no separate re-implementation) | C |
| ADV-081 | 8-hour shift simulation for a 50k-line day runs ≤ 5 min | C |

---

## C.10 Computer Vision for Inventory & Safety Monitoring

| Use Case | Method | Integration |
|---|---|---|
| Drone / fixed-camera cycle counting | Image capture of rack faces; barcode/label OCR; pallet presence/occupancy detection | Results create count observations; variances → count tasks (never direct adjustment without confirmation) |
| Dock pallet verification | Overhead camera on dock door: pallet count, label read, damage detection (object detection model) | Receipt/load verification events; damage → INB-EX-04 evidence |
| Pack station verification | Camera over pack table: item recognition, count, packaging check | Pack audit sampling; mis-pack alert |
| Dimensioning | Vision/LiDAR dimensioner at induct | Updates LPN / item dims |
| Safety: pedestrian-forklift proximity | Edge AI camera detection with zone rules | Safety events to EHS; near-miss analytics; optional forklift slowdown via telematics |
| PPE compliance | Hi-vis / helmet detection at zone entry | Alert only; privacy-preserving (faces blurred at edge) |
| Rack damage | Upright damage detection | Location block + maintenance ticket |

**Privacy & governance:** processing happens at the edge. The platform stores only events and redacted frames. It supports DPIA documentation (GDPR Art. 35) and works-council agreements. Video retention is configurable, with a default of 30 days for evidence only.

| ID | Requirement | Pri |
|---|---|---|
| ADV-090 | CV count observations integrated into cycle-count workflow | C |
| ADV-091 | Dock damage evidence attached to receipt exceptions | C |
| ADV-092 | Edge anonymisation of persons by default | M (if CV in scope) |
