-- ADR-0020: the allocation policy of a site, explicit instead of implied by the code. Without a row the defaults apply
-- (the ADR-0019 behaviour): FEFO for lot-controlled items, FIFO for the others, pick faces first, and a reserve LPN is
-- taken whole only when the order covers it (broken only for items without a pick face).
create table allocation_policy (
    tenant_id       text        not null,
    site_id         text        not null,
    lot_rotation    text        not null check (lot_rotation in ('FEFO', 'FIFO')),
    other_rotation  text        not null check (other_rotation in ('FEFO', 'FIFO')),
    pick_face_first boolean     not null,
    full_lpn        text        not null check (full_lpn in ('COVERED_ONLY', 'SPLIT_ALLOWED', 'NEVER_SPLIT')),
    updated_by      text        not null,
    updated_at      timestamptz not null,
    primary key (tenant_id, site_id)
);
alter table allocation_policy enable row level security;
alter table allocation_policy force row level security;
create policy tenant_isolation on allocation_policy
    using (tenant_id = current_setting('app.tenant_id', true))
    with check (tenant_id = current_setting('app.tenant_id', true));
