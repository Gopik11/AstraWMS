-- Test container bootstrap: the application role (non-owner, no BYPASSRLS), as infrastructure provisions it.
create role astra_app login password 'astra_app_test' nosuperuser nobypassrls;
