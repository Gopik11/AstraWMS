-- ADR-0030 A: the sign-in accounts the platform API created in Keycloak for a tenant (its first administrators).
-- Keycloak holds the account and its password; this table records who asked for which account, and when, so the
-- operator can answer "who made this administrator". No password, temporary or not, is ever stored.
--
-- A platform table like the registry: read and written across tenants by the platform API (PLATFORM_ADMIN), so no
-- row-level security; scripts/tenant_tables.py lists it as reviewed RLS-free.
create table tenant_admin_account (
    id               bigserial   primary key,
    tenant_id        text        not null references tenant (tenant_id),
    username         text        not null unique,
    identity_user_id text        not null,
    email            text,
    created_by       text        not null,
    created_at       timestamptz not null
);
create index tenant_admin_account_tenant_idx on tenant_admin_account (tenant_id, created_at);
