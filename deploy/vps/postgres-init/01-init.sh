#!/bin/sh
# Creates the AstraWMS roles and databases with the passwords from /opt/astrawms/.env (first start only).
set -eu
psql -v ON_ERROR_STOP=1 -U postgres <<SQL
create role astra_owner login password '${DB_OWNER_PASSWORD}';
create role astra_app login password '${DB_APP_PASSWORD}' nosuperuser nobypassrls;
create role keycloak login password '${KC_DB_PASSWORD}';
create database masterdata owner astra_owner;
create database inventory owner astra_owner;
create database inbound owner astra_owner;
create database sapadapter owner astra_owner;
create database tasks owner astra_owner;
create database outbound owner astra_owner;
create database keycloak owner keycloak;
SQL
