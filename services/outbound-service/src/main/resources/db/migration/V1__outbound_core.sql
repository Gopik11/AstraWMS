-- AstraWMS Outbound Service: orders from ERP (IF-OB-001), allocation, pick release, shipping (IF-OB-003).

create table outbound_order (
    id                uuid        primary key,
    tenant_id         text        not null,
    site_id           text        not null,
    erp_doc_no        text        not null,
    order_type        text        not null,
    revision          bigint      not null,
    source_system     text        not null,
    ship_to           jsonb,                       -- snapshot (PII)
    carrier_scac      text,
    planned_gi_utc    timestamptz,
    status            text        not null check (status in
                          ('RELEASED', 'BACKORDERED', 'PICKED', 'SHIPPED', 'CONFIRMED', 'SHIP_ERROR', 'CANCELLED')),
    staging_location  text        not null,
    pick_lpn          text        not null,
    shipment_txn_id   varchar(16),
    tracking_no       text,
    bill_of_lading    text,
    shipped_at        timestamptz,
    erp_document      text,
    erp_error_class   text,
    erp_error_text    text,
    created_at        timestamptz not null,
    updated_at        timestamptz not null,
    unique (tenant_id, site_id, erp_doc_no)
);
create index outbound_order_status_idx on outbound_order (tenant_id, site_id, status);

create table outbound_line (
    order_id           uuid           not null references outbound_order (id) on delete cascade,
    tenant_id          text           not null,
    erp_line_ref       text           not null,
    owner_id           text           not null,
    item_no            text           not null,
    qty_requested      numeric(18, 3) not null,
    uom                text           not null,
    lot_no             text,
    base_uom           text,
    qty_requested_base numeric(18, 3),
    qty_allocated      numeric(18, 3) not null default 0,
    qty_picked         numeric(18, 3) not null default 0,
    qty_short          numeric(18, 3) not null default 0,
    primary key (order_id, erp_line_ref)
);

create table outbound_allocation (
    allocation_id uuid           primary key,
    tenant_id     text           not null,
    order_id      uuid           not null references outbound_order (id) on delete cascade,
    erp_line_ref  text           not null,
    location_id   text           not null,
    lpn_id        text           not null,
    lot_no        text           not null,
    qty           numeric(18, 3) not null,
    qty_picked    numeric(18, 3),
    qty_short     numeric(18, 3),
    status        text           not null check (status in ('OPEN', 'DONE', 'CANCELLED'))
);
create index outbound_allocation_order_idx on outbound_allocation (order_id);

do $$
declare t text;
begin
    foreach t in array array['outbound_order', 'outbound_line', 'outbound_allocation']
    loop
        execute format('alter table %I enable row level security', t);
        execute format('alter table %I force row level security', t);
        execute format($p$create policy tenant_isolation on %I
                         using (tenant_id = current_setting('app.tenant_id', true))
                         with check (tenant_id = current_setting('app.tenant_id', true))$p$, t);
    end loop;
end $$;
