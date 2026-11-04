-- ADR-0030 C (MWH-001), increment 2: owner isolation on the other inventory tables with owner_id.
--
-- As in V21: RESTRICTIVE policies, ANDed with tenant_isolation; app.owners is '*' for scopes unrestricted by owner,
-- the user's owners separated by commas otherwise, empty for none; each setting is read in a scalar subquery so it is
-- evaluated once per query. Checks that must see every owner go through OwnerVisibility.allOwners.
--
-- Three forms, by what the table's owner_id means:
--   * owner rows only: a row belongs to exactly one owner.
--   * shared defaults (allocation_policy, billing_rate): owner_id '' is the site's default for every owner without its
--     own row. Every scope reads it (allocation and billing need it); only a scope unrestricted by owner writes it, so
--     an owner-scoped user cannot change what applies to the other owners.
--   * shared tags (rfid_tag): owner_id is null for LPN and location tags, which the inventory service treats as
--     visible to every owner (Rfid.tags). Every scope reads and writes them, as before; unit tags carry their owner.

-- owner rows only
do $$
declare t text;
begin
    foreach t in array array['billing_event', 'item_slotting', 'material_issue', 'owner_profile', 'ref_item',
                             'ref_item_uom', 'replen_rule', 'replenishment', 'serial_number', 'stock_count_variance',
                             'stock_in_transit', 'store_replenishment', 'store_stock_policy']
    loop
        execute format($p$create policy owner_isolation on %I as restrictive
                         using ((select current_setting('app.owners', true)) = '*'
                                or owner_id = any ((select string_to_array(current_setting('app.owners', true), ','))::text[]))
                         with check ((select current_setting('app.owners', true)) = '*'
                                or owner_id = any ((select string_to_array(current_setting('app.owners', true), ','))::text[]))$p$, t);
    end loop;
end $$;

-- shared defaults: '' is read by every scope, written only by a scope unrestricted by owner
do $$
declare t text;
begin
    foreach t in array array['allocation_policy', 'billing_rate']
    loop
        execute format($p$create policy owner_isolation on %I as restrictive
                         using (owner_id = ''
                                or (select current_setting('app.owners', true)) = '*'
                                or owner_id = any ((select string_to_array(current_setting('app.owners', true), ','))::text[]))
                         with check ((select current_setting('app.owners', true)) = '*'
                                or owner_id = any ((select string_to_array(current_setting('app.owners', true), ','))::text[]))$p$, t);
    end loop;
end $$;

-- shared tags: an LPN or location tag (owner_id null) is read and written by every scope
create policy owner_isolation on rfid_tag as restrictive
    using (owner_id is null
           or (select current_setting('app.owners', true)) = '*'
           or owner_id = any ((select string_to_array(current_setting('app.owners', true), ','))::text[]))
    with check (owner_id is null
           or (select current_setting('app.owners', true)) = '*'
           or owner_id = any ((select string_to_array(current_setting('app.owners', true), ','))::text[]));
