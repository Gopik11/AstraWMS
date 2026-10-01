-- Customer returns (§8, IF-RET-001/002): RMAs from the ERP or blind returns, units received and graded with a
-- disposition, confirmed to the ERP in two steps (receipt, disposition).

create sequence blind_return_seq;

create table return_order (
    id                   uuid        primary key,
    tenant_id            text        not null,
    site_id              text        not null,
    rma_no               text        not null,
    return_type          text        not null check (return_type in ('CUSTOMER', 'ECOM', 'RECALL', 'BLIND')),
    revision             bigint      not null,
    source_system        text        not null,
    campaign_id          text,
    customer_id          text,
    customer_name        text,                        -- PII
    expected_arrival_utc timestamptz,
    status               text        not null check (status in
                             ('EXPECTED', 'IN_PROGRESS', 'CLOSED', 'CONFIRMED', 'POSTING_FAILED', 'CANCELLED')),
    receipt_txn_id       varchar(16),
    disposition_txn_id   varchar(16),
    erp_document         text,
    erp_error_class      text,
    erp_error_text       text,
    created_at           timestamptz not null,
    updated_at           timestamptz not null,
    closed_at            timestamptz,
    unique (tenant_id, site_id, rma_no)
);

create table return_line (
    return_id           uuid           not null references return_order (id) on delete cascade,
    tenant_id           text           not null,
    erp_line_ref        text           not null,
    owner_id            text           not null,
    item_no             text           not null,
    qty_expected        numeric(18, 3) not null,
    uom                 text           not null,
    return_reason       text,
    expected_serials    text[]         not null default '{}',
    inspection_required boolean        not null default true,
    primary key (return_id, erp_line_ref)
);

create table return_unit (
    id                     uuid           primary key,
    tenant_id              text           not null,
    return_id              uuid           not null references return_order (id) on delete cascade,
    idempotency_key        text           not null,
    erp_line_ref           text,
    owner_id               text           not null,
    item_no                text           not null,
    qty                    numeric(18, 3) not null check (qty > 0),
    uom                    text           not null,
    lot_no                 text,
    serials                text[]         not null default '{}',
    condition_grade        text           not null check (condition_grade in ('A', 'B', 'C', 'D', 'E')),
    return_reason_actual   text,
    disposition            text           not null check (disposition in
                               ('RESTOCK', 'REFURBISH', 'RTV', 'SCRAP', 'QUARANTINE', 'LIQUIDATE')),
    stock_status           text           not null,
    wrong_item             boolean        not null default false,
    serial_flag            boolean        not null default false,
    over_rma               boolean        not null default false,
    location_id            text           not null,
    lpn_id                 text,
    inventory_operation_id uuid,
    received_by            text           not null,
    received_at            timestamptz    not null,
    unique (tenant_id, return_id, idempotency_key)
);

do $$
declare t text;
begin
    foreach t in array array['return_order', 'return_line', 'return_unit']
    loop
        execute format('alter table %I enable row level security', t);
        execute format('alter table %I force row level security', t);
        execute format($p$create policy tenant_isolation on %I
                         using (tenant_id = current_setting('app.tenant_id', true))
                         with check (tenant_id = current_setting('app.tenant_id', true))$p$, t);
    end loop;
end $$;

grant usage, select on sequence blind_return_seq to ${appRole};
