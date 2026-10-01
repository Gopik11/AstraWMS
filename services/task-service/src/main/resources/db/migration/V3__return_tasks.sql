-- RETURN tasks (OUT-EX-02 reverse pick): picked stock of a cancelled order back from outbound staging to stock.
alter table task drop constraint task_task_type_check;
alter table task add constraint task_task_type_check check (task_type in ('PUTAWAY', 'PICK', 'RETURN'));

create unique index task_return_allocation on task (tenant_id, allocation_id) where task_type = 'RETURN';
