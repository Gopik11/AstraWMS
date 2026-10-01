-- PICK tasks (scope §4): one per inventory allocation, requested by the outbound service.
alter table task drop constraint task_task_type_check;
alter table task add constraint task_task_type_check check (task_type in ('PUTAWAY', 'PICK'));

alter table task add column allocation_id  uuid;
alter table task add column order_ref      text;
alter table task add column order_line_ref text;
alter table task add column item_no        text;
alter table task add column lot_no         text;
alter table task add column qty            numeric(18, 3);
alter table task add column uom            text;
alter table task add column to_lpn         text;
alter table task add column qty_picked     numeric(18, 3);

create unique index task_pick_allocation on task (tenant_id, allocation_id) where task_type = 'PICK';
-- PUTAWAY tasks are created from inventory events and have a source operation; PICK tasks do not.
alter table task alter column source_operation_id drop not null;
