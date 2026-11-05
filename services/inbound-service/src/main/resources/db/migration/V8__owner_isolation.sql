-- ADR-0030 C (MWH-001), increment 3: owner isolation on the inbound document lines.
--
-- As in inventory V21/V22: RESTRICTIVE policies, ANDed with tenant_isolation; app.owners is '*' for scopes unrestricted
-- by owner, the user's owners separated by commas otherwise, empty for none; read in scalar subqueries, so evaluated
-- once per query.
--
-- The headers (receipt_expectation, return_order) carry no owner. A scoped user works a receipt only when all its lines
-- are in scope, and a return only when all its lines and units are (ReceiptScope, ReturnScope); those checks see every
-- owner's rows (OwnerVisibility.allOwners), so a document is never shown or worked with part of its lines hidden.
do $$
declare t text;
begin
    foreach t in array array['receipt_expectation_line', 'return_line', 'return_unit']
    loop
        execute format($p$create policy owner_isolation on %I as restrictive
                         using ((select current_setting('app.owners', true)) = '*'
                                or owner_id = any ((select string_to_array(current_setting('app.owners', true), ','))::text[]))
                         with check ((select current_setting('app.owners', true)) = '*'
                                or owner_id = any ((select string_to_array(current_setting('app.owners', true), ','))::text[]))$p$, t);
    end loop;
end $$;
