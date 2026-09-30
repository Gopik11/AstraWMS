# ADR-0003: Tenant Isolation with PostgreSQL Row-Level Security and a Non-Owner Application Role

- **Status:** Accepted
- **Date:** 2026-09-30

## Context

MWH-001 and NFR-103 require that cross-tenant and cross-owner access is impossible at the data layer, not only in application code.

## Decision

1. Every tenant-scoped table has `tenant_id` and an RLS policy `tenant_id = current_setting('app.tenant_id', true)` for both `USING` and `WITH CHECK`, with `FORCE ROW LEVEL SECURITY`.
2. `TenantAwareDataSource` (astra-common) runs `set_config('app.tenant_id', <tenant>, false)` on **every** connection checkout. When no tenant is bound it sets an empty string, so a pooled connection can never carry a previous request's tenant.
3. The tenant is bound by `TenantFilter` for HTTP requests (header set by the API gateway from the validated token) and by `TenantContext.callAs` for consumers and jobs.
4. **Two database roles:**
   - The schema owner runs Flyway migrations.
   - The service connects as `astra_app`: non-owner, `NOBYPASSRLS`, least-privilege grants (`V1_1__app_role_grants.sql`).

   Superusers and table owners bypass RLS, so a service connected as the owner would silently lose isolation. Tests therefore also connect as `astra_app`.
5. Platform tables (`outbox`, `outbox_key_sequence`, `inbox`) have no RLS, because the relay works across tenants.

## Consequences

- Isolation is verified by tests. A tenant cannot see or address another tenant's stock, even through SQL without a `WHERE` clause (`TenancyAndConcurrencyIT`, `MasterDataIT`).
- Role provisioning is an infrastructure responsibility: Terraform in environments, `deploy/postgres/init` locally. Migrations contain no credentials.
- Very large tenants can move to a dedicated database cell with no code change (NFR-002).
