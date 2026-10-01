-- Dead-letter replays (astra-common DeadLetters, ADR-0018): one replay per DLQ record, with who and when.
-- Platform table: no row-level security (like outbox / inbox); DeadLetters always filters by tenant_id.
create table dlq_replay (
    dlq_topic      text        not null,
    dlq_partition  int         not null,
    dlq_offset     bigint      not null,
    tenant_id      text        not null,
    original_topic text        not null,
    consumer_group text        not null,
    message_id     text,
    replayed_by    text        not null,
    replayed_at    timestamptz not null,
    primary key (dlq_topic, dlq_partition, dlq_offset)
);
create index dlq_replay_tenant_idx on dlq_replay (tenant_id);
