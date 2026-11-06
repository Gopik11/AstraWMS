# Onboarding a New Client (Tenant)

How to bring a new customer onto AstraWMS. The design behind it is in ADR-0030 (tenant registry and lifecycle) and
ADR-0031 (platform console, platform administrators, tenant user administration).

## 0. Pool or silo?

Every customer is a **tenant**. Isolation is enforced in the database (row-level security on `tenant_id`), in every
service, and in sign-in. Decide where the tenant lives:

| Choose | When |
| --- | --- |
| **A new tenant on the shared deployment** (default) | Most customers. Nothing to install: minutes to set up. |
| **A dedicated deployment** (`scripts/deploy-vps.sh` with its own `VPS` and `PUBLIC_URL`) | The data must stay in a given region or on the customer's own server; a contract requires a separate installation; very high volume; or the customer has its own SAP system before ADR-0030 phase 3 (one SAP connection per deployment until then). |

A dedicated deployment runs the same software with one tenant. The steps below are the same in it.

## 1. Once per deployment: a platform administrator

The operator's account manages tenants and never sees a tenant's data.

```bash
VPS=root@145.223.90.247 scripts/platform-admin.sh ops.jane jane@operator.example
```

- **Locally:** run it without `VPS`.
- **What it prints:** a temporary password, shown once.
- **At first sign-in:** Keycloak asks for a new password and the set-up of an authenticator app. It asks for the app's
  code at every sign-in after that.
- **Who gets one:** create one account per person, never a shared one.

## 2. Register the tenant (operator, platform console)

1. **Sign in** at the deployment's address with the platform administrator. The console opens on *Tenants*.
2. **Register a tenant:**

   | Field | What to enter |
   | --- | --- |
   | Tenant id | Short and permanent, e.g. `acme`. It is stamped on every account and record and cannot be changed. |
   | Name | The customer's name. |
   | Plan | `STANDARD` (100 requests/s) or `LARGE` (400/s). |
   | Time zone | The customer's. |
   | Reporting currency | The customer's. |

3. **Create the first administrator** on the tenant page:
   - a username (unique across the system, e.g. `admin@acme`) and an e-mail;
   - the account gets `SOLUTION_ADMIN` and every site, owner and zone of the tenant.
4. **Hand over the temporary password securely**, e.g. by phone or a password manager share, not in the same e-mail as
   the username. It is shown once. If it is lost, the tenant administrator (or a second one) resets it in *Users*.

## 3. Set up the tenant (the tenant's administrator)

1. **Sign in.** Keycloak asks for a new password. A passkey can be added from the top bar.
2. **Master data:**
   - sites (DC and stores, ADR-0024), zones and locations (or *Generate locations*);
   - owners and their rules;
   - items;
   - the outbound release and allocation policies.
3. **Users.** Create the tenant's users under *Admin → Users*:
   - roles, sites, owners and zones, and an approval limit where they approve adjustments;
   - each user gets a temporary password, shown once, and must change it at first sign-in;
   - disabled users are signed out at once;
   - every change is listed under *Changes*.
4. **Labels and printers** (*Labels*), when labels are printed.

## 4. ERP connection

1. **Plant mapping.** In *Master data → SAP plant → site*, map each SAP plant to its site.
2. **Integration account.** Created by the operator in the Keycloak admin console (an operator tool only):
   - attribute `tenant_id` = the tenant;
   - role `ERP_INTEGRATION`, and nothing else.

   It appears in the tenant's *Users* list as an integration account and cannot be changed there.
3. **One SAP system per deployment.** Until ADR-0030 phase 3 (per-tenant ERP connection), all tenants of a deployment
   share one SAP logical system (`SAP_LOGICAL_SYSTEM`). A customer with its own SAP system goes on a dedicated
   deployment until then.

## 5. Go-live checks

- **Registry status.** On the tenant's page in the console, the status is ACTIVE.
- **Every service follows the registry.** The tenant administrator's *Operations* page shows the registry as loaded
  in every service.
- **Enforcement.** While `TENANT_REGISTRY_MODE=REPORT`, unregistered tenants are only logged. Switch `/opt/astrawms/.env`
  to `TENANT_REGISTRY_MODE=ENFORCE`, then redeploy, once every tenant in use is registered and no service logs
  `Tenant registry (REPORT mode)` refusals (README, ADR-0030 A).
- **Backups.** The nightly backup covers the new tenant. One tenant can be exported with
  `deploy/vps/tenant-data.py export <tenant>`.

## Later in the tenant's life

| Need | Where |
| --- | --- |
| Invoice overdue, security incident | Console → tenant → **Suspend** (reason required). Sign-in and API are refused, ERP messages wait for replay, data is kept. **Reactivate** undoes it. |
| Contract ends | Console → **Start offboarding**: read-only for the customer's export (`tenant-data.py export`); purge after the retention period (ADR-0030 D). |
| A tenant administrator left | Another tenant administrator disables the account in *Users*. If there is none, the operator creates a new administrator in the console. |
| Lost password | *Users* → **Reset password**: a new temporary password, shown once. |
