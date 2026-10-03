-- ADR-0022 controlled material issue: stock issued to a cost centre, WBS element or internal order (SAP 201/221/261)
-- on an approved issue request, returns of issued material (202/222/262), and the cost objects it may be issued to.

-- GTINs of an item's units, so issue scans can be checked by GTIN or GS1 label as on RF picks.
alter table ref_item_uom add column gtin text;

create table cost_object (
    tenant_id          text        not null,
    object_type        text        not null check (object_type in ('COST_CENTER', 'WBS', 'ORDER')),
    code               text        not null,
    description        text        not null,
    department         text,
    -- Users allowed to request issues to it; empty = anyone with a requesting role.
    allowed_requesters text[]      not null default '{}',
    active             boolean     not null default true,
    updated_by         text        not null,
    updated_at         timestamptz not null,
    primary key (tenant_id, object_type, code)
);

create table material_issue (
    id              uuid        primary key,
    tenant_id       text        not null,
    site_id         text        not null,
    issue_no        text        not null,
    owner_id        text        not null,
    object_type     text        not null,
    object_code     text        not null,
    recipient       text        not null,
    note            text,
    status          text        not null check (status in
                        ('REQUESTED', 'APPROVED', 'PARTIALLY_ISSUED', 'ISSUED', 'REJECTED', 'CANCELLED', 'CLOSED')),
    requested_by    text        not null,
    requested_at    timestamptz not null,
    decided_by      text,
    decided_at      timestamptz,
    decision_note   text,
    updated_at      timestamptz not null,
    unique (tenant_id, site_id, issue_no)
);
create index material_issue_status_idx on material_issue (tenant_id, site_id, status);

create table material_issue_line (
    issue_id      uuid           not null references material_issue (id) on delete cascade,
    tenant_id     text           not null,
    line_no       integer        not null,
    item_no       text           not null,
    lot_no        text,
    qty_requested numeric(18, 3) not null check (qty_requested > 0),
    uom           text           not null,
    qty_issued    numeric(18, 3) not null default 0,
    qty_returned  numeric(18, 3) not null default 0,
    primary key (issue_id, line_no)
);

-- Each issue or return scan: which inventory operation and SAP movement it became.
create table material_issue_move (
    id            bigserial      primary key,
    tenant_id     text           not null,
    issue_id      uuid           not null references material_issue (id) on delete cascade,
    line_no       integer        not null,
    kind          text           not null check (kind in ('ISSUE', 'RETURN')),
    qty           numeric(18, 3) not null,
    lot_no        text,
    location_id   text           not null,
    lpn_id        text,
    operation_id  uuid           not null,
    scan_key      text           not null,   -- the device's Idempotency-Key: a retried scan is answered, not redone
    wms_txn_id    text,
    moved_by      text           not null,
    moved_at      timestamptz    not null
);
create index material_issue_move_issue_idx on material_issue_move (issue_id);
create unique index material_issue_move_key_idx on material_issue_move (tenant_id, kind, scan_key);

do $$
declare t text;
begin
    foreach t in array array['cost_object', 'material_issue', 'material_issue_line', 'material_issue_move']
    loop
        execute format('alter table %I enable row level security', t);
        execute format('alter table %I force row level security', t);
        execute format($p$create policy tenant_isolation on %I
                         using (tenant_id = current_setting('app.tenant_id', true))
                         with check (tenant_id = current_setting('app.tenant_id', true))$p$, t);
    end loop;
end $$;
