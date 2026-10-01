-- Waves (§C.4, ADV-030): orders can wait in the pool until a supervisor plans and releases a wave.
-- Re-allocation after short picks (PCK-003 c) and reverse picks of cancelled orders (OUT-EX-02).

create table outbound_site_config (
    tenant_id    text        not null,
    site_id      text        not null,
    release_mode text        not null check (release_mode in ('WAVELESS', 'WAVE')),
    updated_by   text        not null,
    updated_at   timestamptz not null,
    primary key (tenant_id, site_id)
);

create table outbound_wave (
    id          uuid        primary key,
    tenant_id   text        not null,
    site_id     text        not null,
    wave_no     text        not null,
    status      text        not null check (status in ('PLANNED', 'RELEASED')),
    criteria    jsonb       not null,
    created_by  text        not null,
    created_at  timestamptz not null,
    released_by text,
    released_at timestamptz,
    unique (tenant_id, site_id, wave_no)
);

alter table outbound_order drop constraint outbound_order_status_check;
alter table outbound_order add constraint outbound_order_status_check check (status in
    ('POOLED', 'RELEASED', 'BACKORDERED', 'PICKED', 'SHIPPED', 'CONFIRMED', 'SHIP_ERROR', 'CANCEL_REQUESTED', 'CANCELLED'));
alter table outbound_order add column wave_id uuid references outbound_wave (id);
-- The ERP's cancel request is acknowledged only once picked stock is back in stock (OUT-EX-02).
alter table outbound_order add column pending_cancel_ack jsonb;
create index outbound_order_wave_idx on outbound_order (wave_id);

-- qty_short: total not shipped for lack of stock; qty_short_pick: the part caused by short picks.
alter table outbound_line add column qty_short_pick numeric(18, 3) not null default 0;

alter table outbound_allocation drop constraint outbound_allocation_status_check;
alter table outbound_allocation add constraint outbound_allocation_status_check
    check (status in ('OPEN', 'DONE', 'CANCELLED', 'RETURNING', 'RETURNED'));
-- The short-picked allocation that this one re-allocates.
alter table outbound_allocation add column replaces uuid;

do $$
declare t text;
begin
    foreach t in array array['outbound_site_config', 'outbound_wave']
    loop
        execute format('alter table %I enable row level security', t);
        execute format('alter table %I force row level security', t);
        execute format($p$create policy tenant_isolation on %I
                         using (tenant_id = current_setting('app.tenant_id', true))
                         with check (tenant_id = current_setting('app.tenant_id', true))$p$, t);
    end loop;
end $$;
