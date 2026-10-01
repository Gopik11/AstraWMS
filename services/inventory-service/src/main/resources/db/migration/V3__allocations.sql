-- Allocation (scope §3.4): inventory is the authority. An allocation reserves quantity on one balance
-- (inventory_balance.allocated_qty) for an order line; picking moves it to outbound staging still allocated
-- (so it cannot be re-allocated), and issue removes it from stock at shipment.

create table allocation (
    id               uuid           primary key,
    tenant_id        text           not null,
    site_id          text           not null,
    order_ref        text           not null,
    order_line_ref   text           not null,
    owner_id         text           not null,
    item_no          text           not null,
    lot_no           text           not null,
    lpn_id           text           not null,
    location_id      text           not null,
    qty_allocated    numeric(18, 3) not null check (qty_allocated >= 0),
    qty_picked       numeric(18, 3) not null default 0 check (qty_picked >= 0),
    picked_location  text,
    picked_lpn       text,
    status           text           not null check (status in ('OPEN', 'PICKED', 'RELEASED', 'ISSUED')),
    created_at       timestamptz    not null,
    updated_at       timestamptz    not null,
    check (qty_picked <= qty_allocated)
);
create index allocation_order_idx on allocation (tenant_id, site_id, order_ref);

alter table allocation enable row level security;
alter table allocation force row level security;
create policy tenant_isolation on allocation
    using (tenant_id = current_setting('app.tenant_id', true))
    with check (tenant_id = current_setting('app.tenant_id', true));

-- Shipped serials leave stock with their own status (§6.2 serial lifecycle).
alter table serial_number drop constraint serial_number_status_check;
alter table serial_number add constraint serial_number_status_check check (status in ('IN_STOCK', 'REMOVED', 'SHIPPED'));
