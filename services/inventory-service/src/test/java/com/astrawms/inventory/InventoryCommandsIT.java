package com.astrawms.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.astrawms.inventory.support.IntegrationTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Inventory command rules. Requirement IDs in test names refer to the RTM (docs/rtm). */
class InventoryCommandsIT extends IntegrationTest {

    @Nested
    class Receipts {

        @Test
        void receiptConvertsToBaseUomAndWritesLedgerAndEvent() throws Exception {
            receive("SKU-EA", "10", "CS", "A-01-01", "LPN-1", null)
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.wmsTxnId", matchesPattern("W[0-9A-HJKMNP-TV-Z]{15}")))
                    .andExpect(jsonPath("$.lines", hasSize(1)))
                    .andExpect(jsonPath("$.lines[0].txnType", is("RECEIPT")))
                    .andExpect(jsonPath("$.lines[0].qtyAfter", is(120)))
                    .andExpect(jsonPath("$.erpMovements", hasSize(0))); // receipts post to ERP via IF-IB-002

            assertThat(onHand("SKU-EA", "A-01-01", "AVAILABLE")).isEqualByComparingTo("120");
            assertThat(outboxTypes()).containsExactly("InventoryChanged");
            getJson("/lpns/LPN-1").andExpect(status().isOk())
                    .andExpect(jsonPath("$.locationId", is("A-01-01")))
                    .andExpect(jsonPath("$.contents[0].qty", is(120)));
        }

        @Test
        void lotRules() throws Exception {
            receive("SKU-LOT", "5", "EA", "A-01-01", null, null)
                    .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code", is("INV_LOT_REQUIRED")));
            receive("SKU-EA", "5", "EA", "A-01-01", null, "L1")
                    .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code", is("INV_LOT_NOT_ALLOWED")));
            post("/receipts", """
                    {"ownerId":"ACME","itemNo":"SKU-LOT","lotNo":"L1","qty":5,"uom":"EA","locationId":"A-01-01"}""")
                    .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code", is("INV_EXPIRY_REQUIRED")));
        }

        @Test
        void putawayCompatibilityIsAHardConstraint_PUT001_CCH001_CCH003() throws Exception {
            receive("SKU-FROZEN", "1", "EA", "A-01-01", null, null)
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code", is("INV_LOCATION_INCOMPATIBLE")));
            receive("SKU-FROZEN", "1", "EA", "F-01-01", null, null).andExpect(status().isCreated());
            receive("SKU-HAZ", "1", "EA", "A-01-01", null, null)
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code", is("INV_LOCATION_INCOMPATIBLE")));
            receive("SKU-HAZ", "1", "EA", "H-01-01", null, null).andExpect(status().isCreated());
        }

        @Test
        void singleLotLocationRejectsSecondLot() throws Exception {
            receive("SKU-LOT", "5", "EA", "A-02-01", null, "L1").andExpect(status().isCreated());
            receive("SKU-LOT", "5", "EA", "A-02-01", null, "L2")
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code", is("INV_LOCATION_MIXED_LOTS")));
        }

        @Test
        void unknownItemAndLocationAreRejected() throws Exception {
            receive("NOPE", "1", "EA", "A-01-01", null, null)
                    .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code", is("INV_ITEM_UNKNOWN")));
            receive("SKU-EA", "1", "EA", "Z-99", null, null)
                    .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code", is("INV_LOCATION_UNKNOWN")));
            receive("SKU-EA", "1", "PAL", "A-01-01", null, null)
                    .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code", is("INV_UOM_UNKNOWN")));
        }

        @Test
        void lpnCannotMixOwners_MWH002() throws Exception {
            receive("SKU-EA", "1", "EA", "A-01-01", "LPN-X", null).andExpect(status().isCreated());
            post("/receipts", """
                    {"ownerId":"OTHER","itemNo":"SKU-EA","qty":1,"uom":"EA","locationId":"A-01-01","lpnId":"LPN-X"}""")
                    .andExpect(status().isUnprocessableContent());
        }
    }

    @Nested
    class Idempotency {

        @Test
        void retryWithSameKeyReplaysWithoutSecondEffect_NFR123() throws Exception {
            String body = """
                    {"ownerId":"ACME","itemNo":"SKU-EA","qty":7,"uom":"EA","locationId":"A-01-01"}""";
            String first = postAs("op", "/receipts", "key-1", body).andExpect(status().isCreated())
                    .andExpect(jsonPath("$.replayed", is(false)))
                    .andReturn().getResponse().getContentAsString();
            String operationId = com.jayway.jsonpath.JsonPath.read(first, "$.operationId");

            postAs("op", "/receipts", "key-1", body).andExpect(status().isOk())
                    .andExpect(jsonPath("$.replayed", is(true)))
                    .andExpect(jsonPath("$.operationId", is(operationId)));
            assertThat(onHand("SKU-EA", "A-01-01", "AVAILABLE")).isEqualByComparingTo("7");

            postAs("op", "/receipts", "key-1", body.replace("7", "8"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code", is("IDEMPOTENCY_KEY_REUSED")));
        }

        @Test
        void missingIdempotencyKeyIsRejected() throws Exception {
            postAs("op", "/receipts", null, """
                    {"ownerId":"ACME","itemNo":"SKU-EA","qty":1,"uom":"EA","locationId":"A-01-01"}""")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code", is("HEADER_MISSING")));
        }
    }

    @Nested
    class Adjustments {

        @Test
        void stockNeverGoesNegative_INV001() throws Exception {
            receive("SKU-EA", "3", "EA", "A-01-01", null, null).andExpect(status().isCreated());
            post("/adjustments", """
                    {"ownerId":"ACME","itemNo":"SKU-EA","locationId":"A-01-01","qtyDelta":-5,"uom":"EA","reasonCode":"CC_TOL"}""")
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code", is("INV_INSUFFICIENT_STOCK")))
                    .andExpect(jsonPath("$.onHandQty", is(3)));
            assertThat(onHand("SKU-EA", "A-01-01", "AVAILABLE")).isEqualByComparingTo("3");
        }

        @Test
        void reasonCodesAndSegregationOfDuties_INV002_INV008() throws Exception {
            receive("SKU-EA", "10", "EA", "A-01-01", null, null).andExpect(status().isCreated());
            String adjust = """
                    {"ownerId":"ACME","itemNo":"SKU-EA","locationId":"A-01-01","qtyDelta":-2,"uom":"EA",
                     "reasonCode":"CC_VAR"%s}""";
            post("/adjustments", adjust.formatted(""))
                    .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code", is("INV_APPROVAL_REQUIRED")));
            postAs("alice", "/adjustments", "k-self", adjust.formatted(",\"approvedBy\":\"alice\""))
                    .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code", is("INV_SELF_APPROVAL")));
            post("/adjustments", adjust.formatted("").replace("CC_VAR", "NOPE"))
                    .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code", is("INV_REASON_UNKNOWN")));

            postAs("alice", "/adjustments", "k-ok", adjust.formatted(",\"approvedBy\":\"bob\""))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.erpMovements[0].movementType", is("ADJ_NEG")));
            assertThat(onHand("SKU-EA", "A-01-01", "AVAILABLE")).isEqualByComparingTo("8");

            String movement = outboxEnvelopes("GoodsMovement").getFirst();
            assertThat(movement).contains("\"movementType\":\"ADJ_NEG\"", "\"reasonCode\":\"CC_VAR\"",
                    "\"approvedBy\":\"bob\"", "\"fromBucket\":\"0001\"", "\"stockType\":\"UNRESTRICTED\"");
        }

        @Test
        void nonErpReasonProducesNoGoodsMovement() throws Exception {
            receive("SKU-EA", "10", "EA", "A-01-01", null, null).andExpect(status().isCreated());
            postAs("alice", "/adjustments", "k1", """
                    {"ownerId":"ACME","itemNo":"SKU-EA","locationId":"A-01-01","qtyDelta":1,"uom":"EA",
                     "reasonCode":"SYS_CORR","approvedBy":"bob"}""")
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.erpMovements", hasSize(0)));
            assertThat(outboxTypes()).doesNotContain("GoodsMovement");
        }
    }

    @Nested
    class StatusChanges {

        @Test
        void availableToQiIsErpRelevant() throws Exception {
            receive("SKU-EA", "10", "EA", "A-01-01", "LPN-Q", null).andExpect(status().isCreated());
            post("/status-changes", """
                    {"ownerId":"ACME","itemNo":"SKU-EA","locationId":"A-01-01","lpnId":"LPN-Q","fromStatus":"AVAILABLE",
                     "toStatus":"QI","qty":4,"uom":"EA","reasonCode":"QA_HOLD"}""")
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.lines", hasSize(2)))
                    .andExpect(jsonPath("$.erpMovements[0].movementType", is("STATUS_AVL_TO_QI")));
            assertThat(onHand("SKU-EA", "A-01-01", "AVAILABLE")).isEqualByComparingTo("6");
            assertThat(onHand("SKU-EA", "A-01-01", "QI")).isEqualByComparingTo("4");
        }

        @Test
        void damagedToBlockedNeedsNoErpPosting() throws Exception {
            receive("SKU-EA", "2", "EA", "A-01-01", null, null).andExpect(status().isCreated());
            String change = """
                    {"ownerId":"ACME","itemNo":"SKU-EA","locationId":"A-01-01","fromStatus":"%s","toStatus":"%s",
                     "qty":2,"uom":"EA","reasonCode":"DAMAGE"}""";
            post("/status-changes", change.formatted("AVAILABLE", "DAMAGED"))
                    .andExpect(jsonPath("$.erpMovements[0].movementType", is("STATUS_AVL_TO_BLK")));
            post("/status-changes", change.formatted("DAMAGED", "BLOCKED"))
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.erpMovements", hasSize(0)));
        }

        @Test
        void allocatedStockCannotChangeStatus_INV005() throws Exception {
            receive("SKU-EA", "5", "EA", "A-01-01", null, null).andExpect(status().isCreated());
            asTenant(() -> jdbc.sql("update inventory_balance set allocated_qty = 4 where item_no = 'SKU-EA'").update());
            post("/status-changes", """
                    {"ownerId":"ACME","itemNo":"SKU-EA","locationId":"A-01-01","fromStatus":"AVAILABLE","toStatus":"QI",
                     "qty":2,"uom":"EA","reasonCode":"QA_HOLD"}""")
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code", is("INV_STOCK_ALLOCATED")))
                    .andExpect(jsonPath("$.availableQty", is(1)));
        }
    }

    @Nested
    class Moves {

        @Test
        void wholeLpnMoveAcrossErpBucketsProducesBucketTransfer() throws Exception {
            receive("SKU-EA", "2", "CS", "A-01-01", "LPN-M", null).andExpect(status().isCreated());
            receive("SKU-LOT", "3", "EA", "A-01-01", "LPN-M", "L9").andExpect(status().isCreated());
            post("/moves", """
                    {"fromLocationId":"A-01-01","lpnId":"LPN-M","toLocationId":"Q-01-01"}""")
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.lines", hasSize(4)))
                    .andExpect(jsonPath("$.erpMovements", hasSize(2)))
                    .andExpect(jsonPath("$.erpMovements[0].movementType", is("BUCKET_TRANSFER")));
            getJson("/lpns/LPN-M").andExpect(jsonPath("$.locationId", is("Q-01-01")))
                    .andExpect(jsonPath("$.contents[0].locationId", is("Q-01-01")));
            assertThat(outboxEnvelopes("GoodsMovement")).hasSize(2)
                    .allSatisfy(e -> assertThat(e).contains("\"fromBucket\":\"0001\"", "\"toBucket\":\"0002\""));
            // one ERP transaction ID per movement (INT-014)
            assertThat(queryAsTenant(() -> jdbc.sql("select count(distinct wms_txn_id) from erp_movement")
                    .query(Integer.class).single())).isEqualTo(2);
        }

        @Test
        void quantityMoveWithinBucketIsNotErpRelevant() throws Exception {
            receive("SKU-EA", "10", "EA", "A-01-01", "LPN-S", null).andExpect(status().isCreated());
            post("/moves", """
                    {"fromLocationId":"A-01-01","lpnId":"LPN-S","ownerId":"ACME","itemNo":"SKU-EA","qty":4,"uom":"EA",
                     "toLocationId":"A-01-02","toLpnId":"LPN-T"}""")
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.erpMovements", hasSize(0)));
            assertThat(onHand("SKU-EA", "A-01-01", "AVAILABLE")).isEqualByComparingTo("6");
            assertThat(onHand("SKU-EA", "A-01-02", "AVAILABLE")).isEqualByComparingTo("4");
        }

        @Test
        void lpnMustBeAtTheStatedSourceLocation() throws Exception {
            receive("SKU-EA", "1", "EA", "A-01-01", "LPN-W", null).andExpect(status().isCreated());
            post("/moves", """
                    {"fromLocationId":"A-01-02","lpnId":"LPN-W","toLocationId":"Q-01-01"}""")
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code", is("INV_LPN_LOCATION_MISMATCH")));
        }
    }

    @Test
    void ledgerIsAppendOnly_INV006() throws Exception {
        receive("SKU-EA", "1", "EA", "A-01-01", null, null).andExpect(status().isCreated());
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        asTenant(() -> jdbc.sql("update inventory_txn set qty_delta = 99").update()))
                .hasStackTraceContaining("permission denied for table inventory_txn");
        getJson("/transactions?itemNo=SKU-EA").andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].userId", is("operator1")));
    }

    @Test
    void balancesAreCursorPaginated() throws Exception {
        for (String loc : new String[] {"A-01-01", "A-01-02", "Q-01-01"}) {
            receive("SKU-EA", "1", "EA", loc, null, null).andExpect(status().isCreated());
        }
        String page1 = getJson("/balances?itemNo=SKU-EA&limit=2").andExpect(jsonPath("$.items", hasSize(2)))
                .andReturn().getResponse().getContentAsString();
        Number cursor = com.jayway.jsonpath.JsonPath.read(page1, "$.nextCursor");
        getJson("/balances?itemNo=SKU-EA&limit=2&after=" + cursor)
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.nextCursor").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void itemSummaryTotalsByStatus() throws Exception {
        receive("SKU-EA", "5", "EA", "A-01-01", null, null).andExpect(status().isCreated());
        receive("SKU-EA", "2", "EA", "Q-01-01", null, null).andExpect(status().isCreated());
        getJson("/items/ACME/SKU-EA/summary")
                .andExpect(jsonPath("$.byStatus[0].status", is("AVAILABLE")))
                .andExpect(jsonPath("$.byStatus[0].qty", is(7)));
    }
}
