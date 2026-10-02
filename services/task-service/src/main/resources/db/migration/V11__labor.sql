-- ADR-0021 labor: engineered standards per task type, operator equipment and skills, and equipment a zone needs.
-- Labor is a policy on the task service, not a separate product: the board reads the task table and its events.

create table task_standard (
    tenant_id        text           not null,
    site_id          text           not null,
    task_type        text           not null,
    base_seconds     integer        not null check (base_seconds >= 0),
    per_unit_seconds numeric(10, 2) not null default 0 check (per_unit_seconds >= 0),
    required_skill   text,
    updated_by       text           not null,
    updated_at       timestamptz    not null,
    primary key (tenant_id, site_id, task_type)
);

-- A task whose from or to location is in such a zone goes only to operators with this equipment (e.g. REACH_TRUCK).
create table zone_equipment (
    tenant_id  text        not null,
    site_id    text        not null,
    zone_id    text        not null,
    equipment  text        not null,
    updated_by text        not null,
    updated_at timestamptz not null,
    primary key (tenant_id, site_id, zone_id)
);

create table operator_profile (
    tenant_id  text        not null,
    user_id    text        not null,
    equipment  text[]      not null default '{}',
    skills     text[]      not null default '{}',
    updated_by text        not null,
    updated_at timestamptz not null,
    primary key (tenant_id, user_id)
);

create index task_completed_idx on task (tenant_id, site_id, completed_at) where status = 'COMPLETED';

do $$
declare t text;
begin
    foreach t in array array['task_standard', 'zone_equipment', 'operator_profile']
    loop
        execute format('alter table %I enable row level security', t);
        execute format('alter table %I force row level security', t);
        execute format($p$create policy tenant_isolation on %I
                         using (tenant_id = current_setting('app.tenant_id', true))
                         with check (tenant_id = current_setting('app.tenant_id', true))$p$, t);
    end loop;
end $$;
