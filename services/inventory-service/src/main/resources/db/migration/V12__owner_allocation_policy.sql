-- ADR-0021: an owner (3PL client) can have its own allocation policy at a site; owner_id '' is the site default.
-- Lot affinity: a line is filled from a single lot when one lot can cover it.
alter table allocation_policy add column owner_id text not null default '';
alter table allocation_policy add column lot_affinity boolean not null default false;
alter table allocation_policy drop constraint allocation_policy_pkey;
alter table allocation_policy add primary key (tenant_id, site_id, owner_id);
