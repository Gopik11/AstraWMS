package com.astrawms.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.astrawms.inventory.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/** Allocation, picking and issue (scope §3.4, §4, §5; PUT-006, PCK-003, SHP-007). */
class AllocationIT extends IntegrationTest {

    private ResultActions allocate(String order, String line, String item, String qty, String rotation) throws Exception {
        return post("/allocations", """
                {"orderRef":"%s","orderLineRef":"%s","ownerId":"ACME","itemNo":"%s","qty":%s,"uom":"EA"%s}"""
                .formatted(order, line, item, qty, rotation == null ? "" : ",\"rotation\":\"" + rotation + "\""));
    }

    private ResultActions pick(String allocationId, String qty, String toLpn, String extra) throws Exception {
        return post("/allocations/" + allocationId + "/pick", """
                {"qty":%s,"toLocationId":"STAGE-OUT","toLpnId":"%s"%s}""".formatted(qty, toLpn, extra));
    }

    private String receiveLot(String lot, String expiry, String qty, String location) throws Exception {
        return post("/receipts", """
                {"ownerId":"ACME","itemNo":"SKU-LOT","lotNo":"%s","expiryDate":"%s","qty":%s,"uom":"EA","locationId":"%s"}"""
                .formatted(lot, expiry, qty, location)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void fefoAllocatesEarliestExpiryFirstAndNeverFromStaging_PUT006() throws Exception {
        receiveLot("LATE", "2028-01-01", "10", "A-01-01");
        receiveLot("EARLY", "2027-01-01", "4", "A-01-02");
        receiveLot("DOCKED", "2026-12-01", "50", "DOCK-01");          // earliest, but on the dock: not allocable

        allocate("SO-1", "000010", "SKU-LOT", "6", null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.allocatedQty", is(6)))
                .andExpect(jsonPath("$.shortQty", is(0)))
                .andExpect(jsonPath("$.allocations[*].lotNo", contains("EARLY", "LATE")))
                .andExpect(jsonPath("$.allocations[*].qty", contains(4, 2)));

        allocate("SO-2", "000010", "SKU-LOT", "20", null)
                .andExpect(jsonPath("$.allocatedQty", is(8)))
                .andExpect(jsonPath("$.shortQty", is(12)));                 // OUT-EX-01
    }

    @Test
    void fifoUsesReceiptOrder() throws Exception {
        receiveLot("FIRST", "2029-01-01", "5", "A-01-01");
        receiveLot("SECOND", "2027-01-01", "5", "A-01-02");
        allocate("SO-3", "000010", "SKU-LOT", "5", "FIFO")
                .andExpect(jsonPath("$.allocations[0].lotNo", is("FIRST")));
    }

    @Test
    void allocatedStockIsProtectedAndPickMovesItToStagingStillAllocated() throws Exception {
        receive("SKU-EA", "10", "EA", "A-01-01", "LPN-P", null).andExpect(status().isCreated());
        String alloc = JsonPath.read(body(allocate("SO-4", "000010", "SKU-EA", "8", null)), "$.allocations[0].id");

        post("/adjustments", """
                {"ownerId":"ACME","itemNo":"SKU-EA","locationId":"A-01-01","lpnId":"LPN-P","qtyDelta":-5,"uom":"EA","reasonCode":"CC_TOL"}""")
                .andExpect(jsonPath("$.code", is("INV_STOCK_ALLOCATED")));

        pick(alloc, "5", "PK-SO-4", "").andExpect(status().isCreated())
                .andExpect(jsonPath("$.lines[*].txnType", contains("PICK_OUT", "PICK_IN")));
        pick(alloc, "4", "PK-SO-4", "").andExpect(jsonPath("$.code", is("INV_PICK_QTY_EXCEEDS")));
        pick(alloc, "3", "PK-SO-4", "").andExpect(status().isCreated());

        getJson("/balances?locationId=STAGE-OUT")
                .andExpect(jsonPath("$.items[0].qty", is(8)))
                .andExpect(jsonPath("$.items[0].availableQty", is(0)));     // cannot be re-allocated
        getJson("/allocations?orderRef=SO-4").andExpect(jsonPath("$[0].status", is("PICKED")));
        assertThat(onHand("SKU-EA", "A-01-01", "AVAILABLE")).isEqualByComparingTo("2");
    }

    @Test
    void shortCloseReleasesTheRemainder_PCK003() throws Exception {
        receive("SKU-EA", "10", "EA", "A-01-01", "LPN-S", null).andExpect(status().isCreated());
        String alloc = JsonPath.read(body(allocate("SO-5", "000010", "SKU-EA", "10", null)), "$.allocations[0].id");
        pick(alloc, "7", "PK-SO-5", ",\"shortClose\":true").andExpect(status().isCreated());
        getJson("/allocations?orderRef=SO-5")
                .andExpect(jsonPath("$[0].status", is("PICKED")))
                .andExpect(jsonPath("$[0].qtyAllocated", is(7)));
        getJson("/balances?locationId=A-01-01").andExpect(jsonPath("$.items[0].availableQty", is(3)));
    }

    @Test
    void issueRemovesPickedStockAndShipsSerials_SHP007() throws Exception {
        post("/receipts", """
                {"ownerId":"ACME","itemNo":"SKU-SER","qty":3,"uom":"EA","locationId":"A-01-01","lpnId":"LPN-SER",
                 "serials":["S1","S2","S3"]}""").andExpect(status().isCreated());
        receiveLot("L1", "2027-06-30", "5", "A-01-02");
        String serAlloc = JsonPath.read(body(allocate("SO-6", "000010", "SKU-SER", "2", null)), "$.allocations[0].id");
        String lotAlloc = JsonPath.read(body(allocate("SO-6", "000020", "SKU-LOT", "5", null)), "$.allocations[0].id");

        pick(serAlloc, "2", "PK-SO-6", "").andExpect(jsonPath("$.code", is("INV_SERIALS_REQUIRED")));
        pick(serAlloc, "2", "PK-SO-6", ",\"serials\":[\"S9\",\"S2\"]").andExpect(jsonPath("$.code", is("INV_SERIAL_NOT_AT_SOURCE")));
        pick(serAlloc, "2", "PK-SO-6", ",\"serials\":[\"S1\",\"S2\"]").andExpect(status().isCreated());

        post("/issues", """
                {"orderRef":"SO-6"}""").andExpect(jsonPath("$.code", is("INV_ALLOCATIONS_OPEN")));
        pick(lotAlloc, "5", "PK-SO-6", "").andExpect(status().isCreated());

        post("/issues", """
                {"orderRef":"SO-6"}""")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lines", hasSize(2)))
                .andExpect(jsonPath("$.lines[0].serials", contains("S1", "S2")))
                .andExpect(jsonPath("$.lines[1].lots[0].lotNo", is("L1")));
        getJson("/serials/ACME/SKU-SER/S1").andExpect(jsonPath("$.status", is("SHIPPED")));
        getJson("/serials/ACME/SKU-SER/S3").andExpect(jsonPath("$.status", is("IN_STOCK")));
        getJson("/balances?locationId=STAGE-OUT").andExpect(jsonPath("$.items", hasSize(0)));
        List<String> ledger = queryAsTenant(() -> jdbc.sql("select txn_type from inventory_txn where txn_type = 'ISSUE'")
                .query(String.class).list());
        assertThat(ledger).hasSize(2);
    }

    @Test
    void releaseFreesOpenAllocations() throws Exception {
        receive("SKU-EA", "10", "EA", "A-01-01", null, null).andExpect(status().isCreated());
        allocate("SO-7", "000010", "SKU-EA", "6", null).andExpect(status().isCreated());
        post("/allocations/release", """
                {"orderRef":"SO-7"}""")
                .andExpect(jsonPath("$.releasedAllocations", is(1)))
                .andExpect(jsonPath("$.releasedQty", is(6)));
        getJson("/balances?locationId=A-01-01").andExpect(jsonPath("$.items[0].availableQty", is(10)));
    }

    @Test
    void reallocationSkipsExcludedLocations_PCK003() throws Exception {
        receive("SKU-EA", "5", "EA", "A-01-01", null, null).andExpect(status().isCreated());
        receive("SKU-EA", "5", "EA", "A-01-02", null, null).andExpect(status().isCreated());
        post("/allocations", """
                {"orderRef":"SO-9","orderLineRef":"000010","ownerId":"ACME","itemNo":"SKU-EA","qty":3,"uom":"EA",
                 "excludeLocationIds":["A-01-01"]}""")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.allocations[*].locationId", contains("A-01-02")));
    }

    @Test
    void releaseKeepsPickedStockUntilItIsReturnedToStock_OUTEX02() throws Exception {
        receive("SKU-EA", "10", "EA", "A-01-01", null, null).andExpect(status().isCreated());
        String picked = JsonPath.read(body(allocate("SO-8", "000010", "SKU-EA", "4", null)), "$.allocations[0].id");
        String open = JsonPath.read(body(allocate("SO-8", "000020", "SKU-EA", "3", null)), "$.allocations[0].id");
        pick(picked, "4", "PK-SO-8", "").andExpect(status().isCreated());

        post("/allocations/release", """
                {"orderRef":"SO-8"}""")
                .andExpect(jsonPath("$.releasedAllocations", is(1)))
                .andExpect(jsonPath("$.releasedQty", is(3)));
        getJson("/balances?locationId=A-01-01").andExpect(jsonPath("$.items[0].availableQty", is(6)));

        postAs("operator1", "/allocations/" + open + "/return", "ret-0", "{}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code", is("INV_ALLOCATION_NOT_PICKED")));
        postAs("operator1", "/allocations/" + picked + "/return", "ret-1", "{}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lines[*].txnType", contains("RETURN_OUT", "RETURN_IN")));
        postAs("operator1", "/allocations/" + picked + "/return", "ret-1", "{}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.replayed", is(true)));

        getJson("/balances?locationId=STAGE-OUT").andExpect(jsonPath("$.items", hasSize(0)));
        getJson("/balances?locationId=A-01-01")
                .andExpect(jsonPath("$.items[0].qty", is(10)))
                .andExpect(jsonPath("$.items[0].availableQty", is(10)));
        getJson("/allocations?orderRef=SO-8").andExpect(jsonPath("$[*].status", contains("RETURNED", "RELEASED")));
    }

    private String body(ResultActions a) throws Exception {
        return a.andReturn().getResponse().getContentAsString();
    }
}
