package com.astrawms.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.astrawms.common.contracts.MasterDataEvents.ItemUpserted;
import com.astrawms.common.security.Roles;
import com.astrawms.inventory.support.IntegrationTest;
import com.astrawms.test.TestTokens;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/** Full physical inventory: counts per location, freeze, review, posting by a non-counter (ADR-0022). */
class PhysicalInventoryIT extends IntegrationTest {

    private ResultActions pi(String path, String user, String json) throws Exception {
        return postWith(TestTokens.as(tenant, user, Roles.INV_MANAGER), "/physical-inventories" + path, null, json, null);
    }

    private ResultActions count(UUID id, String user, String lines) throws Exception {
        return postWith(TestTokens.as(tenant, user, Roles.PICKER), "/counts/" + id + "/results", "k-" + id + user,
                "{\"lines\":[" + lines + "]}", null);
    }

    private UUID countAt(String location) {
        return queryAsTenant(() -> jdbc.sql("select id from stock_count where location_id = :l and pi_id is not null")
                .param("l", location).query(UUID.class).single());
    }

    @Test
    void zonesAreFrozenCountedReviewedAndPostedTogether() throws Exception {
        asTenant(() -> refs.upsertItem(new ItemUpserted(OWNER, "SKU-VAL", "EA", "ACTIVE", null, null, false,
                List.of(new ItemUpserted.Site(SITE, false, "NONE", "ACTIVE")), List.of(), Instant.now(), new BigDecimal("10"))));
        receive("SKU-VAL", "10", "EA", "A-01-01", null, null).andExpect(status().isCreated());
        receive("SKU-VAL", "5", "EA", "A-01-02", null, null).andExpect(status().isCreated());

        pi("", "mia", "{\"zones\":[\"stor\"],\"freeze\":true}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.pi_no", is("PI00001")))
                .andExpect(jsonPath("$.status", is("PLANNED")));
        pi("", "mia", "{}").andExpect(jsonPath("$.code", is("INV_PI_OPEN")));
        pi("/PI00001/start", "mia", "").andExpect(jsonPath("$.status", is("COUNTING")))
                .andExpect(jsonPath("$.frozenLocations", is(3)))                          // A-01-01, A-01-02, A-02-01
                .andExpect(jsonPath("$.progress.OPEN", is(3)));
        assertThat(outboxTypes()).contains("CountRequested");

        // Frozen: no movement, no allocation.
        receive("SKU-VAL", "1", "EA", "A-01-01", null, null)
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code", is("INV_LOCATION_FROZEN")));
        post("/allocations", """
                {"orderRef":"SO-PI","orderLineRef":"000010","ownerId":"ACME","itemNo":"SKU-VAL","qty":1,"uom":"EA"}""")
                .andExpect(jsonPath("$.allocatedQty", is(0)));
        receive("SKU-VAL", "1", "EA", "H-01-01", null, null).andExpect(status().isCreated());   // other zones work

        String val = "{\"ownerId\":\"ACME\",\"itemNo\":\"SKU-VAL\",\"qty\":%s}";
        count(countAt("A-01-01"), "cara", val.formatted("10")).andExpect(jsonPath("$.status", is("CLOSED")));
        count(countAt("A-01-02"), "cara", val.formatted("4")).andExpect(jsonPath("$.status", is("PENDING_APPROVAL")));
        postWith(TestTokens.as(tenant, "mia", Roles.INV_MANAGER), "/counts/" + countAt("A-01-02") + "/approve", null, "", null)
                .andExpect(jsonPath("$.code", is("INV_COUNT_IN_PHYSICAL_INVENTORY")));
        pi("/PI00001/post", "mia", "").andExpect(jsonPath("$.code", is("INV_PI_NOT_COUNTED")));     // A-02-01 open
        count(countAt("A-02-01"), "cara", "").andExpect(jsonPath("$.status", is("CLOSED")));

        mvcGet("/physical-inventories/PI00001")
                .andExpect(jsonPath("$.differences.length()", is(1)))
                .andExpect(jsonPath("$.differences[0].difference", is(-1)))
                .andExpect(jsonPath("$.netValue", is(-10.0)));
        pi("/PI00001/post", "cara", "").andExpect(jsonPath("$.code", is("INV_SELF_APPROVAL")));
        pi("/PI00001/post", "mia", "").andExpect(jsonPath("$.status", is("POSTED")))
                .andExpect(jsonPath("$.frozenLocations", is(0)));
        assertThat(onHand("SKU-VAL", "A-01-02", "AVAILABLE")).isEqualByComparingTo("4");
        assertThat(outboxEnvelopes("GoodsMovement")).anySatisfy(m ->
                assertThat(m).contains("\"movementType\":\"ADJ_NEG\"", "\"reasonCode\":\"PI_DIFF\""));
        receive("SKU-VAL", "1", "EA", "A-01-01", null, null).andExpect(status().isCreated());   // unfrozen
    }

    private ResultActions mvcGet(String path) throws Exception {
        return getJson(path);
    }
}
