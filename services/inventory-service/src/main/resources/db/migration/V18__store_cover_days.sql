-- Predictive shortage (ADR-0026): how many days of demand a replenishment should cover beyond the transit time.
alter table store_site_setting add column cover_days integer not null default 7 check (cover_days between 1 and 90);
