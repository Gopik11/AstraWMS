-- ADR-0025: lines allocated before the allocation rule was recorded say so, instead of showing nothing.
update outbound_line set allocation_rule = 'Allocated before 2026-10-04 under the site policy at that time (rule not recorded)'
where qty_allocated > 0 and allocation_rule is null;
