-- ADR-0022 labels: network label printers per site (ZPL over TCP 9100) and the LPN label number series.
create table label_printer (
    tenant_id  text        not null,
    site_id    text        not null,
    name       text        not null,
    host       text        not null,
    port       integer     not null default 9100 check (port between 9100 and 9199),   -- raw print ports only
    dpi        integer     not null default 203 check (dpi in (203, 300, 600)),
    purpose    text,                       -- e.g. LOCATION, ITEM, LPN: the default printer for that label
    updated_by text        not null,
    updated_at timestamptz not null,
    primary key (tenant_id, site_id, name)
);

-- Pre-printed pallet / LPN labels: the next number per site; numbers are never reused.
create table lpn_series (
    tenant_id   text        not null,
    site_id     text        not null,
    prefix      text        not null,
    next_number bigint      not null,
    updated_at  timestamptz not null,
    primary key (tenant_id, site_id)
);

do $$
declare t text;
begin
    foreach t in array array['label_printer', 'lpn_series']
    loop
        execute format('alter table %I enable row level security', t);
        execute format('alter table %I force row level security', t);
        execute format($p$create policy tenant_isolation on %I
                         using (tenant_id = current_setting('app.tenant_id', true))
                         with check (tenant_id = current_setting('app.tenant_id', true))$p$, t);
    end loop;
end $$;
