-- The service connects as ${appRole} (non-owner, no BYPASSRLS) so row-level security is always enforced.
-- Flyway runs as the schema owner. The role itself is provisioned by infrastructure (deploy/postgres/init,
-- Terraform), never by a migration, so no credentials live in this repository's migrations.

grant usage on schema public to ${appRole};
grant select, insert, update, delete on all tables in schema public to ${appRole};
grant usage, select on all sequences in schema public to ${appRole};

-- The ledger is append-only for the application role (the trigger guards the owner as well).
revoke update, delete on inventory_txn from ${appRole};
-- Reference data is written only by the master-data projection, which also runs as ${appRole};
-- reason codes are maintained by migrations.
revoke insert, update, delete on reason_code from ${appRole};

alter default privileges in schema public grant select, insert, update, delete on tables to ${appRole};
alter default privileges in schema public grant usage, select on sequences to ${appRole};
