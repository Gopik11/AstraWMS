-- ADR-0030 C (MWH-001), increment 4: owner isolation on the item master.
--
-- As in the other services: RESTRICTIVE policies, ANDed with tenant_isolation; app.owners is '*' for scopes
-- unrestricted by owner, the user's owners separated by commas otherwise, empty for none; read in scalar subqueries.
-- GTINs are unique within an owner (MD001-R08), so no check needs to see another owner's items. The item endpoints
-- also refuse another owner explicitly (403 SCOPE_OWNER_DENIED).
do $$
declare t text;
begin
    foreach t in array array['item', 'item_site', 'item_uom']
    loop
        execute format($p$create policy owner_isolation on %I as restrictive
                         using ((select current_setting('app.owners', true)) = '*'
                                or owner_id = any ((select string_to_array(current_setting('app.owners', true), ','))::text[]))
                         with check ((select current_setting('app.owners', true)) = '*'
                                or owner_id = any ((select string_to_array(current_setting('app.owners', true), ','))::text[]))$p$, t);
    end loop;
end $$;
