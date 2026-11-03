-- ADR-0029: inbox de-duplication per consumer group. Two consumer groups of one service that read the same topic
-- (the SAP adapter's postings and its webhooks) each process a message once. Rows written before this migration
-- keep the group '' and count as processed for every group until housekeeping purges them (inbox retention).
alter table inbox add column consumer_group text not null default '';
alter table inbox drop constraint inbox_pkey;
alter table inbox add primary key (source_system, message_id, consumer_group);

-- ADR-0029: a dead letter older than the inbox retention may already have been applied and forgotten by the inbox;
-- replaying it is refused unless forced, and a forced replay records why.
alter table dlq_replay add column forced boolean not null default false;
alter table dlq_replay add column force_reason text;
