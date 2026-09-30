-- AstraWMS Master Data Service: core schema (release 0.1). RLS on every tenant table (see inventory V1).

-- Platform unit-of-measure catalogue (ISD-00 §3.1). Not tenant-scoped.
create table uom_catalogue (
    uom         text primary key,
    description text not null,
    dimension   text not null check (dimension in ('COUNT', 'WEIGHT', 'VOLUME', 'LENGTH'))
);
insert into uom_catalogue (uom, description, dimension) values
    ('EA', 'Each', 'COUNT'), ('IN', 'Inner pack', 'COUNT'), ('CS', 'Case', 'COUNT'), ('BX', 'Box', 'COUNT'),
    ('PAL', 'Pallet', 'COUNT'), ('KG', 'Kilogram', 'WEIGHT'), ('G', 'Gram', 'WEIGHT'), ('L', 'Litre', 'VOLUME'),
    ('M3', 'Cubic metre', 'VOLUME'), ('M', 'Metre', 'LENGTH'), ('CM', 'Centimetre', 'LENGTH');

create table item (
    tenant_id                     text        not null,
    owner_id                      text        not null,
    item_no                       text        not null,
    description                   text        not null,
    base_uom                      text        not null references uom_catalogue (uom),
    item_type                     text,
    status                        text        not null check (status in ('ACTIVE', 'BLOCKED_PROCUREMENT', 'BLOCKED_ALL', 'DELETED')),
    shelf_life_days               integer     check (shelf_life_days > 0),
    min_remaining_shelf_life_days integer     check (min_remaining_shelf_life_days >= 0),
    temperature_class             text,
    hazardous                     boolean     not null default false,
    source                        text        not null check (source in ('ERP', 'UI', 'API')),
    source_changed_at             timestamptz not null,
    version                       bigint      not null default 0,
    updated_at                    timestamptz not null,
    updated_by                    text        not null,
    primary key (tenant_id, owner_id, item_no)
);

create table item_site (
    tenant_id      text    not null,
    owner_id       text    not null,
    item_no        text    not null,
    site_id        text    not null,
    lot_controlled boolean not null,
    serial_control text    not null check (serial_control in ('NONE', 'INBOUND', 'OUTBOUND', 'FULL')),
    status         text    check (status in ('ACTIVE', 'BLOCKED_PROCUREMENT', 'BLOCKED_ALL', 'DELETED')),
    primary key (tenant_id, owner_id, item_no, site_id),
    foreign key (tenant_id, owner_id, item_no) references item on delete cascade
);

create table item_uom (
    tenant_id    text          not null,
    owner_id     text          not null,
    item_no      text          not null,
    uom          text          not null references uom_catalogue (uom),
    numerator    integer       not null check (numerator > 0),
    denominator  integer       not null check (denominator > 0),
    gtin         varchar(14),
    length_cm    numeric(13, 3),
    width_cm     numeric(13, 3),
    height_cm    numeric(13, 3),
    gross_weight_kg numeric(13, 3),
    primary key (tenant_id, owner_id, item_no, uom),
    foreign key (tenant_id, owner_id, item_no) references item on delete cascade
);
-- MD001-R08: GTIN unique within an owner.
create unique index item_uom_gtin_idx on item_uom (tenant_id, owner_id, gtin) where gtin is not null;

create table site (
    tenant_id  text not null,
    site_id    text not null,
    name       text not null,
    time_zone  text not null,
    erp_site   text not null,          -- SAP plant / Oracle inventory organization
    primary key (tenant_id, site_id)
);

-- Zones carry defaults that locations inherit unless they override them (scope §2.1 location hierarchy).
create table zone (
    tenant_id          text    not null,
    site_id            text    not null,
    zone_id            text    not null,
    zone_type          text    not null,
    erp_bucket         text    not null,       -- SAP storage location / Oracle subinventory
    temperature_class  text,
    hazmat_allowed     boolean not null default false,
    primary key (tenant_id, site_id, zone_id),
    foreign key (tenant_id, site_id) references site
);

create table location (
    tenant_id          text           not null,
    site_id            text           not null,
    location_id        text           not null,
    zone_id            text           not null,
    location_type      text           not null,
    check_digit        char(2)        not null,
    erp_bucket         text,                    -- null = inherit from zone
    temperature_class  text,                    -- null = inherit from zone
    hazmat_allowed     boolean,                 -- null = inherit from zone
    allow_mixed_items  boolean        not null default true,
    allow_mixed_lots   boolean        not null default true,
    max_weight_kg      numeric(13, 3),
    max_volume_m3      numeric(13, 3),
    pick_seq           integer,
    x                  numeric(10, 2),
    y                  numeric(10, 2),
    z                  numeric(10, 2),
    status             text           not null check (status in ('ACTIVE', 'BLOCKED', 'INACTIVE')),
    updated_at         timestamptz    not null,
    primary key (tenant_id, site_id, location_id),
    foreign key (tenant_id, site_id, zone_id) references zone
);
create index location_zone_idx on location (tenant_id, site_id, zone_id);

do $$
declare t text;
begin
    foreach t in array array['item', 'item_site', 'item_uom', 'site', 'zone', 'location']
    loop
        execute format('alter table %I enable row level security', t);
        execute format('alter table %I force row level security', t);
        execute format($p$create policy tenant_isolation on %I
                         using (tenant_id = current_setting('app.tenant_id', true))
                         with check (tenant_id = current_setting('app.tenant_id', true))$p$, t);
    end loop;
end $$;
