-- Standard cost per base unit from the item master, for approval value limits (§G.5.1).
alter table ref_item add column standard_cost numeric(18, 4);
