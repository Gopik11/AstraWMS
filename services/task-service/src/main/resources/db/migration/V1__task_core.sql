-- AstraWMS Task Service: directed putaway (scope §2.2–2.4) and RF task execution.

-- Projections fed by events (read-only for this service) -------------------------------------------------------
create table ref_item (
    tenant_id         text        not null,
    owner_id          text        not null,
    item_no           text        not null,
    temperature_class text,
    hazardous         boolean     not null default false,
    source_changed_at timestamptz not null,
    primary key (tenant_id, owner_id, item_no)
);

create table ref_location (
    tenant_id         text        not null,
    site_id           text        not null,
    location_id       text        not null,
    zone_id           text        not null,
    location_type     text        not null,
    temperature_class text,
    hazmat_allowed    boolean     not null,
    allow_mixed_items boolean     not null,
    allow_mixed_lots  boolean     not null,
    status            text        not null,
    check_digit       text,
    pick_seq          integer,
    source_changed_at timestamptz not null,
    primary key (tenant_id, site_id, location_id)
);

-- Stock per balance key from InventoryChanged (qtyAfter); rows with qty 0 are deleted.
create table stock_projection (
    tenant_id   text           not null,
    site_id     text           not null,
    owner_id    text           not null,
    item_no     text           not null,
    lot_no      text           not null,
    lpn_id      text           not null,
    location_id text           not null,
    status      text           not null,
    qty         numeric(18, 3) not null,
    primary key (tenant_id, site_id, owner_id, item_no, lot_no, lpn_id, location_id, status)
);
create index stock_projection_location_idx on stock_projection (tenant_id, site_id, location_id);
create index stock_projection_lpn_idx on stock_projection (tenant_id, site_id, lpn_id) where lpn_id <> '';

-- Tasks -----------------------------------------------------------------------------------------------------------
create table task (
    id                     uuid        primary key,
    tenant_id              text        not null,
    site_id                text        not null,
    task_type              text        not null check (task_type in ('PUTAWAY')),
    status                 text        not null check (status in
                               ('RELEASED', 'ASSIGNED', 'EXCEPTION', 'COMPLETED', 'CANCELLED')),
    priority               integer     not null default 50,
    owner_id               text        not null,
    lpn_id                 text        not null,
    from_location          text        not null,
    target_location        text,
    strategy               text,
    exception_reason       text,
    excluded_locations     text[]      not null default '{}',
    assigned_to            text,
    confirmed_location     text,
    inventory_operation_id uuid,
    source_operation_id    uuid        not null,
    created_at             timestamptz not null,
    assigned_at            timestamptz,
    completed_at           timestamptz,
    updated_at             timestamptz not null
);
-- One open putaway per LPN; open tasks with a target reserve that location.
create unique index task_open_putaway_lpn on task (tenant_id, site_id, lpn_id)
    where task_type = 'PUTAWAY' and status in ('RELEASED', 'ASSIGNED', 'EXCEPTION');
create index task_queue_idx on task (tenant_id, site_id, status, priority desc, created_at);
create index task_target_idx on task (tenant_id, site_id, target_location) where status in ('RELEASED', 'ASSIGNED');

-- Task audit trail and labor events (LMS input, §C.2)
create table task_event (
    id        bigserial   primary key,
    tenant_id text        not null,
    task_id   uuid        not null references task (id),
    event     text        not null,
    detail    text,
    user_id   text        not null,
    at        timestamptz not null
);
create index task_event_task_idx on task_event (task_id, id);

do $$
declare t text;
begin
    foreach t in array array['ref_item', 'ref_location', 'stock_projection', 'task', 'task_event']
    loop
        execute format('alter table %I enable row level security', t);
        execute format('alter table %I force row level security', t);
        execute format($p$create policy tenant_isolation on %I
                         using (tenant_id = current_setting('app.tenant_id', true))
                         with check (tenant_id = current_setting('app.tenant_id', true))$p$, t);
    end loop;
end $$;
