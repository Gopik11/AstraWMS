# ADR-0031: Tenant Onboarding: Platform Console, Platform Administrators and Tenant User Administration

- **Status:** Accepted
- **Date:** 2026-11-06
- **Builds on:** ADR-0030 A (tenant registry, platform API, first administrator), ADR-0010 (tenant from the token),
  ADR-0012 (site, owner and zone scopes)

## Context

ADR-0030 built the tenant registry and its platform API. A new customer could be registered and given a first
administrator, but only with `curl` and the provisioner's client secret. Three things were still missing to install
AstraWMS for a new client without an engineer:

1. **No console for the operator.** ADR-0030 E left "a platform view for `PLATFORM_ADMIN`" open.
2. **No human platform administrator.** Only the provisioner's service account held `PLATFORM_ADMIN`.
3. **A tenant's administrator could not create the tenant's users.** ADR-0030 said they are created "in Keycloak".
   But every tenant shares one realm, so the Keycloak admin console (realm-management `manage-users`) shows and
   changes every tenant's users and can set any `tenant_id`, including `PLATFORM_ADMIN`. It cannot be given to a
   customer.

### Options considered

| Question | Options | Decision |
| --- | --- | --- |
| How is a new client installed? | (a) a new tenant in the shared deployment ("pool"); (b) a dedicated deployment ("silo") | (a) by default. (b) for data residency, contractual isolation, very large volume, or its own SAP system before ADR-0030 phase 3. A silo is the same software with one tenant, so the same console and steps apply in it. |
| Where does the operator work? | (a) the Keycloak admin console; (b) a separate admin application; (c) a section of the existing web app | (c). It has the same sign-in, build and deployment, and nothing new to host. (a) is too powerful and offers no registry. (b) duplicates the shell for one page. |
| How do tenant administrators manage users? | (a) Keycloak admin console with fine-grained permissions; (b) one realm or Keycloak organisation per tenant; (c) a tenant-scoped API in AstraWMS over the provisioner | (c). (a) cannot restrict by an attribute value in one realm. (b) changes the issuer, client and token model of ADR-0010 for every service. (c) reuses the provisioner of ADR-0030 and enforces the rules in one place. |

## Decision

### 1. Two separate administrator levels

| | Platform administrator (operator) | Tenant administrator (customer) |
| --- | --- | --- |
| Role | `PLATFORM_ADMIN`, no `tenant_id` | `SOLUTION_ADMIN`, with `tenant_id` |
| Created by | `scripts/platform-admin.sh` (Keycloak's own administrator, on the server) | the platform console (first one), then other tenant administrators |
| Sees | the tenant registry only: tenants, status, history, administrators created | its own tenant's data and users |
| Never | a tenant's warehouse data, or a tenant's screens | another tenant, a platform, service or integration role |
| MFA | password + authenticator app (TOTP), required at first sign-in | as the tenant's policy (passkeys, ADR-0023) |

The platform API binds no tenant, and every tenant API refuses a token without one (`TENANT_CLAIM_MISSING`). So a
platform administrator cannot read tenant data even by calling the API directly.

### 2. Platform console (web app)

- **Who gets it.** A sign-in without a tenant that holds `PLATFORM_ADMIN` gets the console (`PlatformApp`) instead of
  the WMS. Before, such a sign-in got "not assigned to a tenant". The console has no site selector and no tenant
  screens.
- **Tenants list.** All tenants with status, plan, time zone and currency, and a form to register a tenant: id, name,
  plan, time zone, currency.
- **Tenant page.**
  - Status, with the allowed changes (suspend, start offboarding, reactivate). A reason is required, and every change
    is kept in the history. Purge is not offered (ADR-0030 D).
  - An onboarding checklist.
  - Administrators: the list, and creating the first administrator. Its temporary password is shown once, with copy
    and hide.
  - Settings (name, plan, time zone, currency) and the status history.
- **API.** All of it uses the existing platform API of ADR-0030 A. No new platform endpoint.

### 3. Platform administrator accounts

- **Script.** `scripts/platform-admin.sh <username> [email]` creates one, locally or on the VPS (`VPS=root@host`).
- **How it creates the account.**
  - It runs `kcadm` inside the Keycloak container as Keycloak's administrator. The API never grants `PLATFORM_ADMIN`,
    and must not be able to.
  - The account has no tenant, the role `PLATFORM_ADMIN`, and the required actions `UPDATE_PASSWORD` and
    `CONFIGURE_TOTP`. From then on, Keycloak's browser flow asks for the code at every sign-in.
- **Secrets.** They travel only on standard input, never on a command line.
  - The Keycloak administrator's password is read on the server from `/opt/astrawms/.env` and never leaves it.
  - The temporary password is printed once.

### 4. Tenant user administration

- **API.** `/api/v1/users` (master data, role `SOLUTION_ADMIN`, tenant-bound like every tenant API):

  | Call | Does |
  | --- | --- |
  | `GET /users` | the tenant's accounts: roles, sites, owners, zones, approval limit, enabled, must change password, managed |
  | `GET /users/roles` | the roles an administrator may grant |
  | `POST /users` | creates an account; the response holds the temporary password once (`Cache-Control: no-store`) |
  | `PUT /users/{id}` | changes names, e-mail, roles, scope, approval limit, enabled; fields left out are kept; disabling also ends the user's sessions |
  | `POST /users/{id}/password` | a new temporary password, once |
  | `GET /users/audit` | who changed which account, how and when |

- **Rules (`TenantUserAdmin`).**
  - **Own tenant only.** An account of another tenant reads as unknown (404 `USER_UNKNOWN`). The provider checks the
    `tenant_id` of every account it returns or changes. The binding is never changed.
  - **Tenant roles only.** Grantable: `RECEIVER`, `PICKER`, `INV_ANALYST`, `INV_MANAGER`, `SUPERVISOR`, `QA_MANAGER`,
    `SOLUTION_ADMIN`. Anything else is refused with 400 `USER_ROLE_NOT_GRANTABLE`.
  - **Integration accounts are listed, not changed.** An account with any other role (`ERP_INTEGRATION`,
    `AUTOMATION`), or a client's service account, cannot be changed here (409 `USER_NOT_MANAGED`). The operator
    manages it.
  - **No wider than yourself.** An administrator scoped to sites, owners or zones grants only those. They cannot
    manage an account wider than their own (403 `USER_SCOPE_EXCEEDS`).
  - **Nobody changes their own access.** An administrator cannot change their own roles, scope or enabled state (409
    `USER_SELF_CHANGE`), so no one locks themselves out. Names and e-mail can be changed.
  - **Approval limit.** An amount of 0 or more, with at most two decimals, as the realm's user profile requires.
- **Audit.** Every change is recorded in `tenant_user_audit` (master data V11, tenant RLS). The record says who did
  what (`CREATED`, `UPDATED`, `ENABLED`, `DISABLED`, `PASSWORD_RESET`) and what changed. It never holds a password.
- **Identity provider.**
  - `KeycloakIdentityProvisioner` implements both `IdentityProvisioner` and `TenantUsers`, signed in as the
    provisioner (`manage-users`, `view-users`).
  - Accounts are found by the exact attribute query `q=tenant_id:<tenant>`, and the binding is checked again on each
    one.
  - No database transaction is held while Keycloak is called. When Keycloak fails, the answer is 503
    `USER_IDENTITY_UNAVAILABLE` and nothing is changed or recorded.
- **Web app.** *Admin → Users*:
  - the list, with search;
  - new user, with role checkboxes and a description of each role;
  - edit;
  - enable or disable, and reset password, both confirmed on the button;
  - the temporary password shown once;
  - the change trail.

### 5. Onboarding a client

`docs/operations/tenant-onboarding.md` is the runbook. In short:

1. **Decide pool or silo** (the table above).
2. **Operator.** Register the tenant in the console, create its first administrator, and hand over the temporary
   password securely.
3. **Tenant administrator.** Sign in, set a new password, then:
   - set up sites, zones and locations, items and owners (*Master data*);
   - set up users (*Users*).
4. **ERP.** Map the SAP plants to sites (*Master data*). The operator creates the tenant's integration account. One
   SAP system per tenant comes with ADR-0030 phase 3.
5. **Enforcement.** Once every tenant in use is registered, switch the deployment to `TENANT_REGISTRY_MODE=ENFORCE`.

## Consequences

- **No new service, endpoint family or infrastructure** beyond `/api/v1/users` and one tenant table in master data.
- **Keycloak's admin console is for the operator only.** Customers never get it.
- **The provisioner's secret is a platform secret.** Its account can create users in every tenant. It lives in master
  data's environment, and in the scripts only on the operator's machine.
- **Open:**
  - the tenant's ERP integration account from the console;
  - per-tenant password and MFA policies (Keycloak organisations, when the realm moves to them);
  - SCIM from a customer's IdP, which can replace the Users page for customers that have one (NFR-100).
