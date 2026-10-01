# ADR-0012: Attribute Scopes on Roles: Sites, Owners, Zones and Approval Value Limits

- **Status:** Accepted
- **Date:** 2026-10-01
- **Extends:** ADR-0010, which checked roles only, at endpoint level

## Context

ADR-0010 answers *which functions* a user may use. The RBAC model in §G.5.1 also constrains *where and how much*:

- **Sites:** a supervisor of DC1 must not run DC2.
- **Owners:** a 3PL client sees only its own stock and orders; the matrix lists their inventory inquiry as "own".
- **Zones:** operators get work only in their zones.
- **Value thresholds:** for example, approve adjustments up to $5,000.

NFR-101 requires least privilege.

## Decision

1. **Scope claims in the token**, issued by the identity provider like the roles. Claim names are configurable under `astra.security.claims.*`.

   | Claim | Type | Meaning |
   |---|---|---|
   | `wms_sites` | string array | Sites the user may work in |
   | `wms_owners` | string array | Owners (3PL clients) whose stock and documents the user may see and change |
   | `wms_zones` | string array | Zones the user receives work in |
   | `approval_limit` | number | Highest value the user may approve, in the tenant reporting currency |

   - `*` means all.
   - **Missing site, owner or zone claims grant nothing** (`astra.security.claims.scope-when-missing=NONE`, least privilege).
   - A missing `approval_limit` means no value limit: the approver role alone decides, as before.
   - Locally, Keycloak keeps these as user attributes that only administrators can edit, with token mappers on the user clients. Smoke users get `*` unless a test narrows them.

2. **Service accounts and message consumers are unrestricted.** That is the trusted subsystem of ADR-0010: the user's request was checked where it entered. Relayed user tokens keep the user's scope downstream.

3. **Where scopes apply.**

   | Dimension | Applied in |
   |---|---|
   | Site | `{siteId}` path variable of every endpoint, reads included, through the shared `PathScopeInterceptor` (`403 SCOPE_SITE_DENIED`) |
   | Owner | `{ownerId}` path variables (`403 SCOPE_OWNER_DENIED`). Inventory commands check the owner of the body, the LPN or the allocation. Inventory lists (balances, transactions, allocations) are filtered. An LPN of another owner reads as not found. |
   | Owner (documents) | Receipts and outbound orders are visible and operable only if **all** their lines belong to the user's owners; others are 404 so their existence is not disclosed. Waves only take such orders. |
   | Zone | Task `next` hands out only tasks whose source or target location is in one of the user's zones (and of the user's owners). |
   | Value | Approvals (ADR-0010 `X-Approval-Token`) check the **approver's** scope: site and owner (`403 APPROVER_SCOPE_DENIED`), then value = item standard cost × base quantity against `approval_limit` (`403 APPROVAL_LIMIT_EXCEEDED`). Without a standard cost, a limited approver cannot approve (`422 APPROVAL_VALUE_UNKNOWN`). |

4. **Item standard cost.** The item master gets `standardCost` per base unit (master data API, `ItemUpserted` schema 1.1, additive). Inventory keeps it in its item reference. IF-MD-001 v0.2 maps it from SAP MBEW and the Oracle cost tables.

## Consequences

- A 3PL client user can be given read access to the WMS (e.g. role `RECEIVER` or a future `CLIENT` role, with `wms_owners=[ACME]`) without seeing other clients' stock or orders. `smoke-test.sh` shows this against Keycloak, together with a site-scoped supervisor (403 on another site) and approvers with limits of 20 and 100 on a variance worth 25.
- Scopes are data in the identity provider. Effective dates for temporary coverage and segregation-of-duties checks at assignment time (§G.5.1) belong there too; neither is built (no IdP-side workflow yet).
- Not yet scoped:
  - SAP adapter endpoints: the ERP middleware normally has `*`.
  - Master data site, zone and location changes beyond the site path.
  - Zones on inventory moves.
- These follow when the corresponding UI workspaces are built.
- A document with lines of several owners is visible only to users scoped to all of them. That is deliberate (no partial views of a delivery), but such documents are rare in 3PL operations.
