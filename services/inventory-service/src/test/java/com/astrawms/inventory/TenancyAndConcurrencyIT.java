package com.astrawms.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.astrawms.inventory.support.IntegrationTest;
import com.astrawms.test.TestTokens;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class TenancyAndConcurrencyIT extends IntegrationTest {

    @Test
    void tenantsCannotSeeOrTouchEachOthersStock_MWH001() throws Exception {
        receive("SKU-EA", "10", "EA", "A-01-01", "LPN-A", null).andExpect(status().isCreated());
        String tenantA = tenant;

        tenant = "t-other";
        getJson("/balances").andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(0)));
        getJson("/lpns/LPN-A").andExpect(status().isNotFound());
        // Reference data is tenant-scoped too, so tenant B cannot even address tenant A's item.
        post("/adjustments", """
                {"ownerId":"ACME","itemNo":"SKU-EA","locationId":"A-01-01","qtyDelta":-1,"uom":"EA","reasonCode":"CC_TOL"}""")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code", is("INV_ITEM_UNKNOWN")));

        tenant = tenantA;
        assertThat(onHand("SKU-EA", "A-01-01", "AVAILABLE")).isEqualByComparingTo("10");
    }

    @Test
    void rowLevelSecurityHidesRowsEvenWithoutWhereClause() throws Exception {
        receive("SKU-EA", "1", "EA", "A-01-01", null, null).andExpect(status().isCreated());
        String tenantA = tenant;
        tenant = "t-spy";
        Integer visible = queryAsTenant(() -> jdbc.sql("select count(*) from inventory_balance")
                .query(Integer.class).single());
        assertThat(visible).isZero();
        tenant = tenantA;
        assertThat(queryAsTenant(() -> jdbc.sql("select count(*) from inventory_balance")
                .query(Integer.class).single())).isEqualTo(1);
    }

    @Test
    void requestsWithoutTokenAreRejected() throws Exception {
        mvc.perform(get("/api/v1/sites/DC1/inventory/balances"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code", is("UNAUTHENTICATED")));
        mvc.perform(get("/api/v1/sites/DC1/inventory/balances").with(TestTokens.as(tenant, "operator1", TestTokens.ALL_ROLES)))
                .andExpect(status().isOk());
    }

    @Test
    void concurrentIssuesNeverOversell_INV001() throws Exception {
        receive("SKU-EA", "10", "EA", "A-01-01", null, null).andExpect(status().isCreated());
        String body = """
                {"ownerId":"ACME","itemNo":"SKU-EA","locationId":"A-01-01","qtyDelta":-1,"uom":"EA","reasonCode":"CC_TOL"}""";

        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Callable<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            String key = "concurrent-" + i;
            calls.add(() -> postAs("op", "/adjustments", key, body).andReturn().getResponse().getStatus());
        }
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : pool.invokeAll(calls)) {
            statuses.add(f.get());
        }
        pool.shutdown();

        assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(10);
        assertThat(statuses.stream().filter(s -> s == 422).count()).isEqualTo(15);
        assertThat(onHand("SKU-EA", "A-01-01", "AVAILABLE")).isZero();
        Integer ledgerLines = queryAsTenant(() -> jdbc.sql(
                "select count(*) from inventory_txn where txn_type = 'ADJUST_NEG'").query(Integer.class).single());
        assertThat(ledgerLines).isEqualTo(10);
    }

    /**
     * Review validation plan (ADR-0029): many concurrent retries of one command with one idempotency key have exactly
     * one effect; every other answer is the original result replayed or a retryable conflict, never a second posting.
     */
    @Test
    void concurrentRetriesOfOneCommandHaveExactlyOneEffect() throws Exception {
        String body = """
                {"ownerId":"ACME","itemNo":"SKU-EA","qty":7,"uom":"EA","locationId":"A-01-02","lpnId":"LPN-IDEM"}""";
        ExecutorService pool = Executors.newFixedThreadPool(16);
        List<Callable<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            calls.add(() -> postAs("op", "/receipts", "rf-retry-1", body).andReturn().getResponse().getStatus());
        }
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : pool.invokeAll(calls)) {
            statuses.add(f.get());
        }
        pool.shutdown();

        assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(1);
        assertThat(statuses).allMatch(s -> s == 201 || s == 200 || s == 409);
        assertThat(onHand("SKU-EA", "A-01-02", "AVAILABLE")).isEqualByComparingTo("7");
        assertThat(queryAsTenant(() -> jdbc.sql("select count(*) from inventory_operation where idempotency_key = 'rf-retry-1'")
                .query(Integer.class).single())).isEqualTo(1);
        assertThat(queryAsTenant(() -> jdbc.sql("select count(*) from inventory_txn where lpn_id = 'LPN-IDEM'")
                .query(Integer.class).single())).isEqualTo(1);
        // one event for the one change (a receipt reaches the ERP through inbound's confirmation, not a movement)
        assertThat(outboxTypes().stream().filter("InventoryChanged"::equals).count()).isEqualTo(1);

        // the same key with another body is refused, not applied
        postAs("op", "/receipts", "rf-retry-1", body.replace("\"qty\":7", "\"qty\":8"))
                .andExpect(status().isUnprocessableContent());
        assertThat(onHand("SKU-EA", "A-01-02", "AVAILABLE")).isEqualByComparingTo("7");
    }
}
