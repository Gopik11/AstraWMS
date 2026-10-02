package com.astrawms.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.astrawms.common.security.Roles;
import com.astrawms.inventory.support.IntegrationTest;
import com.astrawms.test.TestTokens;
import com.jayway.jsonpath.JsonPath;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** 3PL billing from the ledger: receipts, returns, picks, storage days and VAS, priced by owner rates (ADR-0021). */
class BillingIT extends IntegrationTest {

    private ResultActions rate(String json) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.put("/api/v1/sites/" + SITE + "/inventory/billing/rates")
                .with(TestTokens.as(tenant, "ada", Roles.SOLUTION_ADMIN)).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    /** A receipt booked three days ago, written straight into the ledger. */
    private void oldReceipt() {
        asTenant(() -> {
            UUID op = UUID.randomUUID();
            Timestamp at = Timestamp.from(Instant.now().minus(3, ChronoUnit.DAYS));
            jdbc.sql("""
                            insert into inventory_operation (id, tenant_id, site_id, idempotency_key, request_hash, op_type,
                                                             wms_txn_id, created_by, channel, created_at)
                            values (:id, :t, :site, :key, 'x', 'RECEIPT', :txn, 'test', 'TEST', :at)""")
                    .param("id", op).param("t", tenant).param("site", SITE).param("key", "OLD-" + op)
                    .param("txn", "OLD" + op.toString().substring(0, 12)).param("at", at).update();
            jdbc.sql("""
                            insert into inventory_txn (tenant_id, site_id, operation_id, txn_type, owner_id, item_no, lot_no,
                                                       lpn_id, location_id, stock_status, qty_delta, qty_after, user_id,
                                                       channel, occurred_at)
                            values (:t, :site, :op, 'RECEIPT', 'ACME', 'SKU-EA', '', 'LPN-OLD', 'A-02-01', 'AVAILABLE', 6, 6,
                                    'test', 'TEST', :at)""")
                    .param("t", tenant).param("site", SITE).param("op", op).param("at", at).update();
        });
    }

    @Test
    void ledgerOperationsStorageDaysAndServicesBecomePricedBillingEvents() throws Exception {
        rate("{\"eventType\":\"RECEIPT\",\"basis\":\"LPN\",\"rate\":5}").andExpect(status().isOk());
        rate("{\"ownerId\":\"acme\",\"eventType\":\"PICK\",\"basis\":\"UNIT\",\"rate\":0.25}").andExpect(status().isOk());
        rate("{\"eventType\":\"STORAGE\",\"basis\":\"LPN\",\"rate\":1.5}").andExpect(status().isOk());
        rate("{\"eventType\":\"VAS\",\"service\":\"label\",\"basis\":\"UNIT\",\"rate\":0.1}").andExpect(status().isOk());
        rate("{\"eventType\":\"STORAGE\",\"basis\":\"LINE\",\"rate\":1}").andExpect(jsonPath("$.code", is("INV_RATE_INVALID")));

        oldReceipt();
        receive("SKU-EA", "10", "EA", "A-01-01", "LPN-B1", null).andExpect(status().isCreated());
        String alloc = JsonPath.read(post("/allocations", """
                {"orderRef":"SO-B1","orderLineRef":"000010","ownerId":"ACME","itemNo":"SKU-EA","qty":4,"uom":"EA"}""")
                .andReturn().getResponse().getContentAsString(), "$.allocations[0].id");
        post("/allocations/" + alloc + "/pick", """
                {"qty":4,"toLocationId":"STAGE-OUT","toLpnId":"PK-1"}""").andExpect(status().isCreated());
        post("/receipts", """
                {"ownerId":"ACME","itemNo":"SKU-EA","qty":2,"uom":"EA","locationId":"A-01-02","sourceDoc":"RMA 900"}""")
                .andExpect(status().isCreated());

        postAs("sue", "/billing/capture", null, "")
                .andExpect(jsonPath("$.operationEvents", is(4)))         // old receipt, receipt, return, pick
                .andExpect(jsonPath("$.storageEvents", is(3)));          // end of the last three days: LPN-OLD
        postAs("sue", "/billing/capture", null, "")
                .andExpect(jsonPath("$.operationEvents", is(0))).andExpect(jsonPath("$.storageEvents", is(0)));
        postAs("sue", "/billing/vas", null, "{\"ownerId\":\"ACME\",\"service\":\"LABEL\",\"qty\":10,\"ref\":\"V-1\"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.amount", is(1)));
        postAs("sue", "/billing/vas", null, "{\"ownerId\":\"ACME\",\"service\":\"LABEL\",\"qty\":10,\"ref\":\"V-1\"}")
                .andExpect(jsonPath("$.code", is("INV_VAS_DUPLICATE")));

        String from = Instant.now().minus(10, ChronoUnit.DAYS).toString();
        String to = Instant.now().plus(1, ChronoUnit.DAYS).toString();
        List<Map<String, Object>> rows = JsonPath.read(getJson("/billing/summary?from=" + from + "&to=" + to)
                .andReturn().getResponse().getContentAsString(), "$");
        Map<String, Map<String, Object>> byType = new java.util.HashMap<>();
        rows.forEach(r -> byType.put((String) r.get("event_type"), r));
        assertThat(((Number) byType.get("RECEIPT").get("amount")).doubleValue()).isEqualTo(10.0);     // 2 LPNs × 5
        assertThat(((Number) byType.get("PICK").get("amount")).doubleValue()).isEqualTo(1.0);         // 4 units × 0.25
        assertThat(((Number) byType.get("STORAGE").get("amount")).doubleValue()).isEqualTo(4.5);      // 3 LPN-days × 1.5
        assertThat(((Number) byType.get("RETURN").get("unpriced")).intValue()).isEqualTo(1);          // no return rate
        assertThat(((Number) byType.get("VAS").get("amount")).doubleValue()).isEqualTo(1.0);

        mvc.perform(MockMvcRequestBuilders.get("/api/v1/sites/" + SITE + "/inventory/billing/events")
                        .with(TestTokens.as(tenant, "pete", Roles.PICKER)))
                .andExpect(status().isForbidden());
    }
}
