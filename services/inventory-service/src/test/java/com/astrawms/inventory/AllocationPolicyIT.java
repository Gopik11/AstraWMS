package com.astrawms.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.astrawms.common.contracts.MasterDataEvents.LocationUpserted;
import com.astrawms.common.security.Roles;
import com.astrawms.inventory.support.IntegrationTest;
import com.astrawms.test.TestTokens;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/** Allocation policy and demand replenishment (ADR-0019). */
class AllocationPolicyIT extends IntegrationTest {

    private ResultActions allocate(String order, String qty) throws Exception {
        return post("/allocations", """
                {"orderRef":"%s","orderLineRef":"000010","ownerId":"ACME","itemNo":"SKU-EA","qty":%s,"uom":"EA"}"""
                .formatted(order, qty));
    }

    private ResultActions faceRule(String location, String min, String max) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .put("/api/v1/sites/" + SITE + "/inventory/replenishment-rules/" + location + "/ACME/SKU-EA")
                .with(TestTokens.as(tenant, "ada", Roles.SOLUTION_ADMIN))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"minQty\":%s,\"maxQty\":%s}".formatted(min, max)));
    }

    private void zoned(String id, String zoneType, String type) {
        asTenant(() -> refs.upsertLocation(new LocationUpserted(SITE, id, zoneType, type, "0001", null, false, true, true,
                "ACTIVE", Instant.now(), "10", null, zoneType)));
    }

    @Test
    void pickFaceFirstFullReserveLpnsOnlyWhenCoveredAndDemandReplenishment() throws Exception {
        receive("SKU-EA", "6", "EA", "A-01-01", null, null).andExpect(status().isCreated());        // pick face
        receive("SKU-EA", "12", "EA", "A-01-02", "LPN-R1", null).andExpect(status().isCreated());   // reserve, older
        receive("SKU-EA", "30", "EA", "A-02-01", "LPN-R2", null).andExpect(status().isCreated());   // reserve
        faceRule("A-01-01", "2", "10").andExpect(status().isOk());
        assertThat(outboxTypes()).contains("PickFaceChanged");                  // the task service learns the face
        getJson("/replenishments").andExpect(jsonPath("$", hasSize(0)));       // 6 free > min 2

        // 1. The face serves the order; that takes it to its minimum, so it is replenished before the pick.
        allocate("SO-A", "4")
                .andExpect(jsonPath("$.allocatedQty", is(4)))
                .andExpect(jsonPath("$.allocations[*].locationId", contains("A-01-01")));
        getJson("/replenishments?status=OPEN")
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].qty", is(8.0)))                       // up to max 10 from 2 free
                .andExpect(jsonPath("$[0].source_location", is("A-01-02")));    // oldest reserve stock (FIFO)

        // 2. Rest of the face, then no reserve pallet is broken for an item that has a face: the shortfall waits
        //    for the replenishment (backorder recovery allocates it when it arrives).
        allocate("SO-B", "12")
                .andExpect(jsonPath("$.allocatedQty", is(2)))
                .andExpect(jsonPath("$.shortQty", is(10)));

        // 3. A full reserve pallet goes to an order that needs all of it.
        allocate("SO-C", "35")
                .andExpect(jsonPath("$.allocatedQty", is(30)))
                .andExpect(jsonPath("$.allocations[0].lpnId", is("LPN-R2")))
                .andExpect(jsonPath("$.allocations[0].qty", is(30)));
    }

    @Test
    void itemsWithoutAFaceAreTakenFromReserveLpnsAsBefore() throws Exception {
        receive("SKU-EA", "20", "EA", "A-01-01", "LPN-X", null).andExpect(status().isCreated());
        allocate("SO-D", "5")
                .andExpect(jsonPath("$.allocatedQty", is(5)))
                .andExpect(jsonPath("$.allocations[0].lpnId", is("LPN-X")));
    }

    @Test
    void stockInReceivingReturnsShippingOrQcZonesIsNotAllocable() throws Exception {
        zoned("RET-01", "RETURNS", "FLOOR");
        zoned("QC-02", "QC", "FLOOR");
        zoned("SHIP-01", "SHIPPING", "RACK");
        for (String loc : new String[] {"RET-01", "QC-02", "SHIP-01"}) {
            receive("SKU-EA", "5", "EA", loc, null, null).andExpect(status().isCreated());
        }
        allocate("SO-E", "3").andExpect(jsonPath("$.allocatedQty", is(0))).andExpect(jsonPath("$.shortQty", is(3)));
        getJson("/inbound-staging").andExpect(jsonPath("$[*].location_id", contains("RET-01")));
    }
}
