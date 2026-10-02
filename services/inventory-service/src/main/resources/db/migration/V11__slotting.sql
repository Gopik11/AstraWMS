-- ADR-0021: item–location master (slotting). The pick face is the item's min/max rule; this adds where its reserve
-- stock belongs, how much fits on a pallet, and its velocity class (null = computed from pick history).
create table item_slotting (
    tenant_id        text           not null,
    site_id          text           not null,
    owner_id         text           not null,
    item_no          text           not null,
    reserve_zone     text,
    units_per_pallet numeric(18, 3) check (units_per_pallet > 0),
    velocity_class   text           check (velocity_class in ('A', 'B', 'C')),
    updated_by       text           not null,
    updated_at       timestamptz    not null,
    primary key (tenant_id, site_id, owner_id, item_no)
);
alter table item_slotting enable row level security;
alter table item_slotting force row level security;
create policy tenant_isolation on item_slotting
    using (tenant_id = current_setting('app.tenant_id', true))
    with check (tenant_id = current_setting('app.tenant_id', true));

-- Travel-path order of locations (LocationUpserted.pickSeq), for golden-zone suggestions.
alter table ref_location add column pick_seq integer;

create index inventory_txn_pick_history_idx on inventory_txn (tenant_id, site_id, txn_type, occurred_at);
