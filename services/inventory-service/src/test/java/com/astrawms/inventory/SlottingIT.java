package com.astrawms.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.astrawms.common.contracts.MasterDataEvents.LocationUpserted;
import com.astrawms.common.security.Roles;
import com.astrawms.inventory.support.IntegrationTest;
import com.astrawms.test.TestTokens;
import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** Slotting: item–location master, velocity from picks, golden-zone suggestion, reslot (ADR-0021). */
class SlottingIT extends IntegrationTest {

    private void pickSlot(String id, int seq) {
        asTenant(() -> refs.upsertLocation(new LocationUpserted(SITE, id, "PICK", "SHELF", "0001", null, false, true, true,
                "ACTIVE", Instant.now(), "10", seq, "PICK")));
    }

    private ResultActions put(String path, String json) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.put("/api/v1/sites/" + SITE + "/inventory" + path)
                .with(TestTokens.as(tenant, "ada", Roles.SOLUTION_ADMIN))
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions postAdmin(String path, String json) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post("/api/v1/sites/" + SITE + "/inventory" + path)
                .with(TestTokens.as(tenant, "ada", Roles.SOLUTION_ADMIN))
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> row(String item) throws Exception {
        List<Map<String, Object>> rows = JsonPath.read(getJson("/slotting").andReturn().getResponse().getContentAsString(), "$");
        return rows.stream().filter(r -> item.equals(r.get("item_no"))).findFirst().orElseThrow();
    }

    @Test
    void velocityFromPicksDrivesGoldenZoneSuggestionsAndReslotMovesTheFace() throws Exception {
        pickSlot("P-01", 1);
        pickSlot("P-02", 2);
        pickSlot("P-09", 9);
        receive("SKU-EA", "10", "EA", "P-09", null, null).andExpect(status().isCreated());
        receive("SKU-EA", "30", "EA", "A-01-02", "LPN-RES", null).andExpect(status().isCreated());
        put("/replenishment-rules/P-09/ACME/SKU-EA", "{\"minQty\":1,\"maxQty\":20}").andExpect(status().isOk());

        // Three picks of SKU-EA from its face: it is the site's fast mover.
        for (int i = 1; i <= 3; i++) {
            String body = post("/allocations", """
                    {"orderRef":"SO-%d","orderLineRef":"000010","ownerId":"ACME","itemNo":"SKU-EA","qty":1,"uom":"EA"}"""
                    .formatted(i)).andReturn().getResponse().getContentAsString();
            String alloc = JsonPath.read(body, "$.allocations[0].id");
            post("/allocations/" + alloc + "/pick", """
                    {"qty":1,"toLocationId":"STAGE-OUT","toLpnId":"PK-%d"}""".formatted(i)).andExpect(status().isCreated());
        }

        Map<String, Object> ea = row("SKU-EA");
        assertThat(ea.get("velocity_class")).isEqualTo("A");
        assertThat(ea.get("velocity_source")).isEqualTo("COMPUTED");
        assertThat(((Number) ea.get("picks_30d")).intValue()).isEqualTo(3);
        assertThat(ea.get("pick_face")).isEqualTo("P-09");
        assertThat(ea.get("suggested_face")).isEqualTo("P-01");                 // the closest free golden slot
        assertThat((String) ea.get("suggestion_reason")).contains("closer");

        // Reslot: the face moves; the free stock left at P-09 becomes a MOVE task to P-01.
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/sites/" + SITE + "/inventory/slotting/ACME/SKU-EA/reslot")
                        .with(TestTokens.as(tenant, "sue", Roles.SUPERVISOR)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toLocation\":\"P-01\"}"))
                .andExpect(status().isForbidden());
        postAdmin("/slotting/ACME/SKU-EA/reslot", "{\"toLocation\":\"P-01\"}")
                .andExpect(jsonPath("$.pickFace", is("P-01")))
                .andExpect(jsonPath("$.moves", hasSize(1)))
                .andExpect(jsonPath("$.moves[0].from", is("P-09")))
                .andExpect(jsonPath("$.moves[0].qty", is(7)));
        assertThat(outboxTypes()).contains("MoveRequested");
        getJson("/replenishment-rules")
                .andExpect(jsonPath("$[?(@.location_id == 'P-01')].active", org.hamcrest.Matchers.contains(true)))
                .andExpect(jsonPath("$[?(@.location_id == 'P-09')].active", org.hamcrest.Matchers.contains(false)));
        postAdmin("/slotting/ACME/SKU-EA/reslot", "{\"toLocation\":\"P-01\"}")
                .andExpect(jsonPath("$.code", is("INV_RESLOT_SAME_LOCATION")));
    }

    @Test
    void slottingMasterIsPublishedForPutaway() throws Exception {
        put("/slotting/ACME/SKU-EA", "{\"reserveZone\":\"stor\",\"unitsPerPallet\":48,\"velocityClass\":\"c\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reserve_zone", is("STOR")))
                .andExpect(jsonPath("$.velocity_class", is("C")))
                .andExpect(jsonPath("$.velocity_source", is("SET")));
        put("/slotting/ACME/SKU-EA", "{\"velocityClass\":\"X\"}").andExpect(jsonPath("$.code", is("INV_SLOTTING_INVALID")));
        assertThat(outboxTypes()).contains("SlottingChanged");
    }
}
