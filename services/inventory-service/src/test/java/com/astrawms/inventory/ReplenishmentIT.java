package com.astrawms.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.astrawms.common.security.Roles;
import com.astrawms.inventory.support.IntegrationTest;
import com.astrawms.test.TestTokens;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/** Min/max replenishment of forward locations (§7, RPL-001). */
class ReplenishmentIT extends IntegrationTest {

    private ResultActions rule(String location, String min, String max) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .put("/api/v1/sites/" + SITE + "/inventory/replenishment-rules/" + location + "/ACME/SKU-EA")
                .with(TestTokens.as(tenant, "ada", Roles.SOLUTION_ADMIN))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"minQty\":%s,\"maxQty\":%s}".formatted(min, max)));
    }

    private String qtyAt(String location) throws Exception {
        List<Object> qty = JsonPath.read(getJson("/balances?locationId=" + location).andReturn().getResponse()
                .getContentAsString(), "$.items[*].qty");
        return qty.isEmpty() ? "0" : String.valueOf(qty.getFirst());
    }

    @Test
    void forwardLocationAtMinimumIsReplenishedFromReserveToMaximum() throws Exception {
        receive("SKU-EA", "5", "EA", "A-01-01", null, null).andExpect(status().isCreated());      // forward
        receive("SKU-EA", "30", "EA", "A-01-02", "LPN-RES", null).andExpect(status().isCreated()); // reserve
        rule("A-01-01", "2", "10").andExpect(status().isOk());
        getJson("/replenishments").andExpect(jsonPath("$", hasSize(0)));                           // 5 > min

        // Taking stock out of the forward location down to the minimum triggers the replenishment.
        post("/adjustments", """
                {"ownerId":"ACME","itemNo":"SKU-EA","locationId":"A-01-01","qtyDelta":-3,"uom":"EA","reasonCode":"CC_TOL"}""")
                .andExpect(status().isCreated());
        getJson("/replenishments?status=OPEN")
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].qty", is(8.0)))
                .andExpect(jsonPath("$[0].source_location", is("A-01-02")))
                .andExpect(jsonPath("$[0].source_lpn", is("LPN-RES")));
        assertThat(outboxEnvelopes("ReplenRequested")).hasSize(1);
        getJson("/balances?locationId=A-01-02").andExpect(jsonPath("$.items[0].availableQty", is(22)));   // reserved

        // Already covered (2 on hand + 8 incoming > min): no second replenishment.
        post("/adjustments", """
                {"ownerId":"ACME","itemNo":"SKU-EA","locationId":"A-01-01","qtyDelta":-1,"uom":"EA","reasonCode":"CC_TOL"}""")
                .andExpect(status().isCreated());
        assertThat(outboxEnvelopes("ReplenRequested")).hasSize(1);

        String id = JsonPath.read(getJson("/replenishments?status=OPEN").andReturn().getResponse().getContentAsString(), "$[0].id");
        postWith(TestTokens.as(tenant, "rob", Roles.RECEIVER), "/replenishments/" + id + "/confirm", "TSK-1", "", null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lines[*].txnType", contains("REPLEN_OUT", "REPLEN_IN")));
        postWith(TestTokens.as(tenant, "rob", Roles.RECEIVER), "/replenishments/" + id + "/confirm", "TSK-1", "", null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.replayed", is(true)));
        assertThat(qtyAt("A-01-01")).isEqualTo("9");          // 1 + 8
        assertThat(qtyAt("A-01-02")).isEqualTo("22");
        getJson("/replenishments?status=DONE").andExpect(jsonPath("$", hasSize(1)));
        getJson("/replenishment-rules").andExpect(jsonPath("$[0].on_hand", is(9.0))).andExpect(jsonPath("$[0].incoming", is(0)));
    }

    @Test
    void topOffRunAndRulesNeedTheRightRoles() throws Exception {
        receive("SKU-EA", "1", "EA", "A-01-01", null, null).andExpect(status().isCreated());
        receive("SKU-EA", "4", "EA", "A-01-02", null, null).andExpect(status().isCreated());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/v1/sites/" + SITE + "/inventory/replenishment-rules/A-01-01/ACME/SKU-EA")
                        .with(TestTokens.as(tenant, "pete", Roles.PICKER))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"minQty\":2,\"maxQty\":10}"))
                .andExpect(status().isForbidden());
        rule("A-01-01", "2", "10").andExpect(status().isOk());
        // Only 4 in reserve: replenished with what there is.
        getJson("/replenishments?status=OPEN").andExpect(jsonPath("$[0].qty", is(4.0)));
        postWith(TestTokens.as(tenant, "sue", Roles.SUPERVISOR), "/replenishments/evaluate", null, "", null)
                .andExpect(jsonPath("$.created", is(0)));        // nothing more to take
        rule("A-01-01", "5", "4").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("INV_REPLEN_RULE_INVALID")));
    }
}
