-- ADR-0021 3PL billing: billable events captured from the inventory ledger (receipts, picks, returns), storage days
-- from end-of-day stock, and value-added services entered by hand; priced by owner-specific (or default) rates.

create table billing_rate (
    tenant_id  text           not null,
    site_id    text           not null,
    owner_id   text           not null default '',     -- '' = every owner without its own rate
    event_type text           not null check (event_type in ('RECEIPT', 'PICK', 'RETURN', 'STORAGE', 'VAS')),
    service    text           not null default '',     -- VAS service code (LABEL, KIT, ...); '' otherwise
    basis      text           not null check (basis in ('UNIT', 'LINE', 'LPN', 'EVENT')),
    rate       numeric(12, 4) not null check (rate >= 0),
    currency   text           not null default 'USD',
    updated_by text           not null,
    updated_at timestamptz    not null,
    primary key (tenant_id, site_id, owner_id, event_type, service)
);

create table billing_event (
    id          bigserial      primary key,
    tenant_id   text           not null,
    site_id     text           not null,
    owner_id    text           not null,
    event_type  text           not null,
    service     text           not null default '',
    ref         text           not null,               -- what was billed: operation, storage day, VAS id
    occurred_at timestamptz    not null,
    units       numeric(18, 3) not null default 0,
    lines       integer        not null default 0,
    lpns        integer        not null default 0,
    basis       text,
    rate        numeric(12, 4),
    amount      numeric(14, 2) not null default 0,
    currency    text,
    description text,
    created_by  text           not null,
    created_at  timestamptz    not null,
    unique (tenant_id, site_id, event_type, ref)
);
create index billing_event_owner_idx on billing_event (tenant_id, site_id, owner_id, occurred_at);

create table billing_capture (
    tenant_id      text        not null,
    site_id        text        not null,
    last_run_at    timestamptz not null,
    storage_through date,
    primary key (tenant_id, site_id)
);

create index inventory_txn_time_idx on inventory_txn (tenant_id, site_id, occurred_at);

do $$
declare t text;
begin
    foreach t in array array['billing_rate', 'billing_event', 'billing_capture']
    loop
        execute format('alter table %I enable row level security', t);
        execute format('alter table %I force row level security', t);
        execute format($p$create policy tenant_isolation on %I
                         using (tenant_id = current_setting('app.tenant_id', true))
                         with check (tenant_id = current_setting('app.tenant_id', true))$p$, t);
    end loop;
end $$;
