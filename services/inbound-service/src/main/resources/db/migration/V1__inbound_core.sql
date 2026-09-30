-- AstraWMS Inbound Service: receipt expectations, receiving and ERP receipt confirmation (IF-IB-001 / IF-IB-002).

create table receipt_expectation (
    id                   uuid        primary key,
    tenant_id            text        not null,
    site_id              text        not null,
    erp_doc_no           text        not null,
    erp_doc_type         text        not null,
    revision             bigint      not null,
    source_system        text        not null,
    vendor_id            text,
    ship_from_gln        text,
    supplying_site_id    text,
    carrier_scac         text,
    expected_arrival_utc timestamptz not null,
    external_ref         text,
    bill_of_lading       text,
    container_no         text,
    seal_no              text,
    status               text        not null check (status in
                             ('NOT_STARTED', 'IN_PROGRESS', 'CLOSED', 'CONFIRMED', 'POSTING_FAILED', 'CANCELLED')),
    source_changed_at    timestamptz not null,
    created_at           timestamptz not null,
    updated_at           timestamptz not null,
    -- receipt close and ERP confirmation (INT-011: every confirmation reaches a terminal state)
    closed_at            timestamptz,
    closed_by            text,
    confirmation_txn_id  varchar(16),
    erp_document         text,
    erp_error_class      text,
    erp_error_text       text,
    unique (tenant_id, site_id, erp_doc_no)
);
create index receipt_expectation_status_idx on receipt_expectation (tenant_id, site_id, status);

create table receipt_expectation_line (
    expectation_id      uuid           not null references receipt_expectation (id) on delete cascade,
    tenant_id           text           not null,
    erp_line_ref        text           not null,
    owner_id            text           not null,
    item_no             text           not null,
    qty_expected        numeric(18, 3) not null check (qty_expected >= 0),
    uom                 text           not null,
    lot_no              text,
    vendor_lot_no       text,
    po_no               text,
    po_line             text,
    stock_type_target   text           not null check (stock_type_target in ('AVAILABLE', 'QI', 'BLOCKED')),
    over_tolerance_pct  numeric(5, 2)  not null default 0,
    under_tolerance_pct numeric(5, 2)  not null default 100,
    qty_received        numeric(18, 3) not null default 0,
    short_reason        text,
    primary key (expectation_id, erp_line_ref)
);

-- Expected handling units from the ASN (SSCC single-scan receipt, INB-011).
create table expected_hu (
    expectation_id uuid           not null references receipt_expectation (id) on delete cascade,
    tenant_id      text           not null,
    site_id        text           not null,
    sscc           varchar(18)    not null,
    erp_line_ref   text           not null,
    qty            numeric(18, 3) not null,
    uom            text           not null,
    lot_no         text,
    received       boolean        not null default false,
    primary key (expectation_id, sscc, erp_line_ref)
);
create index expected_hu_sscc_idx on expected_hu (tenant_id, site_id, sscc);

-- One row per RF receive action (line or SSCC): idempotency key, request hash and stored response (NFR-123).
create table receive_request (
    tenant_id       text        not null,
    idempotency_key text        not null,
    request_hash    text        not null,
    response        jsonb,
    created_at      timestamptz not null,
    primary key (tenant_id, idempotency_key)
);

-- One row per received line quantity; its idempotency key is the one chained to the inventory call (ADR-0005).
create table receipt_txn (
    id                     uuid           primary key,
    tenant_id              text           not null,
    expectation_id         uuid           not null references receipt_expectation (id),
    erp_line_ref           text           not null,
    idempotency_key        text           not null,
    qty                    numeric(18, 3) not null check (qty > 0),
    uom                    text           not null,
    lot_no                 text,
    vendor_lot_no          text,
    expiry_date            date,
    lpn_id                 text,
    location_id            text           not null,
    stock_status           text           not null,
    inventory_operation_id uuid           not null,
    override_reason        text,
    approved_by            text,
    received_by            text           not null,
    received_at            timestamptz    not null,
    unique (tenant_id, idempotency_key)
);
create index receipt_txn_expectation_idx on receipt_txn (expectation_id);

-- ERP→WMS change/delete requests that were not applied (INB-EX-11), for the supervisor and the audit trail.
create table expectation_change_log (
    id             bigserial   primary key,
    tenant_id      text        not null,
    expectation_id uuid        not null references receipt_expectation (id) on delete cascade,
    revision       bigint      not null,
    action         text        not null,
    result         text        not null check (result in ('APPLIED', 'REJECTED', 'STALE')),
    reason_code    text,
    reason_text    text,
    logged_at      timestamptz not null
);

do $$
declare t text;
begin
    foreach t in array array['receipt_expectation', 'receipt_expectation_line', 'expected_hu', 'receive_request', 'receipt_txn',
                             'expectation_change_log']
    loop
        execute format('alter table %I enable row level security', t);
        execute format('alter table %I force row level security', t);
        execute format($p$create policy tenant_isolation on %I
                         using (tenant_id = current_setting('app.tenant_id', true))
                         with check (tenant_id = current_setting('app.tenant_id', true))$p$, t);
    end loop;
end $$;
