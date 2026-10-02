-- ADR-0021: item slotting for putaway (reserve zone, velocity) and RF MOVE tasks (reslot).
create table ref_item_slotting (
    tenant_id        text           not null,
    site_id          text           not null,
    owner_id         text           not null,
    item_no          text           not null,
    reserve_zone     text,
    units_per_pallet numeric(18, 3),
    velocity_class   text,
    changed_at       timestamptz    not null,
    primary key (tenant_id, site_id, owner_id, item_no)
);
alter table ref_item_slotting enable row level security;
alter table ref_item_slotting force row level security;
create policy tenant_isolation on ref_item_slotting
    using (tenant_id = current_setting('app.tenant_id', true))
    with check (tenant_id = current_setting('app.tenant_id', true));

alter table task drop constraint task_task_type_check;
alter table task add constraint task_task_type_check
    check (task_type in ('PUTAWAY', 'PICK', 'RETURN', 'COUNT', 'REPLEN', 'RECEIVE', 'MOVE'));
alter table task add column move_id uuid;
create unique index task_move on task (tenant_id, move_id) where task_type = 'MOVE';
