-- ADR-0021: why a line is short, and which stock movement recovered it.
alter table outbound_line add column short_reason text;
alter table outbound_line add column short_detail text;

create table outbound_recovery (
    id            bigserial      primary key,
    tenant_id     text           not null,
    order_id      uuid           not null references outbound_order (id) on delete cascade,
    erp_line_ref  text           not null,
    qty           numeric(18, 3) not null,
    trigger       text           not null,   -- AUTOMATIC (stock arrived) or MANUAL (supervisor reallocation)
    txn_type      text,                      -- inventory transaction that freed the stock (MOVE_IN, RETURN_IN, ...)
    location_id   text,
    lpn_id        text,
    operation_id  uuid,
    recovered_by  text           not null,
    recovered_at  timestamptz    not null
);
create index outbound_recovery_order_idx on outbound_recovery (order_id);
alter table outbound_recovery enable row level security;
alter table outbound_recovery force row level security;
create policy tenant_isolation on outbound_recovery
    using (tenant_id = current_setting('app.tenant_id', true))
    with check (tenant_id = current_setting('app.tenant_id', true));
