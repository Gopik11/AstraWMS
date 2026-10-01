-- Min/max replenishment of forward locations (§7, RPL-001/002).

create table replen_rule (
    tenant_id   text           not null,
    site_id     text           not null,
    location_id text           not null,
    owner_id    text           not null,
    item_no     text           not null,
    min_qty     numeric(18, 3) not null check (min_qty >= 0),
    max_qty     numeric(18, 3) not null,
    active      boolean        not null default true,
    updated_by  text           not null,
    updated_at  timestamptz    not null,
    primary key (tenant_id, site_id, location_id, owner_id, item_no),
    check (max_qty > min_qty)
);

create table replenishment (
    id              uuid           primary key,
    tenant_id       text           not null,
    site_id         text           not null,
    location_id     text           not null,
    owner_id        text           not null,
    item_no         text           not null,
    lot_no          text           not null,
    qty             numeric(18, 3) not null check (qty > 0),
    source_location text           not null,
    source_lpn      text           not null,
    allocation_id   uuid           not null references allocation (id),
    trigger         text           not null check (trigger in ('MIN_MAX', 'MANUAL')),
    status          text           not null check (status in ('OPEN', 'DONE', 'CANCELLED')),
    operation_id    uuid,
    created_at      timestamptz    not null,
    completed_at    timestamptz
);
create index replenishment_open_idx on replenishment (tenant_id, site_id, location_id, owner_id, item_no)
    where status = 'OPEN';

alter table allocation drop constraint allocation_status_check;
alter table allocation add constraint allocation_status_check
    check (status in ('OPEN', 'PICKED', 'RELEASED', 'ISSUED', 'RETURNED', 'REPLENISHED'));

do $$
declare t text;
begin
    foreach t in array array['replen_rule', 'replenishment']
    loop
        execute format('alter table %I enable row level security', t);
        execute format('alter table %I force row level security', t);
        execute format($p$create policy tenant_isolation on %I
                         using (tenant_id = current_setting('app.tenant_id', true))
                         with check (tenant_id = current_setting('app.tenant_id', true))$p$, t);
    end loop;
end $$;
