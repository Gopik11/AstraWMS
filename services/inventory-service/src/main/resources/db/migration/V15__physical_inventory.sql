-- ADR-0022 full physical inventory: a wall-to-wall (or per-zone) count document. It opens a blind count for every
-- location in scope, can freeze those locations while counting, and posts all differences at once after review.

create table physical_inventory (
    id          uuid        primary key,
    tenant_id   text        not null,
    site_id     text        not null,
    pi_no       text        not null,
    zones       text[]      not null default '{}',     -- empty = every location of the site
    freeze_stock boolean    not null default true,
    status      text        not null check (status in ('PLANNED', 'COUNTING', 'POSTED', 'CANCELLED')),
    note        text,
    created_by  text        not null,
    created_at  timestamptz not null,
    started_by  text,
    started_at  timestamptz,
    posted_by   text,
    posted_at   timestamptz,
    unique (tenant_id, site_id, pi_no)
);

-- Locations frozen by a physical inventory in progress: no stock movement in or out, no allocation from them.
create table location_freeze (
    tenant_id   text not null,
    site_id     text not null,
    location_id text not null,
    pi_id       uuid not null references physical_inventory (id) on delete cascade,
    primary key (tenant_id, site_id, location_id)
);

alter table stock_count add column pi_id uuid references physical_inventory (id);
create index stock_count_pi_idx on stock_count (pi_id) where pi_id is not null;
alter table stock_count drop constraint stock_count_trigger_check;
alter table stock_count add constraint stock_count_trigger_check check (trigger in ('ADHOC', 'SHORT_PICK', 'PHYSICAL'));

-- A global default reason (tenant_id null) passes no tenant policy: the migration (table owner) inserts it with the
-- forced row-level security lifted for this statement only.
alter table reason_code no force row level security;
insert into reason_code (tenant_id, code, description, applies_to, requires_approval, erp_relevant) values
    (null, 'PI_DIFF', 'Physical inventory difference', 'ADJUSTMENT', true, true);
alter table reason_code force row level security;

do $$
declare t text;
begin
    foreach t in array array['physical_inventory', 'location_freeze']
    loop
        execute format('alter table %I enable row level security', t);
        execute format('alter table %I force row level security', t);
        execute format($p$create policy tenant_isolation on %I
                         using (tenant_id = current_setting('app.tenant_id', true))
                         with check (tenant_id = current_setting('app.tenant_id', true))$p$, t);
    end loop;
end $$;
