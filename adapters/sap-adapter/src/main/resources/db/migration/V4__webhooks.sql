-- ADR-0025 webhooks: partners subscribe to WMS events (transfer shipped / received, issue posted / returned, count
-- variance) next to the SAP IDoc interface. Deliveries are signed (HMAC-SHA256) and retried with backoff.
create table webhook_subscription (
    id          uuid        primary key,
    tenant_id   text        not null,
    name        text        not null,
    url         text        not null,
    events      text[]      not null,
    secret      text        not null,
    active      boolean     not null default true,
    created_by  text        not null,
    created_at  timestamptz not null
);
alter table webhook_subscription enable row level security;
alter table webhook_subscription force row level security;
create policy tenant_isolation on webhook_subscription
    using (tenant_id = current_setting('app.tenant_id', true))
    with check (tenant_id = current_setting('app.tenant_id', true));

-- The delivery queue is read by the dispatcher across tenants (like the outbox): the body is signed when queued, so
-- the dispatcher never needs the secret.
create table webhook_delivery (
    id              uuid        primary key,
    tenant_id       text        not null,
    subscription_id uuid        not null references webhook_subscription (id) on delete cascade,
    message_id      text        not null,
    event           text        not null,
    url             text        not null,
    body            text        not null,
    signature       text        not null,
    signed_at       bigint      not null,
    status          text        not null check (status in ('PENDING', 'DELIVERED', 'FAILED')),
    attempts        integer     not null default 0,
    next_attempt_at timestamptz not null,
    last_status     integer,
    last_error      text,
    created_at      timestamptz not null,
    delivered_at    timestamptz,
    unique (subscription_id, message_id)
);
create index webhook_delivery_due_idx on webhook_delivery (next_attempt_at) where status = 'PENDING';
create index webhook_delivery_sub_idx on webhook_delivery (tenant_id, subscription_id, created_at desc);
