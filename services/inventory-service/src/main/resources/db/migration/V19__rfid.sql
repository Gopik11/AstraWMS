-- RFID (ADR-0027). GS1-encoded tags (SGTIN-96, SSCC-96) are understood without registration: their EPC carries the
-- GTIN + serial or the SSCC. The registry records commissioned tags (what a handheld wrote, or a bin tag) and tags whose
-- EPC is not a GS1 key (GID, GIAI, vendor-encoded EPCs), and keeps where each tag was last seen.

create table rfid_tag (
    tenant_id          text        not null,
    epc                text        not null check (epc ~ '^[0-9A-F]{24,124}$'),
    scheme             text        not null,
    site_id            text        not null,
    owner_id           text,
    item_no            text,
    serial_no          text,
    lpn_id             text,
    location_id        text,
    status             text        not null check (status in ('ACTIVE', 'RETIRED')),
    commissioned_by    text        not null,
    commissioned_at    timestamptz not null,
    retired_at         timestamptz,
    last_seen_at       timestamptz,
    last_seen_location text,
    last_seen_by       text,
    primary key (tenant_id, epc),
    -- a tag identifies exactly one thing: a unit (item, optionally its serial), an LPN or a location
    check (num_nonnulls(item_no, lpn_id, location_id) = 1),
    check (item_no is null or owner_id is not null),
    check (serial_no is null or item_no is not null)
);
create index rfid_tag_lpn_idx on rfid_tag (tenant_id, site_id, lpn_id) where lpn_id is not null;
create index rfid_tag_item_idx on rfid_tag (tenant_id, owner_id, item_no, serial_no) where item_no is not null;
create index rfid_tag_location_idx on rfid_tag (tenant_id, site_id, location_id) where location_id is not null;

alter table rfid_tag enable row level security;
alter table rfid_tag force row level security;
create policy tenant_isolation on rfid_tag
    using (tenant_id = current_setting('app.tenant_id', true))
    with check (tenant_id = current_setting('app.tenant_id', true));
