-- AstraWMS Inventory Service: core schema (release 0.1)
-- Every tenant-scoped table is protected by row-level security on app.tenant_id, which
-- TenantAwareDataSource sets on each connection checkout. FORCE makes the policy apply to the table owner too.

-- ---------------------------------------------------------------------------
-- Reference data projected from Master Data events (read-only for this service)
-- ---------------------------------------------------------------------------
create table ref_item (
    tenant_id         text        not null,
    owner_id          text        not null,
    item_no           text        not null,
    site_id           text        not null,
    base_uom          text        not null,
    lot_controlled    boolean     not null,
    serial_control    text        not null check (serial_control in ('NONE', 'INBOUND', 'OUTBOUND', 'FULL')),
    shelf_life_days   integer,
    temperature_class text,
    hazardous         boolean     not null default false,
    status            text        not null check (status in ('ACTIVE', 'BLOCKED_PROCUREMENT', 'BLOCKED_ALL', 'DELETED')),
    source_changed_at timestamptz not null,
    primary key (tenant_id, owner_id, item_no, site_id)
);

create table ref_item_uom (
    tenant_id   text    not null,
    owner_id    text    not null,
    item_no     text    not null,
    uom         text    not null,
    numerator   integer not null check (numerator > 0),
    denominator integer not null check (denominator > 0),
    primary key (tenant_id, owner_id, item_no, uom)
);

create table ref_location (
    tenant_id          text        not null,
    site_id            text        not null,
    location_id        text        not null,
    zone_id            text        not null,
    location_type      text        not null,
    erp_bucket         text        not null,
    temperature_class  text,
    hazmat_allowed     boolean     not null default false,
    allow_mixed_items  boolean     not null default true,
    allow_mixed_lots   boolean     not null default true,
    status             text        not null check (status in ('ACTIVE', 'BLOCKED', 'INACTIVE')),
    source_changed_at  timestamptz not null,
    primary key (tenant_id, site_id, location_id)
);

-- ---------------------------------------------------------------------------
-- Inventory
-- ---------------------------------------------------------------------------
create table lpn (
    tenant_id   text        not null,
    site_id     text        not null,
    lpn_id      text        not null,
    owner_id    text        not null,
    location_id text        not null,
    lpn_type    text        not null default 'PALLET',
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now(),
    primary key (tenant_id, site_id, lpn_id)
);
create index lpn_location_idx on lpn (tenant_id, site_id, location_id);

-- '' (not NULL) is used for "no lot" / "no LPN" so the natural key can be a plain unique constraint.
create table inventory_balance (
    id            bigserial      primary key,
    tenant_id     text           not null,
    site_id       text           not null,
    owner_id      text           not null,
    item_no       text           not null,
    lot_no        text           not null default '',
    lpn_id        text           not null default '',
    location_id   text           not null,
    stock_status  text           not null check (stock_status in ('AVAILABLE', 'QI', 'BLOCKED', 'DAMAGED', 'EXPIRED')),
    qty           numeric(18, 3) not null check (qty >= 0),                      -- INV-001: never negative
    allocated_qty numeric(18, 3) not null default 0 check (allocated_qty >= 0 and allocated_qty <= qty),
    expiry_date   date,
    receipt_date  timestamptz    not null,
    version       bigint         not null default 0,
    unique (tenant_id, site_id, owner_id, item_no, lot_no, lpn_id, location_id, stock_status)
);
create index inventory_balance_item_idx on inventory_balance (tenant_id, site_id, item_no);
create index inventory_balance_location_idx on inventory_balance (tenant_id, site_id, location_id);
create index inventory_balance_lpn_idx on inventory_balance (tenant_id, site_id, lpn_id) where lpn_id <> '';

-- One row per API operation; carries the idempotency key and the WMS transaction ID sent to the ERP.
create table inventory_operation (
    id              uuid        primary key,
    tenant_id       text        not null,
    site_id         text        not null,
    idempotency_key text        not null,
    request_hash    text        not null,
    op_type         text        not null,
    wms_txn_id      varchar(16) not null,
    created_by      text        not null,
    channel         text        not null,
    created_at      timestamptz not null,
    response        jsonb,
    unique (tenant_id, idempotency_key),
    unique (tenant_id, wms_txn_id)
);

-- Immutable inventory ledger (INV-006). Rows are never updated or deleted.
create table inventory_txn (
    id             bigserial      primary key,
    tenant_id      text           not null,
    site_id        text           not null,
    operation_id   uuid           not null references inventory_operation (id),
    txn_type       text           not null,
    owner_id       text           not null,
    item_no        text           not null,
    lot_no         text           not null,
    lpn_id         text           not null,
    location_id    text           not null,
    stock_status   text           not null,
    qty_delta      numeric(18, 3) not null,
    qty_after      numeric(18, 3) not null,
    reason_code    text,
    source_doc     text,
    user_id        text           not null,
    channel        text           not null,
    occurred_at    timestamptz    not null
);
create index inventory_txn_item_idx on inventory_txn (tenant_id, site_id, item_no, id);
create index inventory_txn_lpn_idx on inventory_txn (tenant_id, site_id, lpn_id, id);
create index inventory_txn_operation_idx on inventory_txn (operation_id);

create or replace function inventory_txn_immutable() returns trigger language plpgsql as $$
begin
    raise exception 'inventory_txn is append-only';
end $$;
create trigger inventory_txn_no_update before update or delete on inventory_txn
    for each row execute function inventory_txn_immutable();

-- One row per GoodsMovement sent to the ERP (IF-INV-001). wms_txn_id is the ERP idempotency key (INT-014);
-- an operation touching several items produces one movement per item (business key site + item).
create table erp_movement (
    tenant_id     text        not null,
    wms_txn_id    varchar(16) not null,
    operation_id  uuid        not null references inventory_operation (id),
    site_id       text        not null,
    item_no       text        not null,
    movement_type text        not null,
    created_at    timestamptz not null,
    primary key (tenant_id, wms_txn_id)
);
create index erp_movement_operation_idx on erp_movement (operation_id);

-- Adjustment reason codes (INV-002). tenant_id NULL = platform default visible to all tenants.
create table reason_code (
    tenant_id         text,
    code              text    not null,
    description       text    not null,
    applies_to        text    not null check (applies_to in ('ADJUSTMENT', 'STATUS_CHANGE', 'ANY')),
    requires_approval boolean not null default false,
    erp_relevant      boolean not null default true,
    unique nulls not distinct (tenant_id, code)
);

insert into reason_code (tenant_id, code, description, applies_to, requires_approval, erp_relevant) values
    (null, 'CC_TOL',   'Cycle count variance within tolerance', 'ADJUSTMENT',    false, true),
    (null, 'CC_VAR',   'Cycle count variance, approved',        'ADJUSTMENT',    true,  true),
    (null, 'FOUND',    'Found stock',                           'ADJUSTMENT',    true,  true),
    (null, 'LOST',     'Stock not found after investigation',   'ADJUSTMENT',    true,  true),
    (null, 'DAMAGE',   'Damaged in warehouse',                  'ANY',           false, true),
    (null, 'QA_HOLD',  'Quality hold',                          'STATUS_CHANGE', false, true),
    (null, 'QA_REL',   'Quality release',                       'STATUS_CHANGE', true,  true),
    (null, 'EXPIRY',   'Expired stock',                         'STATUS_CHANGE', false, true),
    (null, 'SYS_CORR', 'System correction (no ERP posting)',    'ANY',           true,  false);

-- ---------------------------------------------------------------------------
-- Row-level security
-- ---------------------------------------------------------------------------
do $$
declare t text;
begin
    foreach t in array array['ref_item', 'ref_item_uom', 'ref_location', 'lpn', 'inventory_balance',
                             'inventory_operation', 'inventory_txn', 'erp_movement']
    loop
        execute format('alter table %I enable row level security', t);
        execute format('alter table %I force row level security', t);
        execute format($p$create policy tenant_isolation on %I
                         using (tenant_id = current_setting('app.tenant_id', true))
                         with check (tenant_id = current_setting('app.tenant_id', true))$p$, t);
    end loop;
end $$;

alter table reason_code enable row level security;
alter table reason_code force row level security;
create policy tenant_or_default on reason_code
    using (tenant_id is null or tenant_id = current_setting('app.tenant_id', true))
    with check (tenant_id = current_setting('app.tenant_id', true));
