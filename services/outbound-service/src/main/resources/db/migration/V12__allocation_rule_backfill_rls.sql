-- V11 ran outside any tenant, and forced row-level security hid every row from its update. Lift it for this one
-- statement (as inventory V15/V17 do) so lines allocated before rules were recorded say so.
alter table outbound_line no force row level security;
update outbound_line set allocation_rule = 'Allocated before 2026-10-04 under the site policy at that time (rule not recorded)'
where qty_allocated > 0 and allocation_rule is null;
alter table outbound_line force row level security;
