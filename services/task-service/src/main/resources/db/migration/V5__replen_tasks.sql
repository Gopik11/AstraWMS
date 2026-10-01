-- REPLEN tasks (§7): move reserved stock from a reserve location to a forward pick location.
alter table task drop constraint task_task_type_check;
alter table task add constraint task_task_type_check check (task_type in ('PUTAWAY', 'PICK', 'RETURN', 'COUNT', 'REPLEN'));

alter table task add column replenishment_id uuid;
create unique index task_replenishment on task (tenant_id, replenishment_id) where task_type = 'REPLEN';
