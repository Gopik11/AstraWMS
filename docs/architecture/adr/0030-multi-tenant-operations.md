# ADR-0030: Multi-Tenant Operations: Tenant Registry, Owner RLS, Per-Tenant ERP, Data Lifecycle and Fairness

- **Status:** Proposed
- **Date:** 2026-11-04
- **Builds on:** ADR-0003 (tenant RLS), ADR-0010 (tenant from the token), ADR-0012 (site, owner and zone scopes),
  ADR-0029 (backups, architecture checks)
- **Supersedes in part:** ADR-0010 ("one user token is bound to one tenant"), ADR-0012 (owner scope enforced only in
  the services)

## Context

AstraWMS is multi-tenant by design, with everything shared.

- **Identity:** one Keycloak realm. The `tenant_id` user attribute goes into the token, and `TenantFilter` binds it
  to each request.
- **Database:** one database per service, shared by every tenant. 84 of the 93 service tables carry `tenant_id` and
  have a forced RLS policy. The service role cannot bypass RLS, and `TenantAwareDataSource` sets `app.tenant_id` on
  every connection checkout.
- **Messaging:** shared Kafka topics, keyed by `tenant:businessKey`, with the tenant in the envelope.
- **Within a tenant:** sites, owners and zones scope each user (ADR-0012).

Isolation of reads and writes between tenants is solid and tested (`TenancyAndConcurrencyIT`). A review of the code
on 4 November 2026 found what is still missing to run many customers on one deployment:

| # | Gap | Evidence |
| --- | --- | --- |
| G1 | **No tenant registry or lifecycle.** A tenant exists as soon as an administrator types a `tenant_id` on a user. Nothing checks it; a typo creates a new, empty tenant. Tenants cannot be suspended or offboarded. | No tenant table; `TenantFilter` accepts any claim value |
| G2 | **One ERP system per deployment.** The SAP logical system and gateway are environment variables. Plant mapping (`site_map`) is per tenant, but acknowledgements are matched against the single logical system. | `SAP_LOGICAL_SYSTEM`, `SAP_GATEWAY`; `AdapterListeners` |
| G3 | **Owner isolation exists only in application code.** RLS separates tenants. A 3PL's clients within a tenant are separated by checks in each service. MWH-001 requires "enforced at data layer, not UI only". | RLS policies compare only `tenant_id`; ADR-0012 |
| G4 | **Backup and restore cover the whole deployment.** One tenant cannot be restored without rolling back every tenant. There is no export or deletion of one tenant's data. | `pg_dumpall` in `deploy/vps/pg-backup.sh` |
| G5 | **One tenant can slow the others.** The gateway limits requests per client address, not per tenant. Relay partitions, consumers and the downstream bulkhead are shared, so one tenant's backlog delays every tenant. | `deploy/gateway/nginx.conf`; `OutboxRelay`; `ServiceClients` |
| G6 | **Staff who serve several tenants.** A token holds one tenant, and switching is not built. | ADR-0010 |
| G7 | **Tables without RLS are protected by convention.** The CI rule that requires a `tenant_id` filter covers `outbox` and `dlq_replay`, but not `reservation_compensation` or `webhook_delivery`. Nothing stops a new table from missing RLS. | `scripts/check-architecture.sh` rule 3 |
| G8 | **No tooling to move a tenant to its own database cell.** ADR-0003 says this needs no code change, which is true, but nothing copies one tenant's data out. | (none) |

## Decision

The shared-everything ("pool") model stays the default. One customer per deployment ("silo") remains possible,
because it is just a pool with one tenant. The decisions below make the pool safe to operate with many customers.
Each one is independent, and the plan at the end orders them.

### A. Tenant registry and lifecycle (G1)

- **Owner.** `master-data-service` owns a `tenant` table:
  - `tenant_id` (the token value, same pattern as the Keycloak attribute);
  - `display_name`, `status`, `time_zone`, `reporting_currency`;
  - `plan` (quota set, see E);
  - `cell` (default `main`, see H);
  - `created_at`, `status_changed_at`, `status_reason`.
- **Status.**
  - `ACTIVE`
  - `SUSPENDED`: sign-in and API refused; background work for the tenant pauses; data kept.
  - `OFFBOARDING`: read-only for the tenant's administrators, for an export.
  - `PURGED`: data deleted, row kept as a tombstone so the id is never reused.
- **API.** `/api/v1/platform/tenants`, with a new role `PLATFORM_ADMIN`. This role is not tied to a tenant and is
  held only by the operator's accounts, through a separate client scope that a tenant administrator cannot grant.
  - Creating a tenant also creates its first administrator in Keycloak, through the provisioner account. This
    replaces the hand-typed attribute.
  - Changes publish `TenantChanged` on `wms.masterdata.events.v1`.
- **Enforcement without a synchronous dependency.**
  - Every service keeps a `tenant_status` projection from `TenantChanged`. `TenantFilter` refuses unknown tenants
    with 403 `TENANT_UNKNOWN`, and suspended or purged ones with 403 `TENANT_SUSPENDED`, from a local cache.
  - It fails closed for an unknown tenant. Before the projection has synchronized at start-up, it answers 503.
  - Consumers skip, and record, messages of a suspended tenant through `InboxProcessing`. They do not dead-letter
    them.
- **Migration.** A migration seeds the registry from the tenant ids in use (`select distinct tenant_id from site`,
  plus each service's own tables, reconciled by a one-off script), all `ACTIVE`.

### B. ERP connection per tenant (G2)

- **Table.** `sap-adapter` gains `erp_connection`, one row per tenant:
  - `logical_system`;
  - `gateway` (`mock`, `rfc`, `odata` or `cpi`);
  - `endpoint`;
  - `credentials_ref`: the name of a secret in the deployment's secret store. The adapter never stores
    credentials.
- **Flows.** Every flow resolves the connection of the message's tenant. Acknowledgements and posting results are
  matched against that tenant's logical system.
- **Inbound validation.** A message from SAP is accepted only when its `sourceSystem` equals the logical system of
  the tenant in the integration token. Today the adapter trusts the token alone.
- **Migration.** `SAP_LOGICAL_SYSTEM` and `SAP_GATEWAY` become the defaults that seed a row for each existing tenant.
  After that the environment variables are read only when a tenant has no row.

### C. Owner isolation in the database (G3, MWH-001)

- **Session setting.** `TenantAwareDataSource` sets `app.owners` together with `app.tenant_id`, in one statement on
  every checkout:
  - the user's owners, comma-separated, from `AccessScope`;
  - `*` for unrestricted users, service accounts, consumers and jobs (the trusted-subsystem rule of ADR-0010 is
    unchanged);
  - empty when the scope allows no owner or no scope is bound, which shows nothing;
  - an owner id that contains a comma, or is `*`, cannot be expressed and is left out (fail closed).
- **Policy.** Every table with an `owner_id` gets a second, restrictive policy:

  ```sql
  create policy owner_isolation on <table> as restrictive
    using ((select current_setting('app.owners', true)) = '*'
           or owner_id = any ((select string_to_array(current_setting('app.owners', true), ','))::text[]))
    with check ((select current_setting('app.owners', true)) = '*'
           or owner_id = any ((select string_to_array(current_setting('app.owners', true), ','))::text[]));
  ```

  - A restrictive policy is combined with AND with the tenant policy. A row needs both the right tenant and an
    allowed owner.
  - It must never be permissive: permissive policies are combined with OR, and a second one would widen access
    beyond the tenant. The tenant data map fails CI on a second permissive policy on any table.
  - Each setting is read in a scalar subquery, so Postgres evaluates it once per query, not once per row.
  - Three forms, by what `owner_id` means in the table:

    | Form | Tables | Reads | Writes |
    | --- | --- | --- | --- |
    | Owner rows | most tables | the session's owners | the session's owners |
    | Shared defaults | `allocation_policy`, `billing_rate` (`owner_id = ''` is the site default) | also `''` | `''` only by a session unrestricted by owner |
    | Shared tags | `rfid_tag` (null owner for LPN and location tags) | also null | also null |
- **Physical checks (`OwnerVisibility.allOwners`).** Some checks are facts about the warehouse, not about one owner's
  stock, and must see every owner's rows of the tenant:
  - whether a location that refuses mixed items already holds another owner's item;
  - whether an LPN id is already in use;
  - whether a slot is empty, and a pick face's rank on the travel path;
  - whether a location is already another item's pick face;
  - the next material issue number, which is unique in the site;
  - whether an EPC is already commissioned, and whether an LPN being tagged belongs to another owner;
  - what a location count compares with: everything in the location.

  `allOwners` sets `app.owners = *` for the duration of the check only, inside the caller's transaction, and then
  restores the user's owners, also when the check fails. It returns a decision, never another owner's rows. Every
  call carries an `all-owners: <reason>` comment (`scripts/check-architecture.sh` rule 5).
- **Documents and what holds them (increment 3).** Headers (receipt, order, return) carry no owner. The document rule of
  ADR-0012 ("visible and workable only if all lines belong to the user's owners") stays in the services, and now
  covers every path that reaches a document:

  | What | Rule for an owner-scoped user | Otherwise |
  | --- | --- | --- |
  | Receipt (`ReceiptScope`) | all expectation lines in scope | 404 |
  | Order (`OrderScope`) | all order lines in scope | 404 |
  | Return (`ReturnScope`) | all lines **and** received units in scope (a blind return has units, no lines) | 404 |
  | Carton | its order in scope | 404 |
  | Wave, load | every order on it in scope | 403 if it also holds orders in scope, 404 if none |

  - Each rule sees every owner's lines (`allOwners`). With line RLS a user cannot see another owner's lines, so "no
    line of another owner" would otherwise always hold, and a mixed document would be shown with part of its lines.
  - The wave and load gates close gaps that predate RLS: carton pack and close, wave release, hold and detail, and
    load close, tracking and removal had no owner check. With line RLS alone they would have acted on part of a wave
    or load: a wave released with another owner's lines never allocated, a load closed into a partial shipment.
  - Wave candidates (plan, create, release by cutoff) are filtered by the same rule, also across owners.
  - Setting another owner's outbound policy answers 403 `SCOPE_OWNER_DENIED`, not a database error.
- **Counts.** A location count compares the counted lines with everything in the location, whoever owns it. Whoever
  submits or approves it must be authorised for every owner involved; otherwise it is refused with 403
  `INV_COUNT_OWNER_SCOPE`, without naming the other owner. Without this, an unrestricted approver deciding a count that
  an owner-scoped counter submitted would have written off the other owners' stock, which that counter could not count;
  and before owner RLS, an owner-scoped approver could post another owner's adjustments.
- **Behaviour an owner-scoped user sees.**
  - Another owner's LPN reads as unknown: a move of it answers 422 `INV_LPN_UNKNOWN`, no longer 403
    `SCOPE_OWNER_DENIED`, so its existence is not disclosed (as ADR-0012 already does for reads).
  - Reusing another owner's LPN id is refused with `INV_LPN_OWNER_MISMATCH`, which names the owner only to a user
    who may see it ("belongs to another owner"). So is tagging another owner's LPN.
  - An EPC commissioned to another owner is refused with `INV_RFID_TAG_IN_USE`, "already commissioned to another
    owner".
  - A location that holds another owner's stock cannot be counted or approved by this user (`INV_COUNT_OWNER_SCOPE`).
  - Unchanged: an RFID read records sightings only of the tags the user may see, as before owner RLS.
- **Rollout.** Table by table. Before each table:
  - list the reads of the table that a physical check needs across owners, and route them through `allOwners`;
  - compare latency before and after.

  | Increment | Tables | State |
  | --- | --- | --- |
  | 1. Ledger | inventory: `inventory_balance`, `inventory_txn`, `lpn`, `allocation` | Done (inventory V21) |
  | 2. Inventory, rest | `serial_number`, `rfid_tag`, `stock_in_transit`, `material_issue`, replenishment, slotting, counts, billing and owner settings (16 tables) | Done (inventory V22) |
  | 3. Documents | inbound `receipt_expectation_line`, `return_line`, `return_unit`; outbound `outbound_line`, `outbound_owner_policy` | Done (inbound V8, outbound V15, with the document and container gates) |
  | 4. Rest | task-service (6 tables, including `task`), master-data `item`, `item_site`, `item_uom` | Open |

  The tenant data map lists every table with `owner_id` and shows which have the policy.
- **Measured cost (increment 1).** `pgbench` on 340,000 ledger rows of one tenant, with the production indexes:

  | Query | Tenant policy only | With owner policy, unrestricted | With owner policy, one owner |
  | --- | --- | --- | --- |
  | An item's balances at a site | 0.22–0.23 ms | 0.23–0.24 ms (+4%) | 0.16 ms (−31%) |
  | A location's contents | 0.14–0.15 ms | 0.15 ms (+4%) | 0.10 ms (−30%) |

  The same policy with the settings read on every row cost unrestricted sessions 5–13%.
- **Tests.** For each covered table, an owner-scoped session selects with no `WHERE` clause and sees only its owners'
  rows; one with no owner sees none. Changing another owner's rows changes nothing, and creating a row for another
  owner, or handing an own row to one, is refused (`OwnerIsolationIT`). The physical checks still see every owner
  (`OwnerIsolationInventoryIT`), and every inventory table with `owner_id` has a restrictive `owner_isolation` policy
  in the migrated database.

### D. Per-tenant data lifecycle: export, point restore, purge (G4)

- **Tenant data map.**
  - `scripts/tenant_tables.py` generates `docs/security/tenant-data-map.md` from the migrations: every table, its
    database, whether it has `tenant_id`, RLS and `owner_id`, and its foreign-key order.
  - CI fails when it is stale, as for the permission matrix.
  - A table without `tenant_id` must be on a reviewed list of global tables (`uom_catalogue`, `flyway_schema_history`,
    `inbox`, `outbox_key_sequence`).
- **Export.**
  - `deploy/vps/tenant-export.sh <tenant>` runs `COPY (select … where tenant_id = $1)` for every table on the map,
    as the schema owner, in foreign-key order.
  - It also exports the tenant's Keycloak users (without credentials).
  - It writes one archive encrypted with `BACKUP_PASSPHRASE`, with a manifest of row counts and checksums.
- **Import.** `tenant-import.sh <archive>` loads an archive into a deployment where the tenant does not exist. It
  checks the counts against the manifest.
  - Most ids are UUIDs or are scoped by tenant, so those rows do not collide.
  - Eight tables use a database sequence for their id; the data map lists them under "Sequence id". They include the
    stock ledger (`inventory_balance`, `inventory_txn`), `stock_in_transit`, `billing_event` and `task_event`.
    - The import leaves these ids out and lets the target assign new ones.
    - It inserts each such table's rows in id order, so the new ids keep the order the code relies on (for example
      the oldest transit line first).
    - No table refers to these ids, and the data map fails CI if one starts to. The code uses them only within a
      transaction, never in an API path.
  - The outbox is not exported. A tenant is exported only after its outbox has drained.
- **Point restore of one tenant.**
  1. Restore the backup into a throwaway container. The restore drill already does this.
  2. Export the tenant from it.
  3. Suspend the tenant in production and delete its rows.
  4. Import.
  5. Reactivate.

  Only that tenant goes back in time. Other tenants are untouched.
- **Purge.**
  - `tenant-purge.sh <tenant>` works only on a tenant that is `OFFBOARDING` for longer than its retention, and only
    with a recorded export, unless forced with a reason.
  - It deletes the tenant's rows on the map, disables and then deletes its Keycloak users, and sets `PURGED`.
  - Kafka messages expire with topic retention; outbox and inbox rows with housekeeping.
  - Backups still hold the data until they age out (14 days locally, `BACKUP_REMOTE_KEEP_DAYS` off the server). The
    offboarding record states the date the last copy expires.

### E. Fairness between tenants (G5)

- **Quotas in the services.** The gateway does not validate tokens, so a tenant claim it reads could be forged to
  spend another tenant's quota.
  - `TenantFilter` applies a per-tenant token bucket after token validation, in memory per replica, with the rate
    from the tenant's plan.
  - Above the limit it answers 429 `TENANT_RATE_LIMITED` with `Retry-After`.
  - The per-address limit of the gateway stays as the outer guard.
- **Relay fair share.** Within a relay partition, a batch takes at most `astra.outbox.tenant-share` (default 50%) of
  its rows from one tenant while other tenants have rows waiting. Order per key is unchanged, because a key never
  spans tenants.
- **Bulkhead share.** `ServiceClients` lets one tenant hold at most half of a client's concurrent calls, so one
  tenant's slow chain cannot fill the bulkhead.
- **Visibility.**
  - The outbox backlog, relay send time, consumer processing time, 429s and downstream calls gain a `tenant` tag.
  - Cardinality is acceptable up to a few hundred tenants. Above that, only the top N tenants are reported by name
    and the rest as `other`.
  - The Operations page shows the platform view to `PLATFORM_ADMIN`.

### F. Staff who serve several tenants (G6)

- **Attribute.** A user may carry a multi-valued `wms_tenants` attribute, set by a platform administrator only,
  instead of one `tenant_id`.
- **Request.** The web and RF clients send the chosen tenant in `X-Tenant-Id`. `TenantFilter` binds it only when it
  is listed in the token, and otherwise answers 403 `TENANT_MISMATCH`. Without a header and with more than one
  tenant, it answers 400 `TENANT_REQUIRED`.
- **Limit.** Roles and scopes apply to every listed tenant alike. A person who needs different roles in different
  tenants gets separate accounts. Per-tenant roles would need token exchange, which is deferred.
- **Audit.** The audit trail records the user and the bound tenant, as today.

### G. Tenancy rules in CI (G7)

- **Derived list.** `scripts/check-architecture.sh` derives the RLS-free tables from the migrations, through the
  tenant data map of D, instead of a fixed list.
- **Reads.** Every read of an RLS-free table must filter by `tenant_id`, or carry a `-- cross-tenant: <reason>`
  comment on the query (the relay, the retriers, the webhook dispatcher).
- **New tables.** A new table must have RLS or be on the reviewed list of global and platform tables.
- **Proof.** Each rule is shown to fail on a planted violation, as for ADR-0029.

### H. Moving a tenant to its own cell (G8)

- **What a cell is.** A complete deployment. Moving a tenant is an export from one cell and an import into another,
  through D.
  1. Suspend the tenant.
  2. Drain its outbox.
  3. Export, import, verify the counts.
  4. Switch the tenant's `cell`.
  5. Reactivate.
- **Routing** by tenant at the edge (the sign-in host or the gateway) is built when a second cell exists, not before.
  Until then, `cell` is recorded but always `main`.

## Plan

Each phase can ship on its own. Sizes are relative: S is a few days, M one to two weeks, L more than two weeks.

| Phase | Work | Size | Done when |
| --- | --- | --- | --- |
| **0. Guard rails** | G: tenancy rules in CI. D (part): `tenant_tables.py` and the data map. | S | CI fails on a planted table without RLS, and on an unfiltered read of `reservation_compensation` or `webhook_delivery`. The data map is generated and checked. |
| **1. Registry and fairness** | A: registry, platform API, `TenantChanged`, enforcement in every service, seed migration, Keycloak provisioning. E: per-tenant quotas, tenant metrics. | L | An unknown or suspended tenant is refused in every service, and its messages are skipped and recorded. One tenant over its quota gets 429 while another continues. The metrics show both. |
| **2. Data-layer owner isolation** | C, table by table, ledger first (increments 1–3 done: inventory, inbound and outbound documents). | M | For each covered table, an owner-scoped user with no `WHERE` clause sees only its owners' rows, and a write for another owner is refused. p95 latency changes by at most 10% on the covered endpoints. |
| **3. ERP per tenant** | B: `erp_connection`, per-tenant resolution and validation of `sourceSystem`. | M | Two tenants with different logical systems post and receive acknowledgements in one deployment (simulated SAP). A message whose `sourceSystem` is not its tenant's is refused. |
| **4. Data lifecycle** | D: export, import, point restore, purge. H: cell move procedure. | L | A tenant is exported and imported into an empty deployment with matching counts. One tenant is restored to last night's state while another keeps today's data. A purged tenant has no rows on the map. |
| **5. Multi-tenant staff** | F. Fair share in the relay and the bulkhead (rest of E). | M | A user with two tenants switches between them in the web and RF clients; a third tenant is refused. A flood from one tenant leaves another tenant's relay latency within its normal range. |

Phase 0 has no dependencies. Phases 2 and 3 depend on nothing in phase 1 and can run alongside it. Phase 4 needs
phase 1 (status for suspension and offboarding) and phase 0 (the data map).

## Open questions

These change the order, not the design:

1. **How many customers, and of what kind?**
   - Several independent companies on one deployment need phases 1, 3 and 4 before the second customer.
   - A single 3PL with many clients needs phase 2 first.
2. **Does each customer bring its own SAP system?** If every tenant shares one SAP system, phase 3 can wait.
3. **Data residency.** If a customer requires its data in a given region, that customer needs its own cell (H), and
   cell routing moves forward.
4. **Contractual restore and deletion times.** These set the retention in D and decide whether point restore of one
   tenant is a commitment or best effort.

## Consequences

- **Migrations.**
  - master-data: `tenant`.
  - Every service: a `tenant_status` projection, and `owner_isolation` policies.
  - sap-adapter: `erp_connection`.
- **Identity.** A new role `PLATFORM_ADMIN` that is not tied to a tenant. Its accounts are the most powerful in the
  system: MFA required, and kept to the operator.
- **Requests.** Every request gains a local tenant-status check and a token-bucket check. Neither calls another
  service.
- **Writes and reads.** RLS on owners adds a predicate to every query of a covered table. Each table is measured
  before and after.
- **Operations.** Point restore and purge are operator procedures with scripts. They become part of the runbook, and
  the restore drill gains a monthly single-tenant restore.
- **Not changed.**
  - The shared topics.
  - The database per service.
  - The trusted-subsystem rule for service accounts.
  - Deploying one customer per stack: it keeps working without any of this.
