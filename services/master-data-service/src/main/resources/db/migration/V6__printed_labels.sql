-- ADR-0024 label lifecycle: every label printed (or previewed) is recorded once per site, type and barcode, so it can
-- be verified by a scan after it is stuck on, reprinted, or voided (a voided LPN number is never reused or reprinted).
create table printed_label (
    id          uuid        primary key,
    tenant_id   text        not null,
    site_id     text        not null,
    label_type  text        not null check (label_type in ('LOCATION', 'ITEM', 'LPN')),
    barcode     text        not null,
    scan_key    text        not null,          -- the barcode as a scanner reads it (no GS1 parentheses)
    lines       text[]      not null default '{}',
    zpl         text        not null,
    status      text        not null check (status in ('PRINTED', 'VERIFIED', 'VOID')),
    print_count integer     not null default 1,
    printer     text,                          -- null: shown in the browser preview, printed from there
    printed_by  text        not null,
    printed_at  timestamptz not null,
    verified_by text,
    verified_at timestamptz,
    voided_by   text,
    voided_at   timestamptz,
    void_reason text,
    unique (tenant_id, site_id, label_type, barcode)
);
create index printed_label_scan_idx on printed_label (tenant_id, site_id, scan_key);

alter table printed_label enable row level security;
alter table printed_label force row level security;
create policy tenant_isolation on printed_label
    using (tenant_id = current_setting('app.tenant_id', true))
    with check (tenant_id = current_setting('app.tenant_id', true));
