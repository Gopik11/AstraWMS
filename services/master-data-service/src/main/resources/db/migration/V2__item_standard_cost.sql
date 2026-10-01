-- Standard cost per base unit (tenant reporting currency), for approval value limits (§G.5.1).
alter table item add column standard_cost numeric(18, 4) check (standard_cost >= 0);
