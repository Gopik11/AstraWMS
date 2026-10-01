-- Cycle counting (§6.3): blind counts per location, tolerance auto-accept, recounts by other users, approval.

create table stock_count (
    id             uuid           primary key,
    tenant_id      text           not null,
    site_id        text           not null,
    location_id    text           not null,
    trigger        text           not null check (trigger in ('ADHOC', 'SHORT_PICK')),
    status         text           not null check (status in
                       ('OPEN', 'RECOUNT', 'PENDING_APPROVAL', 'ADJUSTED', 'CLOSED', 'REJECTED')),
    counts_done    integer        not null default 0,
    requested_by   text           not null,
    note           text,
    variance_value numeric(18, 2),
    decided_by     text,
    decided_at     timestamptz,
    operation_id   uuid,
    created_at     timestamptz    not null,
    updated_at     timestamptz    not null
);
-- One open count per location.
create unique index stock_count_open_location on stock_count (tenant_id, site_id, location_id)
    where status in ('OPEN', 'RECOUNT', 'PENDING_APPROVAL');
create index stock_count_status_idx on stock_count (tenant_id, site_id, status);

-- What each counter found (blind: the counter never sees the system quantity).
create table stock_count_result (
    count_id   uuid        not null references stock_count (id) on delete cascade,
    tenant_id  text        not null,
    sequence   integer     not null,
    counted_by text        not null,
    counted_at timestamptz not null,
    lines      jsonb       not null,
    primary key (count_id, sequence)
);

-- Variance of the latest evaluation, per balance key (all stock statuses summed).
create table stock_count_variance (
    count_id    uuid           not null references stock_count (id) on delete cascade,
    tenant_id   text           not null,
    owner_id    text           not null,
    item_no     text           not null,
    lot_no      text           not null,
    lpn_id      text           not null,
    system_qty  numeric(18, 3) not null,
    counted_qty numeric(18, 3) not null,
    unit_cost   numeric(18, 4),
    primary key (count_id, owner_id, item_no, lot_no, lpn_id)
);

do $$
declare t text;
begin
    foreach t in array array['stock_count', 'stock_count_result', 'stock_count_variance']
    loop
        execute format('alter table %I enable row level security', t);
        execute format('alter table %I force row level security', t);
        execute format($p$create policy tenant_isolation on %I
                         using (tenant_id = current_setting('app.tenant_id', true))
                         with check (tenant_id = current_setting('app.tenant_id', true))$p$, t);
    end loop;
end $$;
