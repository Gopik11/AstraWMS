package com.astrawms.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.astrawms.common.tenancy.TenantFilter;
import com.astrawms.inventory.support.IntegrationTest;
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
    void missingTenantHeaderIsRejected() throws Exception {
        mvc.perform(get("/api/v1/sites/DC1/inventory/balances"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("TENANT_MISSING")));
        mvc.perform(get("/api/v1/sites/DC1/inventory/balances").header(TenantFilter.TENANT_HEADER, tenant))
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
}
