package com.astrawms.inbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.astrawms.common.security.Roles;
import com.astrawms.common.contracts.IntegrationContracts;
import com.astrawms.common.contracts.IntegrationContracts.ErpPostingResult;
import com.astrawms.common.contracts.IntegrationContracts.ReceiptExpectation;
import com.astrawms.common.messaging.EventEnvelope;
import com.astrawms.common.tenancy.TenantContext;
import com.astrawms.common.web.ApiException;
import com.astrawms.test.AstraContainers;
import com.astrawms.test.AstraMockMvc;
import com.astrawms.test.TestTokens;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = "astra.outbox.relay-enabled=false")
@Import(InboundIT.Stubs.class)
class InboundIT {

    @TestConfiguration
    static class Stubs {
        @Bean
        @Primary
        StubInventoryClient stubInventoryClient() {
            return new StubInventoryClient();
        }
    }

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry r) {
        AstraContainers.register(r);
    }

    @Autowired
    WebApplicationContext context;
    @Autowired
    StubInventoryClient inventory;
    @Autowired
    KafkaTemplate<String, String> kafka;
    @Autowired
    JsonMapper json;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    TransactionTemplate tx;

    MockMvc mvc;
    String tenant;
    static final String DOC = "0180000123";

    @BeforeEach
    void setUp() throws Exception {
        mvc = AstraMockMvc.create(context);
        tenant = "t-" + UUID.randomUUID().toString().substring(0, 8);
        expectation(1, "CREATE", "24", "10");
        await(() -> expectationStatus(DOC) != null);
    }

    // ------------------------------------------------------------------ receiving

    @Nested
    class Receiving {

        @Test
        void lineReceiptCallsInventoryWithChainedKeyAndTargetStockType_INB001() throws Exception {
            receive("000020", "rf-1", """
                    {"qty":4,"uom":"CS","lotNo":"L1","lpnId":"LPN-1","locationId":"DOCK-01"}""")
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.lines[1].qtyReceived", is(4)))
                    .andExpect(jsonPath("$.lines[1].qtyOpen", is(6)));
            StubInventoryClient.Call call = inventory.callsWithKeyPrefix("INB-rf-1").getFirst();
            assertThat(call.command().status()).isEqualTo("QI");          // INSMK X on the ASN line
            assertThat(call.command().sourceDoc()).isEqualTo(DOC + "/000020");
            assertThat(expectationStatus(DOC)).isEqualTo("IN_PROGRESS");
        }

        @Test
        void retryWithSameKeyReplaysWithoutSecondInventoryCall() throws Exception {
            String body = """
                    {"qty":10,"uom":"EA","lotNo":"B1","locationId":"DOCK-01"}""";
            receive("000010", "rf-2", body).andExpect(status().isCreated());
            receive("000010", "rf-2", body).andExpect(status().isOk()).andExpect(jsonPath("$.replayed", is(true)));
            assertThat(inventory.callsWithKeyPrefix("INB-rf-2")).hasSize(1);
            assertThat(lineReceived("000010")).isEqualByComparingTo("10");
            receive("000010", "rf-2", body.replace("10", "11"))
                    .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code", is("IDEMPOTENCY_KEY_REUSED")));
        }

        @Test
        void overToleranceNeedsApprovalFromAnotherUser_INB002() throws Exception {
            // line 000010: 24 EA expected, 5 % over-tolerance → max 25.2
            receive("000010", "rf-3", """
                    {"qty":26,"uom":"EA","lotNo":"B1","locationId":"DOCK-01"}""")
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code", is("INB_OVER_TOLERANCE")))
                    .andExpect(jsonPath("$.maxQty", is(25.2)));
            receive("000010", "rf-4", """
                    {"qty":26,"uom":"EA","lotNo":"B1","locationId":"DOCK-01","overrideReason":"BUYER_OK","approvedBy":"receiver1"}""")
                    .andExpect(jsonPath("$.code", is("INB_OVERRIDE_APPROVAL_REQUIRED")));
            receive("000010", "rf-5", """
                    {"qty":26,"uom":"EA","lotNo":"B1","locationId":"DOCK-01","overrideReason":"BUYER_OK","approvedBy":"supervisor1"}""")
                    .andExpect(status().isCreated());
        }

        @Test
        void inventoryErrorsPassThroughAndNothingIsRecorded() throws Exception {
            inventory.failNext(ApiException.unprocessable("INV_LOCATION_INCOMPATIBLE", "Frozen item into ambient dock"));
            String body = """
                    {"qty":2,"uom":"EA","lotNo":"B1","locationId":"DOCK-AMB"}""";
            receive("000010", "rf-6", body)
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code", is("INV_LOCATION_INCOMPATIBLE")));
            assertThat(lineReceived("000010")).isZero();
            // The failed attempt left no reservation: the same key can be retried after fixing the cause.
            receive("000010", "rf-6", body).andExpect(status().isCreated());
        }

        @Test
        void serialsFlowToInventoryAndIntoTheConfirmation_INB006_IFIB002() throws Exception {
            receive("000010", "sn-1", """
                    {"qty":2,"uom":"EA","lotNo":"B1","locationId":"DOCK-01","serials":["SN-1","SN-2"]}""")
                    .andExpect(status().isCreated());
            assertThat(inventory.callsWithKeyPrefix("INB-sn-1").getFirst().command().serials())
                    .containsExactly("SN-1", "SN-2");
            receive("000010", "sn-2", """
                    {"qty":22,"uom":"EA","lotNo":"B1","locationId":"DOCK-01"}""");
            receive("000020", "sn-3", """
                    {"qty":10,"uom":"CS","locationId":"DOCK-01"}""");
            close("{}").andExpect(jsonPath("$.status", is("CLOSED")));
            JsonNode lines = outbox(IntegrationContracts.ReceiptConfirmation.TYPE).getFirst().get("payload").get("lines");
            assertThat(lines.get(0).get("serials").toString()).isEqualTo("[\"SN-1\",\"SN-2\"]");
            assertThat(lines.get(1).get("serials").isNull()).isTrue();
        }

        @Test
        void lotAndUomMustMatchTheAsn() throws Exception {
            receive("000010", "rf-7", """
                    {"qty":1,"uom":"EA","lotNo":"OTHER","locationId":"DOCK-01"}""")
                    .andExpect(jsonPath("$.code", is("INB_LOT_MISMATCH")));
            receive("000010", "rf-8", """
                    {"qty":1,"uom":"CS","lotNo":"B1","locationId":"DOCK-01"}""")
                    .andExpect(jsonPath("$.code", is("INB_UOM_MISMATCH")));
        }

        @Test
        void ssccSingleScanReceivesWholePallet_INB011() throws Exception {
            call(post("/api/v1/sites/DC1/receipts/" + DOC + "/sscc/106141410000000019/receive").header("Idempotency-Key", "rf-9"),
                    """
                    {"locationId":"DOCK-01"}""")
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.lpnId", is("106141410000000019")))
                    .andExpect(jsonPath("$.lines[0].qtyReceived", is(24)));
            assertThat(inventory.callsWithKeyPrefix("INB-rf-9#").getFirst().command().lpnId())
                    .isEqualTo("106141410000000019");
            call(post("/api/v1/sites/DC1/receipts/" + DOC + "/sscc/106141410000000019/receive").header("Idempotency-Key", "rf-10"),
                    """
                    {"locationId":"DOCK-01"}""")
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("INB_SSCC_ALREADY_RECEIVED")));
            call(post("/api/v1/sites/DC1/receipts/" + DOC + "/sscc/999999999999999999/receive").header("Idempotency-Key", "rf-11"),
                    """
                    {"locationId":"DOCK-01"}""")
                    .andExpect(jsonPath("$.code", is("INB_SSCC_UNKNOWN")));
        }
    }

    // ------------------------------------------------------------------ close and ERP confirmation

    @Nested
    class CloseAndConfirm {

        @Test
        void closeRequiresShortReasonsAndPublishesConfirmation_INB003_IFIB002() throws Exception {
            receive("000010", "c-1", """
                    {"qty":20,"uom":"EA","lotNo":"B1","expiryDate":"2027-06-30","lpnId":"LPN-A","locationId":"DOCK-01"}""");
            receive("000010", "c-2", """
                    {"qty":4,"uom":"EA","lotNo":"B1","lpnId":"LPN-B","locationId":"DOCK-01"}""");
            receive("000020", "c-3", """
                    {"qty":6,"uom":"CS","lotNo":"L1","lpnId":"LPN-B","locationId":"DOCK-01"}""");
            receive("000020", "c-4", """
                    {"qty":3,"uom":"CS","lotNo":"L2","lpnId":"LPN-C","locationId":"DOCK-01"}""");

            close("{}").andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code", is("INB_SHORT_REASON_REQUIRED")))
                    .andExpect(jsonPath("$.lines", contains("000020")));
            close("""
                    {"shortReasons":{"000020":"SHORT_VENDOR"}}""")
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status", is("CLOSED")));

            JsonNode confirmation = outbox(IntegrationContracts.ReceiptConfirmation.TYPE).getFirst();
            assertThat(confirmation.get("targetSystem").asString()).isEqualTo("ERP");
            JsonNode payload = confirmation.get("payload");
            assertThat(payload.get("wmsTxnId").asString()).hasSize(16);
            assertThat(payload.get("final").asBoolean()).isTrue();
            JsonNode line1 = payload.get("lines").get(0);
            assertThat(line1.get("qtyReceived").decimalValue()).isEqualByComparingTo("24");
            assertThat(line1.get("lotSplits")).hasSize(1);
            assertThat(line1.get("lotSplits").get(0).get("expiryDate").asString()).isEqualTo("2027-06-30");
            JsonNode line2 = payload.get("lines").get(1);
            assertThat(line2.get("reasonCode").asString()).isEqualTo("SHORT_VENDOR");
            assertThat(line2.get("lotSplits")).hasSize(2);
            assertThat(line2.get("stockStatus").asString()).isEqualTo("QI");
            assertThat(payload.get("handlingUnits")).hasSize(3);

            // Closing again is idempotent: same transaction, no second confirmation.
            close("{}").andExpect(status().isOk());
            assertThat(outbox(IntegrationContracts.ReceiptConfirmation.TYPE)).hasSize(1);
        }

        @Test
        void erpResultsDriveTerminalStatusAndRepostKeepsTheTransactionId_INT011_INT014() throws Exception {
            receive("000010", "p-1", """
                    {"qty":24,"uom":"EA","lotNo":"B1","locationId":"DOCK-01"}""");
            receive("000020", "p-2", """
                    {"qty":10,"uom":"CS","locationId":"DOCK-01"}""");
            close("{}").andExpect(jsonPath("$.status", is("CLOSED")));
            String txn = outbox(IntegrationContracts.ReceiptConfirmation.TYPE).getFirst().get("payload").get("wmsTxnId").asString();

            postingResult(new ErpPostingResult(txn, "ReceiptConfirmation", DOC, false, null, null, false,
                    "BUSINESS_CORRECTABLE", "M7 053", "Posting only possible in periods 2026/10 and 2026/09", Instant.now()));
            await(() -> "POSTING_FAILED".equals(expectationStatus(DOC)));
            mvc.perform(get("/api/v1/sites/DC1/receipts/" + DOC).with(TestTokens.as(tenant, "receiver1", TestTokens.ALL_ROLES)))
                    .andExpect(jsonPath("$.header.erpErrorClass", is("BUSINESS_CORRECTABLE")));

            call(post("/api/v1/sites/DC1/receipts/" + DOC + "/repost"), "").andExpect(jsonPath("$.status", is("CLOSED")));
            List<JsonNode> confirmations = outbox(IntegrationContracts.ReceiptConfirmation.TYPE);
            assertThat(confirmations).hasSize(2);
            assertThat(confirmations.get(1).get("payload").get("wmsTxnId").asString()).isEqualTo(txn);

            postingResult(new ErpPostingResult(txn, "ReceiptConfirmation", DOC, true, "4900000042", "2026", false,
                    null, null, null, Instant.now()));
            await(() -> "CONFIRMED".equals(expectationStatus(DOC)));
            mvc.perform(get("/api/v1/sites/DC1/receipts/" + DOC).with(TestTokens.as(tenant, "receiver1", TestTokens.ALL_ROLES)))
                    .andExpect(jsonPath("$.header.erpDocument", is("4900000042")));
        }
    }

    // ------------------------------------------------------------------ ERP change handling (IF-IB-001 §6.1)

    @Nested
    class ChangeMatrix {

        @Test
        void inProgressDecreaseBelowReceivedIsRejectedAndAcknowledged_INBEX11() throws Exception {
            receive("000010", "m-1", """
                    {"qty":20,"uom":"EA","lotNo":"B1","locationId":"DOCK-01"}""");
            expectation(2, "CHANGE", "12", "10");
            await(() -> acks().size() >= 2);
            JsonNode ack = acks().getLast();
            assertThat(ack.get("result").asString()).isEqualTo("REJECTED");
            assertThat(ack.get("reasonCode").asString()).isEqualTo("QTY_BELOW_RECEIVED");
            assertThat(ack.get("sourceDocumentId").asString()).isEqualTo("IDOC-2");
            assertThat(lineExpected("000010")).isEqualByComparingTo("24");

            expectation(3, "CHANGE", "30", "10");               // increase is fine
            await(() -> lineExpected("000010").compareTo(new BigDecimal("30")) == 0);

            expectation(4, "DELETE", "30", "10");
            await(() -> acks().size() >= 4);
            assertThat(acks().getLast().get("reasonCode").asString()).isEqualTo("RECEIPT_IN_PROGRESS");
        }

        @Test
        void notStartedDeleteCancelsAndStaleRevisionsAreIgnored() throws Exception {
            expectation(5, "CHANGE", "50", "10");
            await(() -> lineExpected("000010").compareTo(new BigDecimal("50")) == 0);
            expectation(4, "CHANGE", "99", "10");               // older revision
            await(() -> acks().size() >= 3);
            assertThat(lineExpected("000010")).isEqualByComparingTo("50");

            expectation(6, "DELETE", "50", "10");
            await(() -> "CANCELLED".equals(expectationStatus(DOC)));
            receive("000010", "m-2", """
                    {"qty":1,"uom":"EA","lotNo":"B1","locationId":"DOCK-01"}""")
                    .andExpect(jsonPath("$.code", is("INB_EXPECTATION_CLOSED")));
        }
    }

    // ------------------------------------------------------------------ ADR-0019 RF receiving work

    @Test
    void deliveryBecomesAnRfReceiveTaskThatEndsWhenTheReceiptIsClosed() throws Exception {
        JsonNode request = outbox("ReceiveRequested").getLast().get("payload");
        assertThat(request.get("kind").asString()).isEqualTo("ASN");
        assertThat(request.get("docNo").asString()).isEqualTo(DOC);
        assertThat(request.get("partner").asString()).isEqualTo("V-100");
        assertThat(request.get("lines")).hasSize(2);
        assertThat(request.get("lines").get(0).get("itemNo").asString()).isEqualTo("SKU-1");

        // RF receives by item: the scan picks line 000010, a retry replays it.
        String scan = """
                {"itemNo":"SKU-1","qty":24,"uom":"EA","lotNo":"B1","lpnId":"LPN-RF","locationId":"DOCK-01"}""";
        call(post("/api/v1/sites/DC1/receipts/" + DOC + "/receive-item").header("Idempotency-Key", "RF-s1"), scan)
                .andExpect(status().isCreated()).andExpect(jsonPath("$.lines[0].qtyReceived", is(24)));
        call(post("/api/v1/sites/DC1/receipts/" + DOC + "/receive-item").header("Idempotency-Key", "RF-s1"), scan)
                .andExpect(status().isOk()).andExpect(jsonPath("$.replayed", is(true)));
        call(post("/api/v1/sites/DC1/receipts/" + DOC + "/receive-item").header("Idempotency-Key", "RF-s2"),
                scan.replace("SKU-1", "SKU-9"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code", is("INB_ITEM_NOT_ON_DELIVERY")));
        assertThat(inventory.callsWithKeyPrefix("INB-RF-s1")).hasSize(1);

        close("""
                {"shortReasons":{"000020":"SHORT_VENDOR"}}""").andExpect(status().isOk());
        JsonNode ended = outbox("ReceiveEnded").getLast().get("payload");
        assertThat(ended.get("docNo").asString()).isEqualTo(DOC);
        assertThat(ended.get("reason").asString()).isEqualTo("CLOSED");
        call(get("/api/v1/sites/DC1/receipts?q=sku-1"), "").andExpect(jsonPath("$[0].erpDocNo", is(DOC)));
        call(get("/api/v1/sites/DC1/receipts?q=nothing-like-this"), "").andExpect(jsonPath("$.length()", is(0)));
    }

    @Test
    void pickersCannotReceiveAndReceiversCannotRepost_G5() throws Exception {
        mvc.perform(post("/api/v1/sites/DC1/receipts/X/lines/000010/receive")
                        .with(TestTokens.as(tenant, "pete", Roles.PICKER)).header("Idempotency-Key", "r-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"qty\":1,\"uom\":\"EA\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/sites/DC1/receipts/X/repost").with(TestTokens.as(tenant, "rita", Roles.RECEIVER)))
                .andExpect(status().isForbidden());
    }


    @Test
    void receiptsOfOtherOwnersAreInvisible_G5() throws Exception {
        var beta = TestTokens.bearer(TestTokens.token().tenant(tenant).user("beta-clerk").roles(Roles.RECEIVER)
                .owners("BETA").sign());
        mvc.perform(get("/api/v1/sites/DC1/receipts/" + DOC).with(beta)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/sites/DC1/receipts").with(beta)).andExpect(jsonPath("$.length()", is(0)));
        mvc.perform(post("/api/v1/sites/DC1/receipts/" + DOC + "/close").with(beta)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
        var acme = TestTokens.bearer(TestTokens.token().tenant(tenant).user("acme-clerk").roles(Roles.RECEIVER)
                .owners("ACME").sites("DC1").sign());
        mvc.perform(get("/api/v1/sites/DC1/receipts/" + DOC).with(acme)).andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ ADR-0023 transfers started in the WMS

    @Test
    void receiptOfAWmsTransferNamesTheIssuingSite() throws Exception {
        String tr = "TR-DC9-000001";
        ReceiptExpectation e = new ReceiptExpectation(tr, "WMS_TRANSFER", "CREATE", 1, tr, null, null, "DC9", null,
                Instant.now(), null, null, null, null,
                List.of(new ReceiptExpectation.Line("000010", "ACME", "SKU-1", new BigDecimal("4"), "EA", "B1", null, null,
                        "AVAILABLE", null, null)), List.of(), Instant.now());
        EventEnvelope env = new EventEnvelope(UUID.randomUUID(), ReceiptExpectation.TYPE, "3.0", "ASTRAWMS", "ASTRAWMS",
                tenant, "DC1", "ACME", "DC1:" + tr, "test", 1, Instant.now(), json.valueToTree(e));
        kafka.send(IntegrationContracts.TOPIC_RECEIPT_EXPECTATIONS, tenant + ":DC1:" + tr, json.writeValueAsString(env)).get();
        await(() -> expectationStatus(tr) != null);
        call(post("/api/v1/sites/DC1/receipts/" + tr + "/lines/000010/receive").header("Idempotency-Key", "tr-1"), """
                {"qty":4,"uom":"EA","lotNo":"B1","locationId":"DOCK-01"}""").andExpect(status().isCreated());
        call(post("/api/v1/sites/DC1/receipts/" + tr + "/close"), "{}").andExpect(status().isOk());
        JsonNode confirmation = outbox(IntegrationContracts.ReceiptConfirmation.TYPE).getLast().get("payload");
        assertThat(confirmation.get("erpDocNo").asString()).isEqualTo(tr);
        assertThat(confirmation.get("transferFromSiteId").asString()).isEqualTo("DC9");
    }

    // ------------------------------------------------------------------ ADR-0021 RF-only floor work

    @Test
    void receiversReceiveOnRfOnlyTheDesktopIsForSupervisorsExceptions() throws Exception {
        var rita = TestTokens.as(tenant, "rita", Roles.RECEIVER);
        String body = """
                {"qty":2,"uom":"EA","lotNo":"B1","locationId":"DOCK-01"}""";
        mvc.perform(post("/api/v1/sites/DC1/receipts/" + DOC + "/lines/000010/receive").with(rita)
                        .header("Idempotency-Key", "desk-1").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code", is("RF_ONLY")));
        mvc.perform(post("/api/v1/sites/DC1/receipts/" + DOC + "/lines/000010/receive").with(rita).header("X-Channel", "RF")
                        .header("Idempotency-Key", "rf-1").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/sites/DC1/receipts/" + DOC + "/lines/000010/receive")
                        .with(TestTokens.as(tenant, "sue", Roles.SUPERVISOR))
                        .header("Idempotency-Key", "desk-2").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    // ------------------------------------------------------------------ ADR-0021 yard and dock appointments

    @Test
    void doorIsBookedBeforeTheAsnThenTheTrailerIsTrackedFromGateToCheckOut() throws Exception {
        Instant start = Instant.now().minusSeconds(3600);
        String body = """
                {"direction":"INBOUND","door":"d1","carrierScac":"upsn","docNo":"0180009999","start":"%s"}""".formatted(start);
        String appt = com.jayway.jsonpath.JsonPath.read(call(post("/api/v1/sites/DC1/yard/appointments"), body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status", is("SCHEDULED")))
                .andExpect(jsonPath("$.door", is("D1")))
                .andExpect(jsonPath("$.asn_known", is(false)))                    // booked before the ASN exists
                .andReturn().getResponse().getContentAsString(), "$.appt_no");
        call(post("/api/v1/sites/DC1/yard/appointments"), body.replace("0180009999", DOC))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("YRD_DOOR_BOOKED")));
        call(get("/api/v1/sites/DC1/yard/summary"), "")
                .andExpect(jsonPath("$.late[0].appt_no", is(appt)));               // an hour late
        // The ASN arrives under another delivery number: link it.
        call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/sites/DC1/yard/appointments/" + appt),
                "{\"docNo\":\"" + DOC + "\"}")
                .andExpect(jsonPath("$.asn_known", is(true)))
                .andExpect(jsonPath("$.receipt_status", is("NOT_STARTED")));
        call(post("/api/v1/sites/DC1/yard/appointments/" + appt + "/to-door"), "{}")
                .andExpect(jsonPath("$.code", is("YRD_WRONG_STATUS")));
        call(post("/api/v1/sites/DC1/yard/appointments/" + appt + "/check-in"), "{\"trailerNo\":\"tr-42\"}")
                .andExpect(jsonPath("$.status", is("CHECKED_IN"))).andExpect(jsonPath("$.trailer_no", is("TR-42")))
                .andExpect(jsonPath("$.dwell_minutes", is(0)));
        call(post("/api/v1/sites/DC1/yard/appointments/" + appt + "/to-door"), "{}")
                .andExpect(jsonPath("$.status", is("AT_DOOR")));
        call(get("/api/v1/sites/DC1/yard/appointments?docNo=" + DOC), "")
                .andExpect(jsonPath("$[0].appt_no", is(appt)));                    // shown next to the receipt
        call(get("/api/v1/sites/DC1/yard/summary"), "")
                .andExpect(jsonPath("$.inYard[0].trailer_no", is("TR-42")))
                .andExpect(jsonPath("$.doors[0].current.appt_no", is(appt)));
        call(post("/api/v1/sites/DC1/yard/appointments/" + appt + "/check-out"), "")
                .andExpect(jsonPath("$.status", is("CHECKED_OUT")));
        mvc.perform(post("/api/v1/sites/DC1/yard/appointments").with(TestTokens.as(tenant, "r1", Roles.RECEIVER))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ helpers

    private void expectation(long revision, String action, String qty10, String qty20) throws Exception {
        ReceiptExpectation e = new ReceiptExpectation(DOC, "VENDOR_ASN", action, revision, "IDOC-" + revision,
                "V-100", null, null, "UPSN", Instant.parse("2026-10-02T13:30:00Z"), "ASN-77", null, null, null,
                List.of(new ReceiptExpectation.Line("000010", "ACME", "SKU-1", new BigDecimal(qty10), "EA", "B1", null,
                                null, "AVAILABLE", new BigDecimal("5"), null),
                        new ReceiptExpectation.Line("000020", "ACME", "SKU-2", new BigDecimal(qty20), "CS", null, null,
                                null, "QI", null, null)),
                List.of(new ReceiptExpectation.HandlingUnit("106141410000000019", "PAL01",
                        List.of(new ReceiptExpectation.HuContent("000010", new BigDecimal("24"), "EA", "B1")))),
                Instant.now());
        send(IntegrationContracts.TOPIC_RECEIPT_EXPECTATIONS, ReceiptExpectation.TYPE, "SAP_S4_DEV_100", e);
    }

    private void postingResult(ErpPostingResult r) throws Exception {
        send(IntegrationContracts.TOPIC_ERP_POSTING_RESULTS, ErpPostingResult.TYPE, "SAP_S4_DEV_100", r);
    }

    private void send(String topic, String type, String source, Object payload) throws Exception {
        EventEnvelope e = new EventEnvelope(UUID.randomUUID(), type, "3.0", source, "ASTRAWMS", tenant, "DC1", "ACME",
                "DC1:" + DOC, "test", 1, Instant.now(), json.valueToTree(payload));
        kafka.send(topic, tenant + ":DC1:" + DOC, json.writeValueAsString(e)).get();
    }

    private ResultActions receive(String line, String key, String body) throws Exception {
        return call(post("/api/v1/sites/DC1/receipts/" + DOC + "/lines/" + line + "/receive").header("Idempotency-Key", key), body);
    }

    private ResultActions close(String body) throws Exception {
        return call(post("/api/v1/sites/DC1/receipts/" + DOC + "/close"), body);
    }

    private ResultActions call(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, String body)
            throws Exception {
        return mvc.perform(request.with(TestTokens.as(tenant, "receiver1", TestTokens.ALL_ROLES))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String expectationStatus(String doc) {
        return asTenant(() -> jdbc.sql("select status from receipt_expectation where erp_doc_no = :d")
                .param("d", doc).query(String.class).optional().orElse(null));
    }

    private BigDecimal lineReceived(String line) {
        return asTenant(() -> jdbc.sql("select qty_received from receipt_expectation_line where erp_line_ref = :l")
                .param("l", line).query(BigDecimal.class).single());
    }

    private BigDecimal lineExpected(String line) {
        return asTenant(() -> jdbc.sql("select qty_expected from receipt_expectation_line where erp_line_ref = :l")
                .param("l", line).query(BigDecimal.class).single());
    }

    private List<JsonNode> acks() {
        return outbox(IntegrationContracts.ApplicationAck.TYPE).stream().map(e -> e.get("payload")).toList();
    }

    private List<JsonNode> outbox(String type) {
        return asTenant(() -> jdbc.sql("select envelope::text from outbox where tenant_id = :t and message_type = :type order by id")
                .param("t", tenant).param("type", type).query(String.class).list())
                .stream().map(json::readTree).toList();
    }

    private <T> T asTenant(Supplier<T> work) {
        return TenantContext.callAs(new TenantContext.Scope(tenant, "test", "TEST"), () -> tx.execute(s -> work.get()));
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(30);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Condition not met within 30 s");
    }
}
