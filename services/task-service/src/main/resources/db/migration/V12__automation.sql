-- ADR-0021 automation adapter: zones worked by devices (pick-to-light, robots, AS/RS). Tasks starting in such a zone
-- are claimed by devices through the automation API, not handed out on RF, unless a device gave the task back.
create table automation_zone (
    tenant_id   text        not null,
    site_id     text        not null,
    zone_id     text        not null,
    device_type text        not null,
    enabled     boolean     not null default true,
    updated_by  text        not null,
    updated_at  timestamptz not null,
    primary key (tenant_id, site_id, zone_id)
);
alter table automation_zone enable row level security;
alter table automation_zone force row level security;
create policy tenant_isolation on automation_zone
    using (tenant_id = current_setting('app.tenant_id', true))
    with check (tenant_id = current_setting('app.tenant_id', true));

alter table task add column device_id text;
-- A device raised an exception: the task goes to people (RF) from now on.
alter table task add column automation_manual boolean not null default false;
