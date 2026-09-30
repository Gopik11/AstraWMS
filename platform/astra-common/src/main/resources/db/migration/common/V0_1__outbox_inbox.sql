-- Platform tables shared by every AstraWMS service (astra-common).
-- No row-level security: the outbox relay and inbox guard run across tenants.

create table outbox (
    id           bigserial primary key,
    message_id   uuid        not null unique,
    topic        text        not null,
    message_key  text        not null,
    message_type text        not null,
    tenant_id    text        not null,
    envelope     jsonb       not null,
    created_at   timestamptz not null,
    published_at timestamptz
);
create index outbox_unpublished_idx on outbox (id) where published_at is null;

-- Gap-free sequence per (topic, business key) for EventEnvelope.sequence (ISD-00 §5).
create table outbox_key_sequence (
    topic        text   not null,
    business_key text   not null,
    last_seq     bigint not null,
    primary key (topic, business_key)
);

create table inbox (
    source_system text        not null,
    message_id    text        not null,
    message_type  text,
    processed_at  timestamptz not null,
    primary key (source_system, message_id)
);
