-- ADR-0019: RF receiving tasks, zone-aware putaway to the item's fixed pick faces, and the planned vs confirmed
-- putaway location.

-- The zone's role (PICK, RESERVE, DOCK, RECEIVING, RETURNS, SHIPPING, QC, ...), from LocationUpserted 1.2.
alter table ref_location add column zone_type text;

-- Fixed pick faces of items (the inventory service's min/max rules), from PickFaceChanged.
create table ref_pick_face (
    tenant_id   text           not null,
    site_id     text           not null,
    location_id text           not null,
    owner_id    text           not null,
    item_no     text           not null,
    max_qty     numeric(18, 3) not null,
    active      boolean        not null,
    changed_at  timestamptz    not null,
    primary key (tenant_id, site_id, location_id, owner_id, item_no)
);
alter table ref_pick_face enable row level security;
alter table ref_pick_face force row level security;
create policy tenant_isolation on ref_pick_face
    using (tenant_id = current_setting('app.tenant_id', true))
    with check (tenant_id = current_setting('app.tenant_id', true));

alter table task drop constraint task_task_type_check;
alter table task add constraint task_task_type_check
    check (task_type in ('PUTAWAY', 'PICK', 'RETURN', 'COUNT', 'REPLEN', 'RECEIVE'));

-- Putaway: target_location ends as where the LPN was actually put (override included); the engine's suggestion stays
-- in suggested_location.
alter table task add column suggested_location text;
update task set suggested_location = target_location where task_type = 'PUTAWAY';
update task set target_location = confirmed_location
    where task_type = 'PUTAWAY' and status = 'COMPLETED' and confirmed_location is not null;

-- RECEIVE: one open task per vendor delivery (ASN) or RMA; the RF device receives against it scan by scan.
alter table task add column receive_kind   text check (receive_kind in ('ASN', 'RMA'));
alter table task add column doc_no         text;
alter table task add column partner        text;
alter table task add column expected_lines jsonb;
alter table task add column scans          integer not null default 0;
create unique index task_open_receive on task (tenant_id, site_id, receive_kind, doc_no)
    where task_type = 'RECEIVE' and status in ('RELEASED', 'ASSIGNED', 'EXCEPTION');
create index task_search_idx on task (tenant_id, site_id, doc_no);
