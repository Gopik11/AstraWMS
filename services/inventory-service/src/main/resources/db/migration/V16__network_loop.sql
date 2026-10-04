-- ADR-0025 network loop.

-- Stock in transit between sites: written when a transfer is issued at the shipping site, reduced when the receiving
-- site receives against the same transfer. Main reduction = satellite increase + what is still in transit.
create table stock_in_transit (
    id            bigserial      primary key,
    tenant_id     text           not null,
    transfer_no   text           not null,
    from_site     text           not null,
    to_site       text           not null,
    owner_id      text           not null,
    item_no       text           not null,
    lot_no        text           not null default '',
    qty           numeric(18, 3) not null check (qty > 0),
    qty_received  numeric(18, 3) not null default 0 check (qty_received >= 0),
    shipped_at    timestamptz    not null,
    received_at   timestamptz,
    operation_id  uuid           not null,
    unique (tenant_id, transfer_no, owner_id, item_no, lot_no)
);
create index stock_in_transit_open_idx on stock_in_transit (tenant_id, to_site, item_no) where qty_received < qty;

-- Inventory ownership (who owns the stock an owner ID holds): own stock, consignment (supplier's until consumed),
-- customer-owned, supplier-owned. One ledger; the type is an attribute of the owner.
create table owner_profile (
    tenant_id      text        not null,
    owner_id       text        not null,
    name           text,
    ownership_type text        not null default 'OWN'
                     check (ownership_type in ('OWN', 'CONSIGNMENT', 'CUSTOMER_OWNED', 'SUPPLIER_OWNED')),
    updated_by     text        not null,
    updated_at     timestamptz not null,
    primary key (tenant_id, owner_id)
);

-- Store replenishment policy per store and item: min/max, safety stock and the transit days from its source.
create table store_stock_policy (
    tenant_id     text           not null,
    site_id       text           not null,
    owner_id      text           not null,
    item_no       text           not null,
    min_qty       numeric(18, 3) not null check (min_qty >= 0),
    max_qty       numeric(18, 3) not null,
    safety_qty    numeric(18, 3) not null default 0 check (safety_qty >= 0),
    transit_days  integer        not null default 1 check (transit_days between 0 and 60),
    updated_by    text           not null,
    updated_at    timestamptz    not null,
    primary key (tenant_id, site_id, owner_id, item_no),
    check (max_qty >= min_qty)
);

-- A recommendation the supervisor accepted: the transfer it created counts as pipeline until it ships.
create table store_replenishment (
    id            uuid           primary key,
    tenant_id     text           not null,
    site_id       text           not null,
    source_site   text           not null,
    owner_id      text           not null,
    item_no       text           not null,
    qty           numeric(18, 3) not null check (qty > 0),
    required_date date           not null,
    reason        text           not null,
    confidence    text           not null check (confidence in ('HIGH', 'MEDIUM', 'LOW')),
    transfer_no   text           not null,
    accepted_by   text           not null,
    accepted_at   timestamptz    not null
);
create index store_replenishment_open_idx on store_replenishment (tenant_id, site_id, owner_id, item_no);

-- Risk-based cycle counting: days between counts per velocity class; a variance halves the interval for that
-- location until a clean count.
create table count_frequency (
    tenant_id text    not null,
    site_id   text    not null,
    a_days    integer not null default 30 check (a_days > 0),
    b_days    integer not null default 90 check (b_days > 0),
    c_days    integer not null default 180 check (c_days > 0),
    updated_by text   not null,
    updated_at timestamptz not null,
    primary key (tenant_id, site_id)
);
alter table stock_count drop constraint stock_count_trigger_check;
alter table stock_count add constraint stock_count_trigger_check check (trigger in ('ADHOC', 'SHORT_PICK', 'PHYSICAL', 'CYCLE'));

do $$
declare t text;
begin
    foreach t in array array['stock_in_transit', 'owner_profile', 'store_stock_policy', 'store_replenishment', 'count_frequency']
    loop
        execute format('alter table %I enable row level security', t);
        execute format('alter table %I force row level security', t);
        execute format($p$create policy tenant_isolation on %I
                         using (tenant_id = current_setting('app.tenant_id', true))
                         with check (tenant_id = current_setting('app.tenant_id', true))$p$, t);
    end loop;
end $$;
