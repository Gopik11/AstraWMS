-- ADR-0030 A: the tenant registry. The master data service owns it; every service reads it from the compacted topic
-- wms.platform.tenants.v1 (TenantRegistry) and refuses unknown, suspended and purged tenants.
--
-- Platform tables: read and written across tenants by the platform API (PLATFORM_ADMIN), so no row-level security;
-- scripts/tenant_tables.py lists them as reviewed RLS-free, and every query states it is cross-tenant.
create table tenant (
    tenant_id          text        primary key check (tenant_id ~ '^[A-Za-z0-9_-]{1,64}$'),
    display_name       text        not null,
    status             text        not null check (status in ('ACTIVE', 'SUSPENDED', 'OFFBOARDING', 'PURGED')),
    plan               text        not null default 'STANDARD',
    cell               text        not null default 'main',
    time_zone          text        not null default 'UTC',
    reporting_currency text        not null default 'USD' check (reporting_currency ~ '^[A-Z]{3}$'),
    -- every change increments version; TenantPublisher publishes each version above published_version
    version            bigint      not null default 1,
    published_version  bigint      not null default 0,
    created_at         timestamptz not null,
    created_by         text        not null,
    status_changed_at  timestamptz not null,
    status_changed_by  text        not null,
    status_reason      text
);
create index tenant_unpublished_idx on tenant (tenant_id) where version > published_version;

-- Who changed a tenant's status, when and why (suspension, offboarding, reactivation).
create table tenant_status_change (
    id          bigserial   primary key,
    tenant_id   text        not null references tenant (tenant_id),
    from_status text,
    to_status   text        not null,
    reason      text,
    changed_by  text        not null,
    changed_at  timestamptz not null
);
create index tenant_status_change_tenant_idx on tenant_status_change (tenant_id, changed_at);

-- Register every tenant master data already holds, so the first deploy refuses none of them. The migration runs as
-- the schema owner, which FORCE ROW LEVEL SECURITY subjects to the tenant policies like any role: the force is lifted
-- on the tables read here for this transaction only, and put back before it ends.
do $$
declare t text;
    sources text[] := array['site', 'zone', 'location', 'item', 'label_printer', 'lpn_series', 'printed_label'];
begin
    foreach t in array sources
    loop
        execute format('alter table %I no force row level security', t);
    end loop;
    insert into tenant (tenant_id, display_name, status, created_at, created_by, status_changed_at, status_changed_by,
                        status_reason)
    select x.tenant_id, x.tenant_id, 'ACTIVE', now(), 'migration', now(), 'migration',
           'Registered from the master data it already held (ADR-0030)'
    from (select tenant_id from site union select tenant_id from zone union select tenant_id from location
          union select tenant_id from item union select tenant_id from label_printer
          union select tenant_id from lpn_series union select tenant_id from printed_label) x
    where x.tenant_id ~ '^[A-Za-z0-9_-]{1,64}$';
    insert into tenant_status_change (tenant_id, from_status, to_status, reason, changed_by, changed_at)
    select tenant_id, null, 'ACTIVE', status_reason, 'migration', now() from tenant;
    foreach t in array sources
    loop
        execute format('alter table %I force row level security', t);
    end loop;
end $$;
