-- Serial number tracking (INB-006, SHP-007, §6.2 "full serial lifecycle").
-- Items with serial control INBOUND or FULL are tracked from receipt; OUTBOUND-only items are captured at pack.
-- Invariant maintained by InventoryCommandService: for a tracked item, the number of IN_STOCK serials at a balance
-- key (site, owner, item, lot, LPN, location, status) equals the balance quantity.

create table serial_number (
    tenant_id         text        not null,
    owner_id          text        not null,
    item_no           text        not null,
    serial_no         text        not null,
    site_id           text        not null,
    lot_no            text        not null default '',
    lpn_id            text        not null default '',
    location_id       text        not null,
    stock_status      text        not null,
    status            text        not null check (status in ('IN_STOCK', 'REMOVED')),
    last_operation_id uuid        not null references inventory_operation (id),
    updated_at        timestamptz not null,
    primary key (tenant_id, owner_id, item_no, serial_no)
);
create index serial_number_key_idx on serial_number (tenant_id, site_id, owner_id, item_no, location_id, lpn_id);
create index serial_number_lpn_idx on serial_number (tenant_id, site_id, lpn_id) where lpn_id <> '';

alter table serial_number enable row level security;
alter table serial_number force row level security;
create policy tenant_isolation on serial_number
    using (tenant_id = current_setting('app.tenant_id', true))
    with check (tenant_id = current_setting('app.tenant_id', true));

-- The ledger records which serials each line moved (serial genealogy / recall trace, QM-005).
alter table inventory_txn add column serials text[];
