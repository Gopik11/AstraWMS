-- COUNT tasks (§6.3): blind cycle counts requested by the inventory service; recounts exclude earlier counters.
alter table task drop constraint task_task_type_check;
alter table task add constraint task_task_type_check check (task_type in ('PUTAWAY', 'PICK', 'RETURN', 'COUNT'));

alter table task add column count_id       uuid;
alter table task add column count_sequence integer;
alter table task add column excluded_users text[] not null default '{}';

create unique index task_count_sequence on task (tenant_id, count_id, count_sequence) where task_type = 'COUNT';
