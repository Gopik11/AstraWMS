-- The service connects as ${appRole} (non-owner, no BYPASSRLS); see inventory-service V1_1 for rationale.
grant usage on schema public to ${appRole};
grant select, insert, update, delete on all tables in schema public to ${appRole};
grant usage, select on all sequences in schema public to ${appRole};
revoke insert, update, delete on uom_catalogue from ${appRole};
alter default privileges in schema public grant select, insert, update, delete on tables to ${appRole};
alter default privileges in schema public grant usage, select on sequences to ${appRole};
