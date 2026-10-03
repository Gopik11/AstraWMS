-- ADR-0024 site network: a site is the main warehouse (MAIN) or a satellite store (STORE). A store is supplied by a
-- main site; the UI shows a store user only the store work (receive transfers, issue, count, sync status).
alter table site add column site_type text not null default 'MAIN' check (site_type in ('MAIN', 'STORE'));
alter table site add column supplying_site text;
alter table site add constraint site_supplier_check check (site_type = 'STORE' or supplying_site is null);
