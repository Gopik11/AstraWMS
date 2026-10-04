-- ADR-0025 batch and cluster picking: picks assigned together to one operator for one trip. The tasks stay ordinary
-- PICK tasks (each confirmed on its own, same API for RF, voice, pick-to-light or a robot); the group records how they
-- were taken: CLUSTER (several orders into separate totes) or BATCH (one item from one bin for several orders).
alter table task add column pick_group uuid;
alter table task add column pick_mode text check (pick_mode in ('CLUSTER', 'BATCH'));
create index task_pick_group_idx on task (tenant_id, pick_group) where pick_group is not null;
