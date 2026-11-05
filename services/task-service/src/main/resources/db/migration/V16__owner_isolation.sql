-- ADR-0030 C (MWH-001), increment 4: owner isolation in the task service.
--
-- As in the other services: RESTRICTIVE policies, ANDed with tenant_isolation; app.owners is '*' for scopes
-- unrestricted by owner, the user's owners separated by commas otherwise, empty for none; read in scalar subqueries.
--
-- Projections of reference data and stock: one row per owner.
do $$
declare t text;
begin
    foreach t in array array['ref_item', 'ref_item_gtin', 'ref_item_slotting', 'ref_pick_face', 'stock_projection']
    loop
        execute format($p$create policy owner_isolation on %I as restrictive
                         using ((select current_setting('app.owners', true)) = '*'
                                or owner_id = any ((select string_to_array(current_setting('app.owners', true), ','))::text[]))
                         with check ((select current_setting('app.owners', true)) = '*'
                                or owner_id = any ((select string_to_array(current_setting('app.owners', true), ','))::text[]))$p$, t);
    end loop;
end $$;

-- Tasks. owner_id '' marks a task of no single owner: a location count (the inventory service decides who may count a
-- location) or a blind return (the task service checks the owner of each scanned unit). The service already offers
-- those to every scope; they stay readable and workable by every scope. A receive task of a document whose lines
-- belong to several owners carries owner_id '*' (ReceivingContracts.SEVERAL_OWNERS), which is never in a user's owner
-- list: only scopes unrestricted by owner see it. Putaway planning and validation read every owner's stock and
-- reservations (OwnerVisibility.allOwners).
create policy owner_isolation on task as restrictive
    using (owner_id = ''
           or (select current_setting('app.owners', true)) = '*'
           or owner_id = any ((select string_to_array(current_setting('app.owners', true), ','))::text[]))
    with check (owner_id = ''
           or (select current_setting('app.owners', true)) = '*'
           or owner_id = any ((select string_to_array(current_setting('app.owners', true), ','))::text[]));
