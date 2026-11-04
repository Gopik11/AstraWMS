package com.astrawms.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.astrawms.common.contracts.MasterDataEvents.ItemUpserted;
import com.astrawms.common.security.AccessScope;
import com.astrawms.common.security.Roles;
import com.astrawms.common.tenancy.OwnerVisibility;
import com.astrawms.common.tenancy.TenantContext;
import com.astrawms.inventory.support.IntegrationTest;
import com.astrawms.test.TestTokens;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Owner isolation in the database (ADR-0030 C, MWH-001): the restrictive owner_isolation policies on the ledger tables
 * hide another owner's rows from an owner-scoped session even without a WHERE clause, refuse writes for another owner,
 * and leave the physical checks that must see every owner (OwnerVisibility.allOwners) working.
 */
class OwnerIsolationIT extends IntegrationTest {

    private static final List<String> LEDGER = List.of("inventory_balance", "inventory_txn", "lpn", "allocation");

    @BeforeEach
    void secondOwnerAndSingleItemLocation() {
        asTenant(() -> refs.upsertItem(new ItemUpserted("BETA", "SKU-B", "EA", "ACTIVE", null, null, false,
                List.of(new ItemUpserted.Site(SITE, false, "NONE", "ACTIVE")), List.of(), Instant.now())));
        location("S-01-01", "STOR", "0001", null, false, false, true);   // refuses mixed items
    }

    /** ACME stock with an LPN and an allocation, and BETA stock with an LPN. */
    private void stockOfTwoOwners() throws Exception {
        receive("SKU-EA", "5", "EA", "A-01-01", "LPN-ACME", null).andExpect(status().isCreated());
        post("/receipts", """
                {"ownerId":"BETA","itemNo":"SKU-B","qty":3,"uom":"EA","locationId":"A-01-02","lpnId":"LPN-BETA"}""")
                .andExpect(status().isCreated());
        post("/allocations", """
                {"orderRef":"SO-1","orderLineRef":"000010","ownerId":"ACME","itemNo":"SKU-EA","qty":2,"uom":"EA"}""")
                .andExpect(status().isCreated());
        post("/allocations", """
                {"orderRef":"SO-2","orderLineRef":"000010","ownerId":"BETA","itemNo":"SKU-B","qty":1,"uom":"EA"}""")
                .andExpect(status().isCreated());
    }

    private <T> T asOwners(Set<String> owners, Supplier<T> work) {
        AccessScope access = new AccessScope(null, owners, null, null);
        return TenantContext.callAs(new TenantContext.Scope(tenant, "scoped", "TEST", access),
                () -> tx.execute(s -> work.get()));
    }

    private List<String> ownersIn(String table) {
        return jdbc.sql("select distinct owner_id from " + table + " order by owner_id").query(String.class).list();
    }

    @Test
    void anOwnerScopedSessionSeesOnlyItsOwnersLedgerRowsWithoutAWhereClause_MWH001() throws Exception {
        stockOfTwoOwners();
        for (String table : LEDGER) {
            assertThat(queryAsTenant(() -> ownersIn(table))).as(table + ", unrestricted").containsExactly("ACME", "BETA");
            assertThat(asOwners(Set.of("BETA"), () -> ownersIn(table))).as(table + ", BETA").containsExactly("BETA");
            assertThat(asOwners(Set.of("ACME", "BETA"), () -> ownersIn(table))).as(table + ", both")
                    .containsExactly("ACME", "BETA");
            assertThat(asOwners(Set.of(), () -> ownersIn(table))).as(table + ", no owner").isEmpty();
        }
    }

    @Test
    void anOwnerScopedSessionCannotChangeOrCreateAnotherOwnersRows() throws Exception {
        stockOfTwoOwners();
        // another owner's rows are not there to change
        int changed = asOwners(Set.of("BETA"), () -> jdbc.sql(
                "update inventory_balance set qty = qty + 100 where owner_id = 'ACME'").update());
        assertThat(changed).isZero();
        assertThat(onHand("SKU-EA", "A-01-01", "AVAILABLE")).isEqualByComparingTo("5");
        // nor can a row be created for it, or an own row handed to it
        assertThatThrownBy(() -> asOwners(Set.of("BETA"), () -> jdbc.sql("""
                        insert into lpn (tenant_id, site_id, lpn_id, owner_id, location_id)
                        values (:t, 'DC1', 'LPN-FORGED', 'ACME', 'A-01-01')""").param("t", tenant).update()))
                .hasStackTraceContaining("row-level security");
        assertThatThrownBy(() -> asOwners(Set.of("BETA"), () -> jdbc.sql(
                        "update lpn set owner_id = 'ACME' where lpn_id = 'LPN-BETA'").update()))
                .hasStackTraceContaining("row-level security");
        assertThat(queryAsTenant(() -> jdbc.sql("select owner_id from lpn where lpn_id = 'LPN-BETA'")
                .query(String.class).single())).isEqualTo("BETA");
    }

    @Test
    void physicalChecksStillSeeEveryOwnersStock() throws Exception {
        receive("SKU-EA", "5", "EA", "A-01-01", "LPN-ACME", null).andExpect(status().isCreated());
        receive("SKU-EA", "2", "EA", "S-01-01", null, null).andExpect(status().isCreated());
        RequestPostProcessor beta = TestTokens.bearer(TestTokens.token().tenant(tenant).user("beta-clerk")
                .roles(Roles.RECEIVER).owners("BETA").sign());

        // S-01-01 holds ACME's item and refuses mixed items: BETA's item is refused although BETA cannot see ACME's
        postWith(beta, "/receipts", "beta-mixed", """
                {"ownerId":"BETA","itemNo":"SKU-B","qty":1,"uom":"EA","locationId":"S-01-01"}""", null)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code", is("INV_LOCATION_MIXED_ITEMS")));
        // LPN-ACME is in use: BETA may not reuse the id, and is not told whose it is
        postWith(beta, "/receipts", "beta-lpn", """
                {"ownerId":"BETA","itemNo":"SKU-B","qty":1,"uom":"EA","locationId":"A-01-01","lpnId":"LPN-ACME"}""", null)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code", is("INV_LPN_OWNER_MISMATCH")))
                .andExpect(jsonPath("$.detail", containsString("another owner")))
                .andExpect(jsonPath("$.detail", not(containsString("owner ACME"))));
        // a user who may see ACME is told
        post("/receipts", """
                {"ownerId":"BETA","itemNo":"SKU-B","qty":1,"uom":"EA","locationId":"A-01-01","lpnId":"LPN-ACME"}""")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail", containsString("owner ACME")));
        // and BETA's own receipt elsewhere still works
        postWith(beta, "/receipts", "beta-own", """
                {"ownerId":"BETA","itemNo":"SKU-B","qty":1,"uom":"EA","locationId":"A-01-02","lpnId":"LPN-BETA"}""", null)
                .andExpect(status().isCreated());
    }

    @Test
    void allOwnersNeedsATransactionWidensOnlyForTheCheckAndRestoresTheOwners() throws Exception {
        stockOfTwoOwners();
        assertThatThrownBy(() -> OwnerVisibility.allOwners(jdbc, () -> 1)).isInstanceOf(IllegalStateException.class);
        List<Object> seen = asOwners(Set.of("BETA"), () -> {
            String before = jdbc.sql("select current_setting('app.owners', true)").query(String.class).single();
            List<String> during = OwnerVisibility.allOwners(jdbc, () -> ownersIn("inventory_balance"));
            String after = jdbc.sql("select current_setting('app.owners', true)").query(String.class).single();
            return List.of(before, during, after, ownersIn("inventory_balance"));
        });
        assertThat(seen).containsExactly("BETA", List.of("ACME", "BETA"), "BETA", List.of("BETA"));
        // and a check that fails still restores them
        String afterFailure = asOwners(Set.of("BETA"), () -> {
            try {
                OwnerVisibility.allOwners(jdbc, () -> {
                    throw new IllegalArgumentException("check failed");
                });
            } catch (IllegalArgumentException expected) {
                // the caller handles it and continues in the same transaction
            }
            return jdbc.sql("select current_setting('app.owners', true)").query(String.class).single();
        });
        assertThat(afterFailure).isEqualTo("BETA");
    }
}
