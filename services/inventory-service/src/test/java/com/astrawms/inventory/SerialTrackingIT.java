package com.astrawms.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.astrawms.inventory.support.IntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/** Serial number lifecycle in inventory (INB-006, SHP-007, §6.2). */
class SerialTrackingIT extends IntegrationTest {

    private ResultActions receiveSerials(String qty, String lpn, String location, String serialsJson) throws Exception {
        return post("/receipts", """
                {"ownerId":"ACME","itemNo":"SKU-SER","qty":%s,"uom":"EA","locationId":"%s","lpnId":"%s","serials":%s}"""
                .formatted(qty, location, lpn, serialsJson));
    }

    @Test
    void receiptRequiresOneDistinctNewSerialPerUnit_INB006() throws Exception {
        receive("SKU-SER", "2", "EA", "A-01-01", null, null)
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code", is("INV_SERIALS_REQUIRED")));
        receiveSerials("3", "LPN-S1", "A-01-01", "[\"SN-1\",\"SN-2\"]")
                .andExpect(jsonPath("$.code", is("INV_SERIAL_COUNT_MISMATCH")));
        receiveSerials("2", "LPN-S1", "A-01-01", "[\"SN-1\",\"SN-1\"]")
                .andExpect(jsonPath("$.code", is("INV_SERIAL_DUPLICATE")));

        receiveSerials("2", "LPN-S1", "A-01-01", "[\"SN-1\",\"SN-2\"]").andExpect(status().isCreated());
        receiveSerials("1", "LPN-S2", "A-01-02", "[\"SN-2\"]")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code", is("INV_SERIAL_DUPLICATE")))
                .andExpect(jsonPath("$.serials", contains("SN-2")));

        getJson("/serials/ACME/SKU-SER/SN-1")
                .andExpect(jsonPath("$.status", is("IN_STOCK")))
                .andExpect(jsonPath("$.lpnId", is("LPN-S1")))
                .andExpect(jsonPath("$.locationId", is("A-01-01")));
        getJson("/lpns/LPN-S1").andExpect(jsonPath("$.serials", containsInAnyOrder("SN-1", "SN-2")));
    }

    @Test
    void serialsOnlyForTrackedItems() throws Exception {
        post("/receipts", """
                {"ownerId":"ACME","itemNo":"SKU-EA","qty":1,"uom":"EA","locationId":"A-01-01","serials":["X"]}""")
                .andExpect(jsonPath("$.code", is("INV_SERIALS_NOT_ALLOWED")));
        // OUTBOUND-only serial control: captured at pack, not at receipt
        receive("SKU-OUTSER", "5", "EA", "A-01-01", null, null).andExpect(status().isCreated());
    }

    @Test
    void quantityMoveTransfersExactlyTheNamedSerials() throws Exception {
        receiveSerials("3", "LPN-M1", "A-01-01", "[\"SN-A\",\"SN-B\",\"SN-C\"]").andExpect(status().isCreated());
        String move = """
                {"fromLocationId":"A-01-01","lpnId":"LPN-M1","ownerId":"ACME","itemNo":"SKU-SER","qty":1,"uom":"EA",
                 "toLocationId":"A-01-02","toLpnId":"LPN-M2","serials":["%s"]}""";
        post("/moves", move.formatted("SN-Z"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code", is("INV_SERIAL_NOT_AT_SOURCE")));
        post("/moves", move.formatted("SN-B")).andExpect(status().isCreated());

        getJson("/serials/ACME/SKU-SER/SN-B").andExpect(jsonPath("$.lpnId", is("LPN-M2")))
                .andExpect(jsonPath("$.locationId", is("A-01-02")));
        getJson("/lpns/LPN-M1").andExpect(jsonPath("$.serials", containsInAnyOrder("SN-A", "SN-C")));
    }

    @Test
    void wholeLpnMoveCarriesSerialsIntoLedgerAndErpMovement() throws Exception {
        receiveSerials("2", "LPN-W1", "A-01-01", "[\"SN-W1\",\"SN-W2\"]").andExpect(status().isCreated());
        post("/moves", """
                {"fromLocationId":"A-01-01","lpnId":"LPN-W1","toLocationId":"Q-01-01"}""")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.erpMovements[0].movementType", is("BUCKET_TRANSFER")));
        getJson("/serials/ACME/SKU-SER/SN-W2").andExpect(jsonPath("$.locationId", is("Q-01-01")));

        String movement = outboxEnvelopes("GoodsMovement").getFirst();
        assertThat(movement).contains("\"serials\":[\"SN-W1\",\"SN-W2\"]");
        List<String> ledger = queryAsTenant(() -> jdbc.sql("""
                        select array_to_string(serials, ',') from inventory_txn
                        where txn_type = 'MOVE_IN' and lpn_id = 'LPN-W1'""")
                .query(String.class).list());
        assertThat(ledger).containsExactly("SN-W1,SN-W2");
    }

    @Test
    void negativeAdjustmentRemovesAndPositiveAdjustmentRestores() throws Exception {
        receiveSerials("2", "LPN-J1", "A-01-01", "[\"SN-J1\",\"SN-J2\"]").andExpect(status().isCreated());
        String adjust = """
                {"ownerId":"ACME","itemNo":"SKU-SER","locationId":"A-01-01","lpnId":"LPN-J1","qtyDelta":%s,"uom":"EA",
                 "reasonCode":"CC_TOL","serials":["SN-J2"]}""";
        post("/adjustments", adjust.formatted("-1"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.erpMovements[0].movementType", is("ADJ_NEG")));
        getJson("/serials/ACME/SKU-SER/SN-J2").andExpect(jsonPath("$.status", is("REMOVED")));
        assertThat(outboxEnvelopes("GoodsMovement").getFirst()).contains("\"serials\":[\"SN-J2\"]");

        post("/adjustments", adjust.formatted("1")).andExpect(status().isCreated());   // found again
        getJson("/serials/ACME/SKU-SER/SN-J2").andExpect(jsonPath("$.status", is("IN_STOCK")));
    }

    @Test
    void statusChangeMovesSerialsToTheNewStatus() throws Exception {
        receiveSerials("2", "LPN-Q1", "A-01-01", "[\"SN-Q1\",\"SN-Q2\"]").andExpect(status().isCreated());
        String change = """
                {"ownerId":"ACME","itemNo":"SKU-SER","locationId":"A-01-01","lpnId":"LPN-Q1","fromStatus":"%s",
                 "toStatus":"%s","qty":1,"uom":"EA","reasonCode":"QA_HOLD","serials":["SN-Q1"]}""";
        post("/status-changes", change.formatted("AVAILABLE", "QI")).andExpect(status().isCreated());
        getJson("/serials/ACME/SKU-SER/SN-Q1").andExpect(jsonPath("$.stockStatus", is("QI")));
        // SN-Q1 is no longer in AVAILABLE stock
        post("/status-changes", change.formatted("AVAILABLE", "BLOCKED"))
                .andExpect(jsonPath("$.code", is("INV_SERIAL_NOT_AT_SOURCE")));
    }
}
