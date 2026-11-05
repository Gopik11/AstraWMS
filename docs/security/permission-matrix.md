# API permission matrix

Generated from the source by `scripts/permission_matrix.py`; CI fails when it is stale (ADR-0029).

- Every request needs a bearer token of the tenant (ADR-0010); a GET without roles is open to any
  authenticated user of the tenant.
- Every state-changing endpoint declares its roles; services refuse to start otherwise
  (SecuredEndpointsVerifier), and `scripts/check-architecture.sh` checks it in CI.
- Roles are further limited by the scope claims `wms_sites`, `wms_owners`, `wms_zones` and `approval_limit`
  (ADR-0012). Approvals need a second person's token in `X-Approval-Token` (segregation of duties).
- `WMS_SERVICE` is the service-to-service account, `AUTOMATION` a device controller, `ERP_INTEGRATION` the ERP
  middleware; none of them is a person's role.

## State-changing endpoints by role

| Role | Endpoints it may change | Services |
|---|---:|---|
| RECEIVER | 39 | inbound-service, inventory-service, master-data-service, task-service |
| PICKER | 28 | inventory-service, master-data-service, outbound-service, task-service |
| INV_ANALYST | 22 | inventory-service, master-data-service, task-service |
| INV_MANAGER | 42 | inventory-service, master-data-service, outbound-service, task-service |
| SUPERVISOR | 109 | inbound-service, inventory-service, master-data-service, outbound-service, task-service |
| QA_MANAGER | 5 | inbound-service, inventory-service |
| SOLUTION_ADMIN | 44 | all services (astra-common), inventory-service, master-data-service, outbound-service, sap-adapter, task-service |
| ERP_INTEGRATION | 10 | inbound-service, inventory-service, master-data-service, outbound-service, sap-adapter |
| AUTOMATION | 6 | inventory-service, task-service |
| WMS_SERVICE | 11 | inventory-service |

## all services (astra-common)

| Endpoint | Method | Roles | Controller |
|---|---|---|---|
| `/api/v1/ops/dlq` | GET | SOLUTION_ADMIN | OpsController |
| `/api/v1/ops/dlq/replay` | POST | SOLUTION_ADMIN | OpsController |
| `/api/v1/ops/outbox` | GET | SOLUTION_ADMIN | OpsController |
| `/api/v1/ops/status` | GET | SOLUTION_ADMIN | OpsController |

## inbound-service

| Endpoint | Method | Roles | Controller |
|---|---|---|---|
| `/api/v1/network/inbound` | GET | any user of the tenant | NetworkController |
| `/api/v1/sites/{siteId}/receipts` | GET | any user of the tenant | ReceiptController |
| `/api/v1/sites/{siteId}/receipts/{erpDocNo}` | GET | any user of the tenant | ReceiptController |
| `/api/v1/sites/{siteId}/receipts/{erpDocNo}/close` | POST | RECEIVER, SUPERVISOR | ReceiptController |
| `/api/v1/sites/{siteId}/receipts/{erpDocNo}/damage` | GET | any user of the tenant | ReceiptController |
| `/api/v1/sites/{siteId}/receipts/{erpDocNo}/lines/{lineRef}/receive` | POST | RECEIVER, SUPERVISOR | ReceiptController |
| `/api/v1/sites/{siteId}/receipts/{erpDocNo}/receive-item` | POST | RECEIVER, SUPERVISOR | ReceiptController |
| `/api/v1/sites/{siteId}/receipts/{erpDocNo}/repost` | POST | SUPERVISOR | ReceiptController |
| `/api/v1/sites/{siteId}/receipts/{erpDocNo}/sscc/{sscc}/receive` | POST | RECEIVER, SUPERVISOR | ReceiptController |
| `/api/v1/sites/{siteId}/returns` | GET | any user of the tenant | ReturnsController |
| `/api/v1/sites/{siteId}/returns` | POST | RECEIVER, SUPERVISOR | ReturnsController |
| `/api/v1/sites/{siteId}/returns/repairs` | GET | any user of the tenant | ReturnsReportController |
| `/api/v1/sites/{siteId}/returns/report` | GET | any user of the tenant | ReturnsReportController |
| `/api/v1/sites/{siteId}/returns/units/{unitId}/repair` | POST | RECEIVER, SUPERVISOR, QA_MANAGER | ReturnsReportController |
| `/api/v1/sites/{siteId}/returns/{rmaNo}` | GET | any user of the tenant | ReturnsController |
| `/api/v1/sites/{siteId}/returns/{rmaNo}/close` | POST | RECEIVER, SUPERVISOR | ReturnsController |
| `/api/v1/sites/{siteId}/returns/{rmaNo}/repost` | POST | SUPERVISOR | ReturnsController |
| `/api/v1/sites/{siteId}/returns/{rmaNo}/units` | POST | RECEIVER, SUPERVISOR | ReturnsController |
| `/api/v1/sites/{siteId}/yard/appointments` | GET | any user of the tenant | YardController |
| `/api/v1/sites/{siteId}/yard/appointments` | POST | SUPERVISOR, ERP_INTEGRATION | YardController |
| `/api/v1/sites/{siteId}/yard/appointments/{apptNo}` | GET | any user of the tenant | YardController |
| `/api/v1/sites/{siteId}/yard/appointments/{apptNo}` | PUT | SUPERVISOR, ERP_INTEGRATION | YardController |
| `/api/v1/sites/{siteId}/yard/appointments/{apptNo}/cancel` | POST | SUPERVISOR | YardController |
| `/api/v1/sites/{siteId}/yard/appointments/{apptNo}/check-in` | POST | RECEIVER, SUPERVISOR | YardController |
| `/api/v1/sites/{siteId}/yard/appointments/{apptNo}/check-out` | POST | RECEIVER, SUPERVISOR | YardController |
| `/api/v1/sites/{siteId}/yard/appointments/{apptNo}/no-show` | POST | SUPERVISOR | YardController |
| `/api/v1/sites/{siteId}/yard/appointments/{apptNo}/to-door` | POST | RECEIVER, SUPERVISOR | YardController |
| `/api/v1/sites/{siteId}/yard/summary` | GET | any user of the tenant | YardController |

## inventory-service

| Endpoint | Method | Roles | Controller |
|---|---|---|---|
| `/api/v1/network/inventory` | GET | any user of the tenant | NetworkController |
| `/api/v1/network/items` | GET | any user of the tenant | EnterpriseController |
| `/api/v1/network/items/{ownerId}/{itemNo}` | GET | any user of the tenant | EnterpriseController |
| `/api/v1/network/owners` | GET | any user of the tenant | EnterpriseController |
| `/api/v1/network/owners/{ownerId}` | PUT | INV_MANAGER, SOLUTION_ADMIN | EnterpriseController |
| `/api/v1/network/recall` | GET | any user of the tenant | EnterpriseController |
| `/api/v1/network/replenishment` | GET | any user of the tenant | EnterpriseController |
| `/api/v1/network/value` | GET | any user of the tenant | EnterpriseController |
| `/api/v1/sites/{siteId}/inventory/adjustments` | POST | INV_ANALYST, INV_MANAGER, SUPERVISOR, WMS_SERVICE | InventoryController |
| `/api/v1/sites/{siteId}/inventory/aisles` | GET | any user of the tenant | NetworkController |
| `/api/v1/sites/{siteId}/inventory/allocation-policy` | GET | any user of the tenant | InventoryController |
| `/api/v1/sites/{siteId}/inventory/allocation-policy` | PUT | SOLUTION_ADMIN | InventoryController |
| `/api/v1/sites/{siteId}/inventory/allocation-policy/owners` | GET | any user of the tenant | InventoryController |
| `/api/v1/sites/{siteId}/inventory/allocation-policy/owners/{ownerId}` | DELETE | SOLUTION_ADMIN | InventoryController |
| `/api/v1/sites/{siteId}/inventory/allocations` | GET | any user of the tenant | InventoryController |
| `/api/v1/sites/{siteId}/inventory/allocations` | POST | SUPERVISOR, WMS_SERVICE | InventoryController |
| `/api/v1/sites/{siteId}/inventory/allocations/release` | POST | SUPERVISOR, WMS_SERVICE | InventoryController |
| `/api/v1/sites/{siteId}/inventory/allocations/{allocationId}/pick` | POST | PICKER, SUPERVISOR, WMS_SERVICE | InventoryController |
| `/api/v1/sites/{siteId}/inventory/allocations/{allocationId}/return` | POST | RECEIVER, PICKER, SUPERVISOR, WMS_SERVICE | InventoryController |
| `/api/v1/sites/{siteId}/inventory/balances` | GET | any user of the tenant | InventoryController |
| `/api/v1/sites/{siteId}/inventory/billing/capture` | POST | INV_MANAGER, SUPERVISOR, SOLUTION_ADMIN | BillingController |
| `/api/v1/sites/{siteId}/inventory/billing/events` | GET | INV_MANAGER, SUPERVISOR, SOLUTION_ADMIN | BillingController |
| `/api/v1/sites/{siteId}/inventory/billing/rates` | GET | INV_MANAGER, SUPERVISOR, SOLUTION_ADMIN | BillingController |
| `/api/v1/sites/{siteId}/inventory/billing/rates` | PUT | SOLUTION_ADMIN | BillingController |
| `/api/v1/sites/{siteId}/inventory/billing/summary` | GET | INV_MANAGER, SUPERVISOR, SOLUTION_ADMIN | BillingController |
| `/api/v1/sites/{siteId}/inventory/billing/vas` | POST | INV_MANAGER, SUPERVISOR, SOLUTION_ADMIN | BillingController |
| `/api/v1/sites/{siteId}/inventory/cost-objects` | GET | any user of the tenant | MaterialIssueController |
| `/api/v1/sites/{siteId}/inventory/cost-objects/{type}/{code}` | PUT | INV_MANAGER, SOLUTION_ADMIN, ERP_INTEGRATION | MaterialIssueController |
| `/api/v1/sites/{siteId}/inventory/counts` | GET | any user of the tenant | CountController |
| `/api/v1/sites/{siteId}/inventory/counts` | POST | INV_ANALYST, INV_MANAGER, SUPERVISOR | CountController |
| `/api/v1/sites/{siteId}/inventory/counts/plan` | GET | any user of the tenant | EnterpriseController |
| `/api/v1/sites/{siteId}/inventory/counts/plan/frequency` | PUT | INV_MANAGER, SOLUTION_ADMIN | EnterpriseController |
| `/api/v1/sites/{siteId}/inventory/counts/plan/open` | POST | INV_ANALYST, INV_MANAGER, SUPERVISOR | EnterpriseController |
| `/api/v1/sites/{siteId}/inventory/counts/{countId}` | GET | any user of the tenant | CountController |
| `/api/v1/sites/{siteId}/inventory/counts/{countId}/approve` | POST | INV_MANAGER, SUPERVISOR | CountController |
| `/api/v1/sites/{siteId}/inventory/counts/{countId}/reject` | POST | INV_MANAGER, SUPERVISOR | CountController |
| `/api/v1/sites/{siteId}/inventory/counts/{countId}/results` | POST | RECEIVER, PICKER, INV_ANALYST, SUPERVISOR, WMS_SERVICE | CountController |
| `/api/v1/sites/{siteId}/inventory/faces/{ownerId}/{itemNo}` | GET | any user of the tenant | ReplenishmentController |
| `/api/v1/sites/{siteId}/inventory/faces/{ownerId}/{itemNo}/replenish` | POST | INV_MANAGER, SUPERVISOR | ReplenishmentController |
| `/api/v1/sites/{siteId}/inventory/inbound-staging` | GET | any user of the tenant | InventoryController |
| `/api/v1/sites/{siteId}/inventory/issues` | POST | SUPERVISOR, WMS_SERVICE | InventoryController |
| `/api/v1/sites/{siteId}/inventory/items/{ownerId}/{itemNo}/summary` | GET | any user of the tenant | InventoryController |
| `/api/v1/sites/{siteId}/inventory/lpns/{lpnId}` | GET | any user of the tenant | InventoryController |
| `/api/v1/sites/{siteId}/inventory/material-issues` | GET | any user of the tenant | MaterialIssueController |
| `/api/v1/sites/{siteId}/inventory/material-issues` | POST | RECEIVER, PICKER, INV_ANALYST, INV_MANAGER, SUPERVISOR | MaterialIssueController |
| `/api/v1/sites/{siteId}/inventory/material-issues/{issueNo}` | GET | any user of the tenant | MaterialIssueController |
| `/api/v1/sites/{siteId}/inventory/material-issues/{issueNo}/approve` | POST | INV_MANAGER, SUPERVISOR | MaterialIssueController |
| `/api/v1/sites/{siteId}/inventory/material-issues/{issueNo}/close` | POST | INV_MANAGER, SUPERVISOR | MaterialIssueController |
| `/api/v1/sites/{siteId}/inventory/material-issues/{issueNo}/lines/{lineNo}/issue` | POST | RECEIVER, PICKER, INV_MANAGER, SUPERVISOR | MaterialIssueController |
| `/api/v1/sites/{siteId}/inventory/material-issues/{issueNo}/lines/{lineNo}/return` | POST | RECEIVER, PICKER, INV_MANAGER, SUPERVISOR | MaterialIssueController |
| `/api/v1/sites/{siteId}/inventory/material-issues/{issueNo}/reject` | POST | INV_MANAGER, SUPERVISOR | MaterialIssueController |
| `/api/v1/sites/{siteId}/inventory/moves` | POST | RECEIVER, PICKER, INV_ANALYST, SUPERVISOR, WMS_SERVICE | InventoryController |
| `/api/v1/sites/{siteId}/inventory/physical-inventories` | GET | any user of the tenant | PhysicalInventoryController |
| `/api/v1/sites/{siteId}/inventory/physical-inventories` | POST | INV_MANAGER, SUPERVISOR | PhysicalInventoryController |
| `/api/v1/sites/{siteId}/inventory/physical-inventories/{piNo}` | GET | any user of the tenant | PhysicalInventoryController |
| `/api/v1/sites/{siteId}/inventory/physical-inventories/{piNo}/cancel` | POST | INV_MANAGER, SUPERVISOR | PhysicalInventoryController |
| `/api/v1/sites/{siteId}/inventory/physical-inventories/{piNo}/post` | POST | INV_MANAGER, SUPERVISOR | PhysicalInventoryController |
| `/api/v1/sites/{siteId}/inventory/physical-inventories/{piNo}/start` | POST | INV_MANAGER, SUPERVISOR | PhysicalInventoryController |
| `/api/v1/sites/{siteId}/inventory/receipts` | POST | RECEIVER, SUPERVISOR, WMS_SERVICE | InventoryController |
| `/api/v1/sites/{siteId}/inventory/replenishment-rules` | GET | any user of the tenant | ReplenishmentController |
| `/api/v1/sites/{siteId}/inventory/replenishment-rules/{locationId}/{ownerId}/{itemNo}` | PUT | INV_MANAGER, SOLUTION_ADMIN | ReplenishmentController |
| `/api/v1/sites/{siteId}/inventory/replenishments` | GET | any user of the tenant | ReplenishmentController |
| `/api/v1/sites/{siteId}/inventory/replenishments/evaluate` | POST | INV_MANAGER, SUPERVISOR, SOLUTION_ADMIN | ReplenishmentController |
| `/api/v1/sites/{siteId}/inventory/replenishments/{replenishmentId}/confirm` | POST | RECEIVER, PICKER, SUPERVISOR, WMS_SERVICE | ReplenishmentController |
| `/api/v1/sites/{siteId}/inventory/rfid/locations/{locationId}/reconcile` | POST | RECEIVER, PICKER, INV_ANALYST, INV_MANAGER, SUPERVISOR, QA_MANAGER, AUTOMATION | RfidController |
| `/api/v1/sites/{siteId}/inventory/rfid/resolve` | POST | RECEIVER, PICKER, INV_ANALYST, INV_MANAGER, SUPERVISOR, QA_MANAGER, AUTOMATION | RfidController |
| `/api/v1/sites/{siteId}/inventory/rfid/sightings` | POST | RECEIVER, PICKER, INV_ANALYST, INV_MANAGER, SUPERVISOR, QA_MANAGER, AUTOMATION | RfidController |
| `/api/v1/sites/{siteId}/inventory/rfid/tags` | GET | any user of the tenant | RfidController |
| `/api/v1/sites/{siteId}/inventory/rfid/tags` | POST | RECEIVER, INV_ANALYST, INV_MANAGER, SUPERVISOR | RfidController |
| `/api/v1/sites/{siteId}/inventory/rfid/tags/{epc}` | GET | any user of the tenant | RfidController |
| `/api/v1/sites/{siteId}/inventory/rfid/tags/{epc}/retire` | POST | INV_ANALYST, INV_MANAGER, SUPERVISOR | RfidController |
| `/api/v1/sites/{siteId}/inventory/serials/{ownerId}/{itemNo}/{serialNo}` | GET | any user of the tenant | InventoryController |
| `/api/v1/sites/{siteId}/inventory/shipped` | GET | any user of the tenant | EnterpriseController |
| `/api/v1/sites/{siteId}/inventory/slotting` | GET | any user of the tenant | ReplenishmentController |
| `/api/v1/sites/{siteId}/inventory/slotting/{ownerId}/{itemNo}` | PUT | INV_MANAGER, SOLUTION_ADMIN | ReplenishmentController |
| `/api/v1/sites/{siteId}/inventory/slotting/{ownerId}/{itemNo}/reslot` | POST | INV_MANAGER, SOLUTION_ADMIN | ReplenishmentController |
| `/api/v1/sites/{siteId}/inventory/status-changes` | POST | INV_ANALYST, INV_MANAGER, SUPERVISOR, QA_MANAGER, WMS_SERVICE | InventoryController |
| `/api/v1/sites/{siteId}/inventory/store-policies` | GET | any user of the tenant | EnterpriseController |
| `/api/v1/sites/{siteId}/inventory/store-policies` | PUT | INV_MANAGER, SUPERVISOR, SOLUTION_ADMIN | EnterpriseController |
| `/api/v1/sites/{siteId}/inventory/store-replenishment/accept` | POST | INV_MANAGER, SUPERVISOR | EnterpriseController |
| `/api/v1/sites/{siteId}/inventory/store-setting` | GET | any user of the tenant | EnterpriseController |
| `/api/v1/sites/{siteId}/inventory/store-setting` | PUT | INV_MANAGER, SUPERVISOR, SOLUTION_ADMIN | EnterpriseController |
| `/api/v1/sites/{siteId}/inventory/transactions` | GET | any user of the tenant | InventoryController |

## master-data-service

| Endpoint | Method | Roles | Controller |
|---|---|---|---|
| `/api/v1/items/{ownerId}` | GET | any user of the tenant | MasterDataController |
| `/api/v1/items/{ownerId}/{itemNo}` | GET | any user of the tenant | MasterDataController |
| `/api/v1/items/{ownerId}/{itemNo}` | PUT | SOLUTION_ADMIN, ERP_INTEGRATION | MasterDataController |
| `/api/v1/platform/tenants` | GET | WMS_SERVICE, PLATFORM_ADMIN | PlatformController |
| `/api/v1/platform/tenants` | POST | PLATFORM_ADMIN | PlatformController |
| `/api/v1/platform/tenants/{tenantId}` | GET | PLATFORM_ADMIN | PlatformController |
| `/api/v1/platform/tenants/{tenantId}` | PUT | PLATFORM_ADMIN | PlatformController |
| `/api/v1/platform/tenants/{tenantId}/history` | GET | PLATFORM_ADMIN | PlatformController |
| `/api/v1/platform/tenants/{tenantId}/status` | POST | PLATFORM_ADMIN | PlatformController |
| `/api/v1/sites` | GET | any user of the tenant | MasterDataController |
| `/api/v1/sites/{siteId}` | GET | any user of the tenant | MasterDataController |
| `/api/v1/sites/{siteId}` | PUT | SOLUTION_ADMIN | MasterDataController |
| `/api/v1/sites/{siteId}/labels/items` | POST | RECEIVER, INV_ANALYST, INV_MANAGER, SUPERVISOR, SOLUTION_ADMIN | LabelController |
| `/api/v1/sites/{siteId}/labels/locations` | POST | INV_ANALYST, INV_MANAGER, SUPERVISOR, SOLUTION_ADMIN | LabelController |
| `/api/v1/sites/{siteId}/labels/lpns` | POST | RECEIVER, INV_MANAGER, SUPERVISOR, SOLUTION_ADMIN | LabelController |
| `/api/v1/sites/{siteId}/labels/printed` | GET | any user of the tenant | LabelController |
| `/api/v1/sites/{siteId}/labels/printed/status` | GET | any user of the tenant | LabelController |
| `/api/v1/sites/{siteId}/labels/printed/verify` | POST | RECEIVER, PICKER, INV_ANALYST, INV_MANAGER, SUPERVISOR, SOLUTION_ADMIN | LabelController |
| `/api/v1/sites/{siteId}/labels/printed/{id}/reprint` | POST | RECEIVER, INV_ANALYST, INV_MANAGER, SUPERVISOR, SOLUTION_ADMIN | LabelController |
| `/api/v1/sites/{siteId}/labels/printed/{id}/void` | POST | INV_MANAGER, SUPERVISOR, SOLUTION_ADMIN | LabelController |
| `/api/v1/sites/{siteId}/labels/printers` | GET | any user of the tenant | LabelController |
| `/api/v1/sites/{siteId}/labels/printers/{name}` | DELETE | SOLUTION_ADMIN | LabelController |
| `/api/v1/sites/{siteId}/labels/printers/{name}` | PUT | SOLUTION_ADMIN | LabelController |
| `/api/v1/sites/{siteId}/locations` | GET | any user of the tenant | MasterDataController |
| `/api/v1/sites/{siteId}/locations/republish` | POST | SOLUTION_ADMIN | MasterDataController |
| `/api/v1/sites/{siteId}/locations/{locationId}` | GET | any user of the tenant | MasterDataController |
| `/api/v1/sites/{siteId}/locations/{locationId}` | PUT | SOLUTION_ADMIN | MasterDataController |
| `/api/v1/sites/{siteId}/zones/{zoneId}` | PUT | SOLUTION_ADMIN | MasterDataController |
| `/api/v1/sites/{siteId}/zones/{zoneId}/locations/generate` | POST | SOLUTION_ADMIN | MasterDataController |

## outbound-service

| Endpoint | Method | Roles | Controller |
|---|---|---|---|
| `/api/v1/network/outbound` | GET | any user of the tenant | NetworkController |
| `/api/v1/network/outbound/commitments` | GET | any user of the tenant | NetworkController |
| `/api/v1/network/outbound/items/{itemNo}` | GET | any user of the tenant | NetworkController |
| `/api/v1/network/outbound/metrics` | GET | any user of the tenant | NetworkController |
| `/api/v1/sites/{siteId}/outbound/carrier-cutoffs` | GET | any user of the tenant | WaveController |
| `/api/v1/sites/{siteId}/outbound/carrier-cutoffs/{carrierScac}` | DELETE | SUPERVISOR, SOLUTION_ADMIN | WaveController |
| `/api/v1/sites/{siteId}/outbound/carrier-cutoffs/{carrierScac}` | PUT | SUPERVISOR, SOLUTION_ADMIN | WaveController |
| `/api/v1/sites/{siteId}/outbound/cartons/{sscc}` | GET | any user of the tenant | PackingController |
| `/api/v1/sites/{siteId}/outbound/cartons/{sscc}/close` | POST | PICKER, SUPERVISOR | PackingController |
| `/api/v1/sites/{siteId}/outbound/cartons/{sscc}/items` | POST | PICKER, SUPERVISOR | PackingController |
| `/api/v1/sites/{siteId}/outbound/config` | GET | any user of the tenant | WaveController |
| `/api/v1/sites/{siteId}/outbound/config` | PUT | SOLUTION_ADMIN | WaveController |
| `/api/v1/sites/{siteId}/outbound/loads` | GET | any user of the tenant | PackingController |
| `/api/v1/sites/{siteId}/outbound/loads` | POST | SUPERVISOR | PackingController |
| `/api/v1/sites/{siteId}/outbound/loads/{loadNo}` | GET | any user of the tenant | PackingController |
| `/api/v1/sites/{siteId}/outbound/loads/{loadNo}/close` | POST | SUPERVISOR | PackingController |
| `/api/v1/sites/{siteId}/outbound/loads/{loadNo}/orders` | POST | PICKER, SUPERVISOR | PackingController |
| `/api/v1/sites/{siteId}/outbound/loads/{loadNo}/orders/{erpDocNo}` | DELETE | SUPERVISOR | PackingController |
| `/api/v1/sites/{siteId}/outbound/loads/{loadNo}/tracking` | POST | SUPERVISOR, ERP_INTEGRATION | PackingController |
| `/api/v1/sites/{siteId}/outbound/orders` | GET | any user of the tenant | OutboundController |
| `/api/v1/sites/{siteId}/outbound/orders/{erpDocNo}` | GET | any user of the tenant | OutboundController |
| `/api/v1/sites/{siteId}/outbound/orders/{erpDocNo}/cartons` | POST | PICKER, SUPERVISOR | PackingController |
| `/api/v1/sites/{siteId}/outbound/orders/{erpDocNo}/close-shorts` | POST | SUPERVISOR | OutboundController |
| `/api/v1/sites/{siteId}/outbound/orders/{erpDocNo}/packing` | GET | any user of the tenant | PackingController |
| `/api/v1/sites/{siteId}/outbound/orders/{erpDocNo}/policy` | PUT | SUPERVISOR | WaveController |
| `/api/v1/sites/{siteId}/outbound/orders/{erpDocNo}/reallocate` | POST | SUPERVISOR | OutboundController |
| `/api/v1/sites/{siteId}/outbound/orders/{erpDocNo}/repost` | POST | SUPERVISOR | OutboundController |
| `/api/v1/sites/{siteId}/outbound/orders/{erpDocNo}/ship` | POST | SUPERVISOR | OutboundController |
| `/api/v1/sites/{siteId}/outbound/owner-policies` | GET | any user of the tenant | WaveController |
| `/api/v1/sites/{siteId}/outbound/owner-policies/{ownerId}` | PUT | SOLUTION_ADMIN | WaveController |
| `/api/v1/sites/{siteId}/outbound/transfers` | GET | any user of the tenant | WaveController |
| `/api/v1/sites/{siteId}/outbound/transfers` | POST | INV_MANAGER, SUPERVISOR | WaveController |
| `/api/v1/sites/{siteId}/outbound/waves` | GET | any user of the tenant | WaveController |
| `/api/v1/sites/{siteId}/outbound/waves` | POST | SUPERVISOR | WaveController |
| `/api/v1/sites/{siteId}/outbound/waves/plan` | POST | SUPERVISOR | WaveController |
| `/api/v1/sites/{siteId}/outbound/waves/release-by-cutoff` | POST | SUPERVISOR | WaveController |
| `/api/v1/sites/{siteId}/outbound/waves/{waveNo}` | GET | any user of the tenant | WaveController |
| `/api/v1/sites/{siteId}/outbound/waves/{waveNo}/hold` | POST | SUPERVISOR | WaveController |
| `/api/v1/sites/{siteId}/outbound/waves/{waveNo}/release` | POST | SUPERVISOR | WaveController |
| `/api/v1/sites/{siteId}/outbound/waves/{waveNo}/unhold` | POST | SUPERVISOR | WaveController |

## sap-adapter

| Endpoint | Method | Roles | Controller |
|---|---|---|---|
| `/api/v1/integration/webhooks` | GET | SOLUTION_ADMIN, ERP_INTEGRATION | WebhookController |
| `/api/v1/integration/webhooks` | POST | SOLUTION_ADMIN, ERP_INTEGRATION | WebhookController |
| `/api/v1/integration/webhooks/{id}` | DELETE | SOLUTION_ADMIN, ERP_INTEGRATION | WebhookController |
| `/api/v1/integration/webhooks/{id}/deliveries` | GET | SOLUTION_ADMIN, ERP_INTEGRATION | WebhookController |
| `/api/v1/integration/webhooks/{id}/ping` | POST | SOLUTION_ADMIN, ERP_INTEGRATION | WebhookController |
| `/api/v1/sap/idocs/delvry07` | POST | ERP_INTEGRATION | SapAdapterController |
| `/api/v1/sap/idocs/matmas05` | POST | ERP_INTEGRATION | SapAdapterController |
| `/api/v1/sap/idocs/{idocNumber}` | GET | SUPERVISOR, SOLUTION_ADMIN, ERP_INTEGRATION | SapAdapterController |
| `/api/v1/sap/site-map/{werks}` | PUT | SOLUTION_ADMIN | SapAdapterController |
| `/mock-sap/documents` | GET | SOLUTION_ADMIN | MockSapController |
| `/mock-sap/faults` | POST | SOLUTION_ADMIN | MockSapController |
| `/mock-sap/faults/{faultKey}` | DELETE | SOLUTION_ADMIN | MockSapController |

## task-service

| Endpoint | Method | Roles | Controller |
|---|---|---|---|
| `/api/v1/network/tasks` | GET | any user of the tenant | NetworkController |
| `/api/v1/sites/{siteId}/tasks` | GET | any user of the tenant | TaskController |
| `/api/v1/sites/{siteId}/tasks/aisles` | GET | any user of the tenant | NetworkController |
| `/api/v1/sites/{siteId}/tasks/automation/claim` | POST | SUPERVISOR, AUTOMATION | AutomationController |
| `/api/v1/sites/{siteId}/tasks/automation/tasks/{taskId}/confirm` | POST | SUPERVISOR, AUTOMATION | AutomationController |
| `/api/v1/sites/{siteId}/tasks/automation/tasks/{taskId}/exception` | POST | SUPERVISOR, AUTOMATION | AutomationController |
| `/api/v1/sites/{siteId}/tasks/automation/zones` | GET | any user of the tenant | AutomationController |
| `/api/v1/sites/{siteId}/tasks/automation/zones/{zoneId}` | PUT | SUPERVISOR, SOLUTION_ADMIN | AutomationController |
| `/api/v1/sites/{siteId}/tasks/claim-batch` | POST | RECEIVER, PICKER, INV_ANALYST, SUPERVISOR | TaskController |
| `/api/v1/sites/{siteId}/tasks/label-checks/bypasses` | GET | INV_MANAGER, SUPERVISOR, SOLUTION_ADMIN | LabelCheckController |
| `/api/v1/sites/{siteId}/tasks/label-checks/bypasses/{id}/review` | POST | INV_MANAGER, SUPERVISOR | LabelCheckController |
| `/api/v1/sites/{siteId}/tasks/label-checks/policy` | GET | any user of the tenant | LabelCheckController |
| `/api/v1/sites/{siteId}/tasks/label-checks/policy` | PUT | SUPERVISOR, SOLUTION_ADMIN | LabelCheckController |
| `/api/v1/sites/{siteId}/tasks/labor` | GET | SUPERVISOR, SOLUTION_ADMIN | TaskController |
| `/api/v1/sites/{siteId}/tasks/next` | POST | RECEIVER, PICKER, INV_ANALYST, SUPERVISOR | TaskController |
| `/api/v1/sites/{siteId}/tasks/operators` | GET | any user of the tenant | TaskController |
| `/api/v1/sites/{siteId}/tasks/operators/{userId}` | PUT | SUPERVISOR, SOLUTION_ADMIN | TaskController |
| `/api/v1/sites/{siteId}/tasks/pick-group` | POST | PICKER, SUPERVISOR | TaskController |
| `/api/v1/sites/{siteId}/tasks/standards` | GET | any user of the tenant | TaskController |
| `/api/v1/sites/{siteId}/tasks/standards/{taskType}` | PUT | SUPERVISOR, SOLUTION_ADMIN | TaskController |
| `/api/v1/sites/{siteId}/tasks/sweep-dock` | POST | SUPERVISOR | TaskController |
| `/api/v1/sites/{siteId}/tasks/unassign-stale` | POST | SUPERVISOR | TaskController |
| `/api/v1/sites/{siteId}/tasks/zone-equipment` | GET | any user of the tenant | TaskController |
| `/api/v1/sites/{siteId}/tasks/zone-equipment/{zoneId}` | PUT | SUPERVISOR, SOLUTION_ADMIN | TaskController |
| `/api/v1/sites/{siteId}/tasks/{taskId}` | GET | any user of the tenant | TaskController |
| `/api/v1/sites/{siteId}/tasks/{taskId}/confirm` | POST | RECEIVER, PICKER, SUPERVISOR | TaskController |
| `/api/v1/sites/{siteId}/tasks/{taskId}/count` | POST | RECEIVER, PICKER, INV_ANALYST, SUPERVISOR | TaskController |
| `/api/v1/sites/{siteId}/tasks/{taskId}/exception` | POST | RECEIVER, PICKER, SUPERVISOR | TaskController |
| `/api/v1/sites/{siteId}/tasks/{taskId}/move` | POST | RECEIVER, PICKER, INV_ANALYST, SUPERVISOR | TaskController |
| `/api/v1/sites/{siteId}/tasks/{taskId}/pick` | POST | PICKER, SUPERVISOR | TaskController |
| `/api/v1/sites/{siteId}/tasks/{taskId}/receive` | POST | RECEIVER, SUPERVISOR | TaskController |
| `/api/v1/sites/{siteId}/tasks/{taskId}/receive/close` | POST | RECEIVER, SUPERVISOR | TaskController |
| `/api/v1/sites/{siteId}/tasks/{taskId}/release` | POST | RECEIVER, PICKER, INV_ANALYST, SUPERVISOR | TaskController |
| `/api/v1/sites/{siteId}/tasks/{taskId}/replan` | POST | SUPERVISOR | TaskController |
| `/api/v1/sites/{siteId}/tasks/{taskId}/replenish` | POST | RECEIVER, PICKER, SUPERVISOR | TaskController |
| `/api/v1/sites/{siteId}/tasks/{taskId}/return` | POST | RECEIVER, PICKER, SUPERVISOR | TaskController |
| `/api/v1/sites/{siteId}/tasks/{taskId}/sync-conflict` | POST | RECEIVER, PICKER, INV_ANALYST, SUPERVISOR | TaskController |
| `/api/v1/sites/{siteId}/tasks/{taskId}/unassign` | POST | SUPERVISOR | TaskController |
