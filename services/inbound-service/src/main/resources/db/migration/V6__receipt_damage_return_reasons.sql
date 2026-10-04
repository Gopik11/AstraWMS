-- ADR-0025 damage at receive: a unit received damaged goes into stock as DAMAGED (never allocated) with the reason
-- and, when taken, a photo from the RF device. Kept per receipt transaction.
create table receipt_damage (
    id            uuid        primary key,
    tenant_id     text        not null,
    site_id       text        not null,
    erp_doc_no    text        not null,
    erp_line_ref  text        not null,
    item_no       text        not null,
    qty           numeric(18, 3) not null,
    uom           text        not null,
    reason        text        not null check (reason in ('CRUSHED', 'WET', 'TORN', 'BROKEN', 'CONTAMINATED', 'OTHER')),
    note          text,
    photo         bytea,
    photo_type    text,
    lpn_id        text,
    location_id   text        not null,
    idempotency_key text      not null,
    reported_by   text        not null,
    reported_at   timestamptz not null,
    unique (tenant_id, idempotency_key)
);
create index receipt_damage_doc_idx on receipt_damage (tenant_id, site_id, erp_doc_no);
alter table receipt_damage enable row level security;
alter table receipt_damage force row level security;
create policy tenant_isolation on receipt_damage
    using (tenant_id = current_setting('app.tenant_id', true))
    with check (tenant_id = current_setting('app.tenant_id', true));

-- Repair chain as a status on a returned unit sent to refurbish (no separate repair module).
alter table return_unit add column repair_status text
    check (repair_status in ('AWAITING_REPAIR', 'IN_REPAIR', 'REPAIRED', 'NOT_REPAIRABLE'));
alter table return_unit add column repair_updated_at timestamptz;
update return_unit set repair_status = 'AWAITING_REPAIR' where disposition = 'REFURBISH';
