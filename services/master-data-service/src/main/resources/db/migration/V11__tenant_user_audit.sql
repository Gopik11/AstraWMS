-- ADR-0031: who changed which sign-in account of a tenant, and how. A tenant's administrators create, change, disable
-- and reset the tenant's accounts through the users API; Keycloak holds the accounts and passwords, this table holds
-- the trail. No password, temporary or not, is ever stored.
create table tenant_user_audit (
    id          bigserial   primary key,
    tenant_id   text        not null,
    user_id     text        not null,
    username    text        not null,
    action      text        not null check (action in ('CREATED', 'UPDATED', 'ENABLED', 'DISABLED', 'PASSWORD_RESET')),
    detail      text,
    changed_by  text        not null,
    changed_at  timestamptz not null
);
create index tenant_user_audit_tenant_idx on tenant_user_audit (tenant_id, changed_at desc);

alter table tenant_user_audit enable row level security;
alter table tenant_user_audit force row level security;
create policy tenant_isolation on tenant_user_audit
    using (tenant_id = current_setting('app.tenant_id', true))
    with check (tenant_id = current_setting('app.tenant_id', true));
