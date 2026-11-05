-- ADR-0030 C (MWH-001), increment 3: owner isolation on the outbound order lines and the owner policies.
--
-- As in inventory V21/V22: RESTRICTIVE policies, ANDed with tenant_isolation; app.owners is '*' for scopes unrestricted
-- by owner, the user's owners separated by commas otherwise, empty for none; read in scalar subqueries, so evaluated
-- once per query.
--
-- The order header carries no owner. A scoped user works an order only when all its lines are in scope, and a carton,
-- wave or load only when every order on it is (OrderScope); those checks see every owner's lines
-- (OwnerVisibility.allOwners), so an order is never shown or worked with part of its lines hidden, and a wave or load
-- is never released, closed or shipped with another owner's orders left out.
do $$
declare t text;
begin
    foreach t in array array['outbound_line', 'outbound_owner_policy']
    loop
        execute format($p$create policy owner_isolation on %I as restrictive
                         using ((select current_setting('app.owners', true)) = '*'
                                or owner_id = any ((select string_to_array(current_setting('app.owners', true), ','))::text[]))
                         with check ((select current_setting('app.owners', true)) = '*'
                                or owner_id = any ((select string_to_array(current_setting('app.owners', true), ','))::text[]))$p$, t);
    end loop;
end $$;
