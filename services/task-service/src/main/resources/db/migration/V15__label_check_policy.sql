-- ADR-0029 (review M-03): what RF does when master data cannot say whether a printed label is active.
-- FAIL_OPEN (default, ADR-0025): the scan is accepted and the bypass recorded for reconciliation.
-- FAIL_CLOSED: the scan is refused until master data answers.
create table label_check_policy (
    tenant_id  text        not null,
    site_id    text        not null,
    mode       text        not null check (mode in ('FAIL_OPEN', 'FAIL_CLOSED')),
    updated_by text        not null,
    updated_at timestamptz not null,
    primary key (tenant_id, site_id)
);

-- Every scan accepted without a label check, until a supervisor has reviewed it.
create table label_check_bypass (
    id          uuid        primary key,
    tenant_id   text        not null,
    site_id     text        not null,
    task_id     uuid,
    label_type  text        not null,
    barcode     text        not null,
    user_id     text        not null,
    at          timestamptz not null,
    reviewed_by text,
    reviewed_at timestamptz,
    review_note text
);
create index label_check_bypass_open_idx on label_check_bypass (tenant_id, site_id, at) where reviewed_at is null;

do $$
declare t text;
begin
    foreach t in array array['label_check_policy', 'label_check_bypass']
    loop
        execute format('alter table %I enable row level security', t);
        execute format('alter table %I force row level security', t);
        execute format($p$create policy tenant_isolation on %I
                         using (tenant_id = current_setting('app.tenant_id', true))
                         with check (tenant_id = current_setting('app.tenant_id', true))$p$, t);
    end loop;
end $$;
