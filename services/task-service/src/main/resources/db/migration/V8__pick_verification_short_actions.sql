-- ADR-0020: RF pick verification by item number or GTIN, and the operator's short-pick reason and action.

-- GTINs of an item's units (ItemUpserted 1.3), for item scans on RF.
create table ref_item_gtin (
    tenant_id text not null,
    owner_id  text not null,
    item_no   text not null,
    gtin      text not null,
    uom       text not null,
    primary key (tenant_id, owner_id, item_no, gtin)
);
alter table ref_item_gtin enable row level security;
alter table ref_item_gtin force row level security;
create policy tenant_isolation on ref_item_gtin
    using (tenant_id = current_setting('app.tenant_id', true))
    with check (tenant_id = current_setting('app.tenant_id', true));

alter table task add column short_reason text;
alter table task add column short_action text check (short_action in ('REALLOCATE', 'BACKORDER', 'SHIP_SHORT'));
