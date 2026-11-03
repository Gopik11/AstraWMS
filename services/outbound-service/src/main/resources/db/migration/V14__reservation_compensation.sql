-- ADR-0029 (review H-04): a strict transfer reserves stock in inventory, then is refused or fails; its own
-- transaction rolls back, the reservation in inventory does not. What could not be released at once is kept here and
-- released by a retrier. Read across tenants by the retrier, so no row-level security (like outbox); every other read
-- filters by tenant_id.
create table reservation_compensation (
    id             uuid        primary key,
    tenant_id      text        not null,
    site_id        text        not null,
    order_ref      text        not null,
    allocation_ids uuid[]      not null,
    -- an allocation call failed without an answer: inventory may hold reservations whose ids were never returned
    sweep_by_ref   boolean     not null default false,
    created_at     timestamptz not null,
    attempts       int         not null default 0,
    last_error     text,
    done_at        timestamptz
);
create index reservation_compensation_open_idx on reservation_compensation (created_at) where done_at is null;
