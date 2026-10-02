-- ADR-0021 release policy: carrier cutoffs, wave hold, ship-complete orders and owner rules.

-- The local time each carrier's trailer leaves the site; an order's cutoff is that time on its goods-issue date.
create table outbound_carrier_cutoff (
    tenant_id    text        not null,
    site_id      text        not null,
    carrier_scac text        not null,
    cutoff_time  time        not null,
    updated_by   text        not null,
    updated_at   timestamptz not null,
    primary key (tenant_id, site_id, carrier_scac)
);

-- Rules of an owner (3PL client) that override the site's: ship complete, pack list and label template.
create table outbound_owner_policy (
    tenant_id      text        not null,
    owner_id       text        not null,
    ship_complete  boolean,
    pack_list      boolean     not null default false,
    label_template text,
    updated_by     text        not null,
    updated_at     timestamptz not null,
    primary key (tenant_id, owner_id)
);

alter table outbound_site_config add column timezone text not null default 'UTC';
alter table outbound_site_config add column ship_complete boolean not null default false;

alter table outbound_order add column ship_complete boolean not null default false;
alter table outbound_order add column cutoff_at timestamptz;
create index outbound_order_cutoff_idx on outbound_order (tenant_id, site_id, cutoff_at) where status = 'POOLED';

alter table outbound_wave drop constraint outbound_wave_status_check;
alter table outbound_wave add constraint outbound_wave_status_check check (status in ('PLANNED', 'HELD', 'RELEASED'));
alter table outbound_wave add column hold_reason text;
alter table outbound_wave add column held_by text;
alter table outbound_wave add column held_at timestamptz;

do $$
declare t text;
begin
    foreach t in array array['outbound_carrier_cutoff', 'outbound_owner_policy']
    loop
        execute format('alter table %I enable row level security', t);
        execute format('alter table %I force row level security', t);
        execute format($p$create policy tenant_isolation on %I
                         using (tenant_id = current_setting('app.tenant_id', true))
                         with check (tenant_id = current_setting('app.tenant_id', true))$p$, t);
    end loop;
end $$;
