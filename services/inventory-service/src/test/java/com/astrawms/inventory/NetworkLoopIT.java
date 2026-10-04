package com.astrawms.inventory;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.astrawms.common.contracts.MasterDataEvents.ItemUpserted;
import com.astrawms.common.contracts.MasterDataEvents.LocationUpserted;
import com.astrawms.common.security.Roles;
import com.astrawms.inventory.support.IntegrationTest;
import com.astrawms.test.TestTokens;
import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * ADR-0025 network loop: a transfer's goods issue puts stock in transit, the receiving store's receipt takes it out;
 * the enterprise item balance shows both sides; store replenishment recommends from the warehouse; the cycle plan
 * makes never-counted locations due.
 */
class NetworkLoopIT extends IntegrationTest {

    @BeforeEach
    void store() {
        asTenant(() -> {
            refs.upsertItem(new ItemUpserted(OWNER, "ABC", "EA", "ACTIVE", null, null, false,
                    List.of(new ItemUpserted.Site(SITE, false, "NONE", "ACTIVE"), new ItemUpserted.Site("ST03", false, "NONE", "ACTIVE")),
                    List.of(), Instant.now()));
            refs.upsertLocation(new LocationUpserted("ST03", "S-01", "PICK", "SHELF", "0001", null, false, true, true,
                    "ACTIVE", Instant.now(), "21", 10));
        });
    }

    private ResultActions at(String site, String path, String json) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/v1/sites/" + site + "/inventory" + path)
                .with(TestTokens.as(tenant, "sue", TestTokens.ALL_ROLES)).header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions network(String path) throws Exception {
        return mvc.perform(get(path).with(TestTokens.as(tenant, "sue", TestTokens.ALL_ROLES)));
    }

    @Test
    void transferStockIsInTransitUntilTheStoreReceivesIt() throws Exception {
        receive("ABC", "30", "EA", "A-01-01", null, null).andExpect(status().isCreated());
        String alloc = JsonPath.read(at(SITE, "/allocations", """
                {"orderRef":"TR-DC1-000001","orderLineRef":"000010","ownerId":"ACME","itemNo":"ABC","qty":6,"uom":"EA"}""")
                .andReturn().getResponse().getContentAsString(), "$.allocations[0].id");
        at(SITE, "/allocations/" + alloc + "/pick", """
                {"qty":6,"toLocationId":"STAGE-OUT","toLpnId":"TR1"}""").andExpect(status().isCreated());
        // Picked and staged: still on hand at DC1, shown as picked, not available.
        network("/api/v1/network/items/ACME/ABC")
                .andExpect(jsonPath("$.sites[0].site_id", is("DC1")))
                .andExpect(jsonPath("$.sites[0].picked", is(6.0)))
                .andExpect(jsonPath("$.sites[0].available", is(24.0)));
        at(SITE, "/issues", """
                {"orderRef":"TR-DC1-000001","transferToSite":"ST03"}""").andExpect(status().isCreated());
        // DC1 down by 6, nothing at ST03 yet: 6 in transit, the network total unchanged.
        network("/api/v1/network/items/ACME/ABC")
                .andExpect(jsonPath("$.totalOnHand", is(24.0)))
                .andExpect(jsonPath("$.totalInTransit", is(6.0)))
                .andExpect(jsonPath("$.networkTotal", is(30.0)))
                .andExpect(jsonPath("$.inTransit[0].transfer_no", is("TR-DC1-000001")))
                .andExpect(jsonPath("$.sites[?(@.site_id == 'ST03')].in_transit_in", org.hamcrest.Matchers.contains(6.0)))
                .andExpect(jsonPath("$.sites[?(@.site_id == 'DC1')].in_transit_out", org.hamcrest.Matchers.contains(6.0)));
        // A replayed goods issue does not put it in transit twice.
        assertTransit("6");

        at("ST03", "/receipts", """
                {"ownerId":"ACME","itemNo":"ABC","qty":4,"uom":"EA","locationId":"S-01","sourceDoc":"TR-DC1-000001/000010"}""")
                .andExpect(status().isCreated());
        network("/api/v1/network/items/ACME/ABC").andExpect(jsonPath("$.totalInTransit", is(2.0)))
                .andExpect(jsonPath("$.networkTotal", is(30.0)));
        at("ST03", "/receipts", """
                {"ownerId":"ACME","itemNo":"ABC","qty":2,"uom":"EA","locationId":"S-01","sourceDoc":"TR-DC1-000001/000010"}""")
                .andExpect(status().isCreated());
        // Received: in transit is gone; DC1 reduction (6) = ST03 increase (6).
        network("/api/v1/network/items/ACME/ABC")
                .andExpect(jsonPath("$.totalInTransit", is(0)))
                .andExpect(jsonPath("$.inTransit", hasSize(0)))
                .andExpect(jsonPath("$.sites[?(@.site_id == 'ST03')].on_hand", org.hamcrest.Matchers.contains(6.0)))
                .andExpect(jsonPath("$.networkTotal", is(30.0)));
    }

    private void assertTransit(String qty) {
        java.math.BigDecimal open = queryAsTenant(() -> jdbc.sql("select sum(qty - qty_received) from stock_in_transit")
                .query(java.math.BigDecimal.class).single());
        org.assertj.core.api.Assertions.assertThat(open).isEqualByComparingTo(qty);
    }

    @Test
    void storesGetARecommendedTransferFromTheWarehouseWithReasonAndConfidence() throws Exception {
        receive("ABC", "100", "EA", "A-01-01", null, null).andExpect(status().isCreated());
        at("ST03", "/receipts", """
                {"ownerId":"ACME","itemNo":"ABC","qty":3,"uom":"EA","locationId":"S-01"}""").andExpect(status().isCreated());
        mvc.perform(put("/api/v1/sites/ST03/inventory/store-policies").with(TestTokens.as(tenant, "rita", Roles.RECEIVER))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/sites/ST03/inventory/store-policies").with(TestTokens.as(tenant, "sue", Roles.SUPERVISOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ownerId\":\"ACME\",\"itemNo\":\"ABC\",\"minQty\":10,\"maxQty\":40,\"safetyQty\":2,\"transitDays\":1}"))
                .andExpect(jsonPath("$[0].max_qty", is(40.0)));
        network("/api/v1/network/replenishment?siteId=ST03")
                .andExpect(jsonPath("$[0].recommended", is(true)))
                .andExpect(jsonPath("$[0].sourceSite", is("DC1")))
                .andExpect(jsonPath("$[0].qty", is(37)))                    // max 40 − projected 3
                .andExpect(jsonPath("$[0].confidence", is("LOW")))           // no usage history yet
                .andExpect(jsonPath("$[0].reason", org.hamcrest.Matchers.containsString("below min 10")));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/sites/ST03/inventory/store-replenishment/accept")
                        .with(TestTokens.as(tenant, "sue", Roles.SUPERVISOR)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ownerId\":\"ACME\",\"itemNo\":\"ABC\",\"qty\":37,\"sourceSite\":\"DC1\",\"transferNo\":\"TR-DC1-000009\","
                                + "\"requiredDate\":\"2026-10-06\",\"confidence\":\"LOW\",\"reason\":\"test\"}"))
                .andExpect(jsonPath("$.transferNo", is("TR-DC1-000009")));
        // Accepted: the open transfer is pipeline, so the need is covered and not recommended again.
        network("/api/v1/network/replenishment?siteId=ST03")
                .andExpect(jsonPath("$[0].recommended", is(false)))
                .andExpect(jsonPath("$[0].inTransit", is(37.0)));
    }

    @Test
    void ownershipRecallAndTheCyclePlan() throws Exception {
        receive("SKU-LOT", "5", "EA", "A-01-02", null, "L-RECALL").andExpect(status().isCreated());
        mvc.perform(put("/api/v1/network/owners/ACME").with(TestTokens.as(tenant, "ada", Roles.SOLUTION_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Acme\",\"ownershipType\":\"CONSIGNMENT\"}"))
                .andExpect(jsonPath("$.ownership_type", is("CONSIGNMENT")));
        network("/api/v1/network/recall?itemNo=SKU-LOT&lotNo=L-RECALL")
                .andExpect(jsonPath("$.balances", hasSize(1)))
                .andExpect(jsonPath("$.balances[0].location_id", is("A-01-02")))
                .andExpect(jsonPath("$.balances[0].ownership_type", is("CONSIGNMENT")));
        network("/api/v1/sites/DC1/inventory/counts/plan")
                .andExpect(jsonPath("$.frequency.aDays", is(30)))
                .andExpect(jsonPath("$.locations[0].due", is(true)))
                .andExpect(jsonPath("$.locations[0].reason", is("Never counted")));
        at(SITE, "/counts/plan/open?limit=1", "{}").andExpect(jsonPath("$.opened", is(1)));
        network("/api/v1/sites/DC1/inventory/counts/plan").andExpect(jsonPath("$.locations[?(@.count_open == true)]", hasSize(1)));
    }
}
