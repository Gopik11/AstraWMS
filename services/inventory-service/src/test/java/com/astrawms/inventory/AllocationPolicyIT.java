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
                .andExpect(jsonPath("$.shortQty", is(10)))
                .andExpect(jsonPath("$.shortReason", is("WAITING_FOR_REPLENISHMENT")))   // ADR-0021
                // ADR-0028: the reason names the face, its free and capacity, the open replenishment and the rule.
                .andExpect(jsonPath("$.shortDetail", org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("pick face A-01-01 has 0 free (capacity 10)"),
                        org.hamcrest.Matchers.containsString("replenishment of 8 from A-01-02 open"),
                        org.hamcrest.Matchers.containsString("takes a pallet only when the order covers it"))));
        getJson("/faces/ACME/SKU-EA")
                .andExpect(jsonPath("$.faces[0].location_id", is("A-01-01")))
                .andExpect(jsonPath("$.faces[0].free", is(0.0)))
                .andExpect(jsonPath("$.faces[0].capacity", is(10.0)))
                .andExpect(jsonPath("$.faces[0].open_replenishments", hasSize(1)));
        // "Create replen" while one is open: the open one is returned, no second task.
        replenishFaces().andExpect(jsonPath("$[0].alreadyOpen", is(true))).andExpect(jsonPath("$[0].created", is(0)))
                .andExpect(jsonPath("$[0].open", hasSize(1)));
        getJson("/replenishments?status=OPEN").andExpect(jsonPath("$", hasSize(1)));

        // 3. A full reserve pallet goes to an order that needs all of it.
        allocate("SO-C", "35")
                .andExpect(jsonPath("$.allocatedQty", is(30)))
                .andExpect(jsonPath("$.allocations[0].lpnId", is("LPN-R2")))
                .andExpect(jsonPath("$.allocations[0].qty", is(30)));
    }

    private ResultActions replenishFaces() throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/v1/sites/" + SITE + "/inventory/faces/ACME/SKU-EA/replenish")
                .with(TestTokens.as(tenant, "sue", Roles.SUPERVISOR)));
    }

    @Test
    void aSupervisorTopsUpAFaceAboveItsMinimumFromAReservePalletWithFreeStock_ADR0027() throws Exception {
        receive("SKU-EA", "6", "EA", "A-01-01", null, null).andExpect(status().isCreated());        // face: 6 > min 2
        receive("SKU-EA", "12", "EA", "A-01-02", "LPN-R1", null).andExpect(status().isCreated());
        faceRule("A-01-01", "2", "10").andExpect(status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/sites/" + SITE + "/inventory/faces/ACME/SKU-EA/replenish")
                        .with(TestTokens.as(tenant, "rita", Roles.RECEIVER)))
                .andExpect(status().isForbidden());
        replenishFaces().andExpect(jsonPath("$[0].created", is(1))).andExpect(jsonPath("$[0].alreadyOpen", is(false)))
                .andExpect(jsonPath("$[0].open[0].qty", is(4.0)))                // up to capacity 10
                .andExpect(jsonPath("$[0].open[0].source_lpn", is("LPN-R1")));
        replenishFaces().andExpect(jsonPath("$[0].created", is(0))).andExpect(jsonPath("$[0].alreadyOpen", is(true)));
        assertThat(outboxTypes().stream().filter("ReplenRequested"::equals)).hasSize(1);   // one task only
    }

    @Test
    void itemsWithoutAFaceAreTakenFromReserveLpnsAsBefore() throws Exception {
        receive("SKU-EA", "20", "EA", "A-01-01", "LPN-X", null).andExpect(status().isCreated());
        allocate("SO-D", "5")
                .andExpect(jsonPath("$.allocatedQty", is(5)))
                .andExpect(jsonPath("$.allocations[0].lpnId", is("LPN-X")));
    }

    // ------------------------------------------------------------------ ADR-0020 explicit site policy

    private ResultActions policy(String json, String role) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .put("/api/v1/sites/" + SITE + "/inventory/allocation-policy")
                .with(TestTokens.as(tenant, "ada", role))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    void sitePolicyIsExplicitAndOnlyAdminsChangeIt() throws Exception {
        getJson("/allocation-policy")
                .andExpect(jsonPath("$.lotRotation", is("FEFO"))).andExpect(jsonPath("$.otherRotation", is("FIFO")))
                .andExpect(jsonPath("$.pickFaceFirst", is(true))).andExpect(jsonPath("$.fullLpn", is("COVERED_ONLY")));
        policy("{\"fullLpn\":\"NEVER_SPLIT\"}", Roles.SUPERVISOR).andExpect(status().isForbidden());
        policy("{\"fullLpn\":\"SOMETIMES\"}", Roles.SOLUTION_ADMIN).andExpect(jsonPath("$.code", is("INV_POLICY_INVALID")));
        policy("{\"fullLpn\":\"NEVER_SPLIT\"}", Roles.SOLUTION_ADMIN)
                .andExpect(jsonPath("$.fullLpn", is("NEVER_SPLIT"))).andExpect(jsonPath("$.lotRotation", is("FEFO")))
                .andExpect(jsonPath("$.updatedBy", is("ada")));
    }

    @Test
    void neverSplitTakesOnlyWholeReservePallets() throws Exception {
        policy("{\"fullLpn\":\"NEVER_SPLIT\"}", Roles.SOLUTION_ADMIN).andExpect(status().isOk());
        receive("SKU-EA", "20", "EA", "A-01-01", "LPN-W", null).andExpect(status().isCreated());
        allocate("SO-N1", "5").andExpect(jsonPath("$.allocatedQty", is(0))).andExpect(jsonPath("$.shortQty", is(5)))
                .andExpect(jsonPath("$.shortReason", is("POLICY_NO_SPLIT")));
        allocate("SO-N2", "25").andExpect(jsonPath("$.allocatedQty", is(20)))
                .andExpect(jsonPath("$.allocations[0].lpnId", is("LPN-W")));
    }

    @Test
    void splitAllowedBreaksReservePalletsEvenForItemsWithAFace() throws Exception {
        receive("SKU-EA", "30", "EA", "A-02-01", "LPN-S", null).andExpect(status().isCreated());
        faceRule("A-01-01", "2", "10").andExpect(status().isOk());            // empty face
        allocate("SO-S1", "4").andExpect(jsonPath("$.allocatedQty", is(0)));   // default: the face is replenished instead
        policy("{\"fullLpn\":\"SPLIT_ALLOWED\"}", Roles.SOLUTION_ADMIN).andExpect(status().isOk());
        allocate("SO-S2", "4").andExpect(jsonPath("$.allocatedQty", is(4)))
                .andExpect(jsonPath("$.allocations[0].lpnId", is("LPN-S")));
    }

    @Test
    void lotRotationFollowsThePolicy() throws Exception {
        post("/receipts", """
                {"ownerId":"ACME","itemNo":"SKU-LOT","lotNo":"OLD-LATE","expiryDate":"2029-01-01","qty":5,"uom":"EA","locationId":"A-01-01"}""")
                .andExpect(status().isCreated());
        post("/receipts", """
                {"ownerId":"ACME","itemNo":"SKU-LOT","lotNo":"NEW-EARLY","expiryDate":"2027-01-01","qty":5,"uom":"EA","locationId":"A-01-02"}""")
                .andExpect(status().isCreated());
        String lot = """
                {"orderRef":"%s","orderLineRef":"000010","ownerId":"ACME","itemNo":"SKU-LOT","qty":1,"uom":"EA"}""";
        post("/allocations", lot.formatted("SO-L1")).andExpect(jsonPath("$.allocations[0].lotNo", is("NEW-EARLY")));   // FEFO
        policy("{\"lotRotation\":\"FIFO\"}", Roles.SOLUTION_ADMIN).andExpect(status().isOk());
        post("/allocations", lot.formatted("SO-L2")).andExpect(jsonPath("$.allocations[0].lotNo", is("OLD-LATE")));    // FIFO
    }

    @Test
    void ownerPolicyWithLotAffinityFillsALineFromOneLot_ADR0021() throws Exception {
        post("/receipts", """
                {"ownerId":"ACME","itemNo":"SKU-LOT","lotNo":"LOT-A","expiryDate":"2027-01-01","qty":3,"uom":"EA","locationId":"A-01-01"}""")
                .andExpect(status().isCreated());
        post("/receipts", """
                {"ownerId":"ACME","itemNo":"SKU-LOT","lotNo":"LOT-B","expiryDate":"2028-01-01","qty":10,"uom":"EA","locationId":"A-01-02"}""")
                .andExpect(status().isCreated());
        String line = """
                {"orderRef":"%s","orderLineRef":"000010","ownerId":"ACME","itemNo":"SKU-LOT","qty":5,"uom":"EA"}""";
        policy("{\"ownerId\":\"acme\",\"lotAffinity\":true}", Roles.SOLUTION_ADMIN)
                .andExpect(jsonPath("$.ownerId", is("ACME"))).andExpect(jsonPath("$.lotAffinity", is(true)));
        getJson("/allocation-policy").andExpect(jsonPath("$.lotAffinity", is(false)));          // the site's is unchanged
        getJson("/allocation-policy/owners").andExpect(jsonPath("$[*].ownerId", contains("ACME")));
        // LOT-A (earliest expiry) cannot cover 5: the line comes whole from LOT-B.
        post("/allocations", line.formatted("SO-AF1"))
                .andExpect(jsonPath("$.allocations", hasSize(1)))
                .andExpect(jsonPath("$.allocations[0].lotNo", is("LOT-B")));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/sites/" + SITE + "/inventory/allocation-policy/owners/ACME")
                        .with(TestTokens.as(tenant, "ada", Roles.SOLUTION_ADMIN)))
                .andExpect(status().isNoContent());
        // Site policy (plain FEFO): LOT-A first, the rest from LOT-B.
        post("/allocations", line.formatted("SO-AF2"))
                .andExpect(jsonPath("$.allocations[*].lotNo", contains("LOT-A", "LOT-B")));
    }

    @Test
    void shortfallsSayWhy_ADR0021() throws Exception {
        allocate("SO-W1", "2").andExpect(jsonPath("$.shortReason", is("NO_STOCK")));
        receive("SKU-EA", "4", "EA", "A-01-01", "LPN-Q", null).andExpect(status().isCreated());
        allocate("SO-W2", "4").andExpect(jsonPath("$.allocatedQty", is(4)));
        allocate("SO-W3", "1").andExpect(jsonPath("$.shortReason", is("ALLOCATED_ELSEWHERE")));
        post("/receipts", """
                {"ownerId":"ACME","itemNo":"SKU-FROZEN","qty":3,"uom":"EA","locationId":"F-01-01","status":"QI"}""")
                .andExpect(status().isCreated());
        post("/allocations", """
                {"orderRef":"SO-W4","orderLineRef":"000010","ownerId":"ACME","itemNo":"SKU-FROZEN","qty":1,"uom":"EA"}""")
                .andExpect(jsonPath("$.shortReason", is("NOT_AVAILABLE")))
                .andExpect(jsonPath("$.shortDetail", org.hamcrest.Matchers.containsString("QI")));
    }

    @Test
    void stockInReceivingReturnsShippingOrQcZonesIsNotAllocable() throws Exception {
        zoned("RET-01", "RETURNS", "FLOOR");
        zoned("QC-02", "QC", "FLOOR");
        zoned("SHIP-01", "SHIPPING", "RACK");
        for (String loc : new String[] {"RET-01", "QC-02", "SHIP-01"}) {
            receive("SKU-EA", "5", "EA", loc, null, null).andExpect(status().isCreated());
        }
        allocate("SO-E", "3").andExpect(jsonPath("$.allocatedQty", is(0))).andExpect(jsonPath("$.shortQty", is(3)))
                .andExpect(jsonPath("$.shortReason", is("AWAITING_PUTAWAY")))
                .andExpect(jsonPath("$.shortDetail", org.hamcrest.Matchers.containsString("RET-01")));
        getJson("/inbound-staging").andExpect(jsonPath("$[*].location_id", contains("RET-01")));
    }
}
