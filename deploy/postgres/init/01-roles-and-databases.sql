-- Local development only. In other environments roles and databases are provisioned by infrastructure (Terraform)
-- with secrets from the vault (INT-027); these passwords must never be used outside a developer machine.
create role astra_owner login password 'astra_owner_dev';
create role astra_app login password 'astra_app_dev' nosuperuser nobypassrls;

create database masterdata owner astra_owner;
create database inventory owner astra_owner;
create database inbound owner astra_owner;
create database sapadapter owner astra_owner;
create database tasks owner astra_owner;
