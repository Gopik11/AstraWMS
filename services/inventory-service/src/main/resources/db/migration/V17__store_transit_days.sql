-- ADR-0025 (review 2): transit days are a site value. A store's transit time from its source is set on the store;
-- an item policy may still override it. Policies seeded with the old fixed default (1) inherit the store's value.
create table store_site_setting (
    tenant_id    text        not null,
    site_id      text        not null,
    transit_days integer     not null check (transit_days between 0 and 60),
    updated_by   text        not null,
    updated_at   timestamptz not null,
    primary key (tenant_id, site_id)
);
alter table store_site_setting enable row level security;
alter table store_site_setting force row level security;
create policy tenant_isolation on store_site_setting
    using (tenant_id = current_setting('app.tenant_id', true))
    with check (tenant_id = current_setting('app.tenant_id', true));

alter table store_stock_policy alter column transit_days drop not null;
alter table store_stock_policy alter column transit_days drop default;
-- The migration runs outside any tenant: lift forced row-level security for this one update (as V15 does).
alter table store_stock_policy no force row level security;
update store_stock_policy set transit_days = null where transit_days = 1;
alter table store_stock_policy force row level security;
