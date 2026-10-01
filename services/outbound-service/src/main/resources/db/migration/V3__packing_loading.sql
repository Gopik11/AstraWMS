-- Packing (§5.1/5.2) and loading (§5.3): cartons with SSCC and carrier labels, loads (trailers) closed into shipments.
-- Stock stays on the order's pick LPN at outbound staging until ship; cartons record what was packed where.

alter table outbound_site_config add column pack_required boolean not null default false;

create sequence carton_serial_seq;

create table shipment_load (
    id           uuid        primary key,
    tenant_id    text        not null,
    site_id      text        not null,
    load_no      text        not null,
    carrier_scac text,
    door         text,
    trailer_no   text,
    seal_no      text,
    bol_no       text,
    status       text        not null check (status in ('OPEN', 'CLOSED')),
    created_by   text        not null,
    created_at   timestamptz not null,
    closed_by    text,
    closed_at    timestamptz,
    unique (tenant_id, site_id, load_no)
);

alter table outbound_order add column load_id uuid references shipment_load (id);

create table carton (
    id           uuid           primary key,
    tenant_id    text           not null,
    site_id      text           not null,
    order_id     uuid           not null references outbound_order (id) on delete cascade,
    sscc         varchar(18)    not null,
    carton_type  text           not null,
    status       text           not null check (status in ('OPEN', 'CLOSED')),
    weight_kg    numeric(10, 3),
    carrier_scac text,
    tracking_no  text,
    label        text,
    packed_by    text           not null,
    created_at   timestamptz    not null,
    closed_at    timestamptz,
    unique (tenant_id, sscc)
);
create index carton_order_idx on carton (order_id);

create table carton_item (
    carton_id    uuid           not null references carton (id) on delete cascade,
    tenant_id    text           not null,
    erp_line_ref text           not null,
    item_no      text           not null,
    qty          numeric(18, 3) not null check (qty > 0),
    primary key (carton_id, erp_line_ref)
);

do $$
declare t text;
begin
    foreach t in array array['shipment_load', 'carton', 'carton_item']
    loop
        execute format('alter table %I enable row level security', t);
        execute format('alter table %I force row level security', t);
        execute format($p$create policy tenant_isolation on %I
                         using (tenant_id = current_setting('app.tenant_id', true))
                         with check (tenant_id = current_setting('app.tenant_id', true))$p$, t);
    end loop;
end $$;

grant usage, select on sequence carton_serial_seq to ${appRole};
