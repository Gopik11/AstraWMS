-- ADR-0030 C (MWH-001): owner isolation in the database, ledger tables first.
--
-- tenant_isolation (permissive, ADR-0003) already limits every row to the session's tenant. owner_isolation is
-- RESTRICTIVE, so Postgres combines it with AND: a row needs the right tenant and an allowed owner. It must never be
-- permissive, because permissive policies are combined with OR and would widen access across tenants
-- (scripts/tenant_tables.py fails CI on a second permissive policy).
--
-- app.owners is set on every connection checkout by TenantAwareDataSource: '*' for a scope unrestricted by owner
-- (service accounts, message consumers, jobs, users with every owner), the user's owners separated by commas
-- otherwise, empty for none. Physical checks that must see every owner's rows of the tenant (another owner's item in
-- a location, an LPN id in use, an empty slot) go through OwnerVisibility.allOwners.
--
-- Each setting is read in a scalar subquery so Postgres evaluates it once per query (an InitPlan), not once per row:
-- measured with pgbench on 340k ledger rows, an unrestricted session pays about 4% (11-13% with the settings read per
-- row), and an owner-scoped session is about 30% faster because it reads fewer rows.
do $$
declare t text;
begin
    foreach t in array array['inventory_balance', 'inventory_txn', 'lpn', 'allocation']
    loop
        execute format($p$create policy owner_isolation on %I as restrictive
                         using ((select current_setting('app.owners', true)) = '*'
                                or owner_id = any ((select string_to_array(current_setting('app.owners', true), ','))::text[]))
                         with check ((select current_setting('app.owners', true)) = '*'
                                or owner_id = any ((select string_to_array(current_setting('app.owners', true), ','))::text[]))$p$, t);
    end loop;
end $$;
