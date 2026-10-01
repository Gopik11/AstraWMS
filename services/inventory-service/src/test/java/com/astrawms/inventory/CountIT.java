package com.astrawms.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.astrawms.common.contracts.MasterDataEvents.ItemUpserted;
import com.astrawms.common.security.Roles;
import com.astrawms.inventory.support.IntegrationTest;
import com.astrawms.test.TestTokens;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Cycle counting (§6.3): blind counts, tolerance, recounts by other users, approval with SoD and value limits. */
class CountIT extends IntegrationTest {

    @BeforeEach
    void valuedItem() {
        // 10.00 per unit; tolerance defaults: 2 units and 50.00
        asTenant(() -> refs.upsertItem(new ItemUpserted(OWNER, "SKU-VAL", "EA", "ACTIVE", null, null, false,
                List.of(new ItemUpserted.Site(SITE, false, "NONE", "ACTIVE")), List.of(), Instant.now(), new BigDecimal("10"))));
    }

    private String openCount(String location) throws Exception {
        String body = postWith(TestTokens.as(tenant, "sue", Roles.SUPERVISOR), "/counts", null, """
                {"locationIds":["%s"],"note":"ad hoc"}""".formatted(location), null)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.countIds[0]");
    }

    private ResultActions count(String id, String user, String key, String lines) throws Exception {
        return postWith(counter(user), "/counts/" + id + "/results", key, "{\"lines\":[" + lines + "]}", null);
    }

    private RequestPostProcessor counter(String user) {
        return TestTokens.as(tenant, user, Roles.PICKER);
    }

    private static String line(String item, String qty) {
        return "{\"ownerId\":\"ACME\",\"itemNo\":\"%s\",\"qty\":%s}".formatted(item, qty);
    }

    private String balance(String location) throws Exception {
        String body = getJson("/balances?locationId=" + location).andReturn().getResponse().getContentAsString();
        List<Object> qty = JsonPath.read(body, "$.items[*].qty");
        return qty.isEmpty() ? "0" : String.valueOf(qty.getFirst());
    }

    @Test
    void matchingCountClosesWithoutAdjustment() throws Exception {
        receive("SKU-VAL", "5", "EA", "A-01-01", null, null).andExpect(status().isCreated());
        String id = openCount("A-01-01");
        count(id, "cathy", "c-1", line("SKU-VAL", "5")).andExpect(jsonPath("$.status", is("CLOSED")));
        assertThat(balance("A-01-01")).isEqualTo("5");
    }

    @Test
    void varianceWithinToleranceIsAdjustedAutomatically_CCTOL() throws Exception {
        receive("SKU-VAL", "5", "EA", "A-01-01", null, null).andExpect(status().isCreated());
        String id = openCount("A-01-01");
        count(id, "cathy", "c-1", line("SKU-VAL", "4"))
                .andExpect(jsonPath("$.status", is("ADJUSTED")))
                .andExpect(jsonPath("$.systemQty").doesNotExist());              // blind: no system quantity returned
        assertThat(balance("A-01-01")).isEqualTo("4");
        assertThat(outboxEnvelopes("GoodsMovement").getLast()).contains("\"reasonCode\":\"CC_TOL\"", "\"movementType\":\"ADJ_NEG\"");
    }

    @Test
    void largeVarianceNeedsIndependentRecountAndApproval_INV008() throws Exception {
        receive("SKU-VAL", "20", "EA", "A-01-01", null, null).andExpect(status().isCreated());
        String id = openCount("A-01-01");
        count(id, "cathy", "c-1", line("SKU-VAL", "12")).andExpect(jsonPath("$.status", is("RECOUNT")));
        String recount = outboxEnvelopes("CountRequested").getLast();
        assertThat(recount).contains("\"sequence\":2", "\"excludedUsers\":[\"cathy\"]");

        count(id, "cathy", "c-2", line("SKU-VAL", "12"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code", is("INV_RECOUNT_SAME_USER")));
        count(id, "dave", "c-3", line("SKU-VAL", "12")).andExpect(jsonPath("$.status", is("PENDING_APPROVAL")));

        // A counter may not approve; a limit below the variance value (8 × 10.00 = 80.00) is not enough.
        postWith(TestTokens.as(tenant, "dave", Roles.SUPERVISOR), "/counts/" + id + "/approve", null, "{}", null)
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code", is("INV_SELF_APPROVAL")));
        postWith(TestTokens.bearer(TestTokens.token().tenant(tenant).user("max").roles(Roles.INV_MANAGER).approvalLimit("50").sign()),
                "/counts/" + id + "/approve", null, "{}", null)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code", is("APPROVAL_LIMIT_EXCEEDED")));
        postWith(TestTokens.bearer(TestTokens.token().tenant(tenant).user("mona").roles(Roles.INV_MANAGER).approvalLimit("500").sign()),
                "/counts/" + id + "/approve", null, "{}", null)
                .andExpect(jsonPath("$.status", is("ADJUSTED")))
                .andExpect(jsonPath("$.decidedBy", is("mona")))
                .andExpect(jsonPath("$.varianceValue", is(80.00)));
        assertThat(balance("A-01-01")).isEqualTo("12");
        assertThat(outboxEnvelopes("GoodsMovement").getLast()).contains("\"reasonCode\":\"CC_VAR\"", "\"approvedBy\":\"mona\"");
    }

    @Test
    void differingRecountGoesToAThirdCountAndCanBeRejected() throws Exception {
        receive("SKU-VAL", "20", "EA", "A-01-01", null, null).andExpect(status().isCreated());
        String id = openCount("A-01-01");
        count(id, "cathy", "c-1", line("SKU-VAL", "12")).andExpect(jsonPath("$.status", is("RECOUNT")));
        count(id, "dave", "c-2", line("SKU-VAL", "15")).andExpect(jsonPath("$.status", is("RECOUNT")));
        assertThat(outboxEnvelopes("CountRequested").getLast()).contains("\"sequence\":3", "\"excludedUsers\":[\"cathy\",\"dave\"]");
        count(id, "erin", "c-3", line("SKU-VAL", "15")).andExpect(jsonPath("$.status", is("PENDING_APPROVAL")));

        getJson("/counts/" + id).andExpect(jsonPath("$.variances", hasSize(1)))
                .andExpect(jsonPath("$.variances[0].variance", is(-5)));
        postWith(TestTokens.as(tenant, "sue", Roles.SUPERVISOR), "/counts/" + id + "/reject", null, "{\"note\":\"mis-putaway\"}", null)
                .andExpect(jsonPath("$.status", is("REJECTED")));
        assertThat(balance("A-01-01")).isEqualTo("20");
    }

    @Test
    void countersDoNotSeeSystemQuantities_INV003() throws Exception {
        receive("SKU-VAL", "20", "EA", "A-01-01", null, null).andExpect(status().isCreated());
        String id = openCount("A-01-01");
        count(id, "cathy", "c-1", line("SKU-VAL", "12")).andExpect(status().isOk());
        mvcGet("/counts/" + id, counter("cathy"))
                .andExpect(jsonPath("$.variances", hasSize(0)))
                .andExpect(jsonPath("$.results", hasSize(0)));
    }

    @Test
    void shortPickOpensACountOfTheSourceLocation_PCK003b() throws Exception {
        receive("SKU-EA", "10", "EA", "A-01-01", null, null).andExpect(status().isCreated());
        String alloc = JsonPath.read(post("/allocations", """
                {"orderRef":"SO-C","orderLineRef":"000010","ownerId":"ACME","itemNo":"SKU-EA","qty":4,"uom":"EA"}""")
                .andReturn().getResponse().getContentAsString(), "$.allocations[0].id");
        post("/allocations/" + alloc + "/pick", """
                {"qty":3,"toLocationId":"STAGE-OUT","toLpnId":"PK-SO-C","shortClose":true}""").andExpect(status().isCreated());
        getJson("/counts?status=OPEN")
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].location_id", is("A-01-01")))
                .andExpect(jsonPath("$[0].trigger", is("SHORT_PICK")));
        assertThat(outboxEnvelopes("CountRequested")).hasSize(1);
    }

    private ResultActions mvcGet(String path, RequestPostProcessor auth) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/v1/sites/" + SITE + "/inventory" + path).with(auth));
    }
}
