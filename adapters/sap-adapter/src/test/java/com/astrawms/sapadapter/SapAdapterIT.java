package com.astrawms.sapadapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.astrawms.common.contracts.IntegrationContracts;
import com.astrawms.common.contracts.IntegrationContracts.ApplicationAck;
import com.astrawms.common.contracts.IntegrationContracts.GoodsMovement;
import com.astrawms.common.contracts.IntegrationContracts.ReceiptConfirmation;
import com.astrawms.common.messaging.EventEnvelope;
import com.astrawms.common.tenancy.TenantContext;
import com.astrawms.common.tenancy.TenantFilter;
import com.astrawms.test.AstraContainers;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = "astra.outbox.relay-enabled=false")
class SapAdapterIT {

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry r) {
        AstraContainers.register(r);
    }

    @Autowired
    WebApplicationContext context;
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

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(new TenantFilter()).build();
        tenant = "t-" + UUID.randomUUID().toString().substring(0, 8);
        call(put("/api/v1/sap/site-map/1000"), """
                {"siteId":"DC1","timeZone":"America/Chicago","defaultOwner":"ACME"}""").andExpect(status().isNoContent());
    }

    static final String DELVRY = """
            {"DOCNUM":"%s","MESTYP":"SHP_IBDLV_SAVE_REPLICA",
             "E1EDL20":{"VBELN":"0180000123","LFART":"EL","LIFEX":"ASN-77","WERKS":"%s"},
             "E1ADRM1":[{"PARTNER_Q":"LF","PARTNER_ID":"V-100"}],
             "E1EDT13":[{"QUALF":"007","NTANF":"20261002","NTANZ":"083000"}],
             "E1EDL24":[{"POSNR":"000010","MATNR":"SKU-1","LFIMG":"24.000","VRKME":"ST","CHARG":"B1"}],
             "UNKNOWN_SEGMENT":{"X":"ignored"}}""";

    @Test
    void delvryIsMappedForwardedAndAcknowledged_IFIB001() throws Exception {
        call(post("/api/v1/sap/idocs/delvry07"), DELVRY.formatted("0000000000000101", "1000"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status", is("03")));

        JsonNode envelope = outbox(IntegrationContracts.ReceiptExpectation.TYPE).getFirst();
        assertThat(envelope.get("sourceSystem").asString()).isEqualTo("SAP_S4_DEV_100");
        assertThat(envelope.get("siteId").asString()).isEqualTo("DC1");
        assertThat(envelope.get("businessKey").asString()).isEqualTo("DC1:0180000123");
        JsonNode payload = envelope.get("payload");
        assertThat(payload.get("action").asString()).isEqualTo("CREATE");
        assertThat(payload.get("lines").get(0).get("uom").asString()).isEqualTo("EA");
        assertThat(payload.get("expectedArrivalUtc").asString()).isEqualTo("2026-10-02T13:30:00Z");

        // AstraWMS rejects a later change → IDoc status 51 with the reason (ALEAUD equivalent)
        send(IntegrationContracts.TOPIC_APPLICATION_ACKS, ApplicationAck.TYPE, "DC1:0180000123", "ASTRAWMS",
                new ApplicationAck("SAP_S4_DEV_100", UUID.randomUUID().toString(), "0000000000000101", "0180000123",
                        ApplicationAck.REJECTED, "RECEIPT_IN_PROGRESS", "Receipt has started"));
        await(() -> "51".equals(idocStatus("0000000000000101")));
        call(get("/api/v1/sap/idocs/0000000000000101"), "")
                .andExpect(jsonPath("$.statusText", is("RECEIPT_IN_PROGRESS: Receipt has started")));
    }

    @Test
    void unmappedPlantIsRejectedWithStatus51() throws Exception {
        call(post("/api/v1/sap/idocs/delvry07"), DELVRY.formatted("0000000000000102", "9999"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code", is("PLANT_NOT_MAPPED")))
                .andExpect(jsonPath("$.idocStatus", is("51")));
        assertThat(idocStatus("0000000000000102")).isEqualTo("51");
        assertThat(outbox(IntegrationContracts.ReceiptExpectation.TYPE)).isEmpty();
    }

    @Test
    void receiptConfirmationPostsGoodsReceiptOnceEvenWhenResent_IFIB002_INT014() throws Exception {
        String txn = "W1M3S5G8745800A1";
        ReceiptConfirmation c = confirmation(txn, "0180000500");
        send(IntegrationContracts.TOPIC_RECEIPT_CONFIRMATIONS, ReceiptConfirmation.TYPE, "DC1:0180000500", "ASTRAWMS", c);
        JsonNode first = awaitResult(txn, 1).getFirst();
        assertThat(first.get("success").asBoolean()).isTrue();
        assertThat(first.get("duplicate").asBoolean()).isFalse();
        String document = first.get("erpDocument").asString();
        assertThat(document).startsWith("49");

        // A re-sent confirmation (new message, same wmsTxnId) must not post again.
        send(IntegrationContracts.TOPIC_RECEIPT_CONFIRMATIONS, ReceiptConfirmation.TYPE, "DC1:0180000500", "ASTRAWMS", c);
        JsonNode second = awaitResult(txn, 2).get(1);
        assertThat(second.get("duplicate").asBoolean()).isTrue();
        assertThat(second.get("erpDocument").asString()).isEqualTo(document);
        assertThat(documents(txn)).isEqualTo(1);
    }

    @Test
    void sapBusinessErrorsBecomeCorrectableFailures() throws Exception {
        call(post("/mock-sap/faults"), """
                {"faultKey":"0180000600","fault":"PERIOD_CLOSED"}""").andExpect(status().isNoContent());
        String txn = "W1M3S5G8745800A2";
        send(IntegrationContracts.TOPIC_RECEIPT_CONFIRMATIONS, ReceiptConfirmation.TYPE, "DC1:0180000600", "ASTRAWMS",
                confirmation(txn, "0180000600"));
        JsonNode result = awaitResult(txn, 1).getFirst();
        assertThat(result.get("success").asBoolean()).isFalse();
        assertThat(result.get("errorClass").asString()).isEqualTo("BUSINESS_CORRECTABLE");
        assertThat(result.get("erpMessageId").asString()).isEqualTo("M7 053");
        assertThat(documents(txn)).isZero();
    }

    @Test
    void transientLocksAreRetriedUntilPosted() throws Exception {
        call(post("/mock-sap/faults"), """
                {"faultKey":"0180000700","fault":"LOCKED","remaining":2}""").andExpect(status().isNoContent());
        String txn = "W1M3S5G8745800A3";
        send(IntegrationContracts.TOPIC_RECEIPT_CONFIRMATIONS, ReceiptConfirmation.TYPE, "DC1:0180000700", "ASTRAWMS",
                confirmation(txn, "0180000700"));
        JsonNode result = awaitResult(txn, 1).getFirst();
        assertThat(result.get("success").asBoolean()).isTrue();
        assertThat(documents(txn)).isEqualTo(1);
    }

    @Test
    void goodsMovementPostsWithCatalogueMovementType_IFINV001() throws Exception {
        String txn = "W1M3S5G8745800A4";
        send(IntegrationContracts.TOPIC_GOODS_MOVEMENTS, GoodsMovement.TYPE, "DC1:SKU-1", "ASTRAWMS",
                new GoodsMovement(txn, "STATUS_AVL_TO_QI", "QA_HOLD", Instant.now(), null,
                        List.of(new GoodsMovement.Item("SKU-1", new BigDecimal("4"), "EA", "0001", null, null, null,
                                "UNRESTRICTED", "test"))));
        assertThat(awaitResult(txn, 1).getFirst().get("success").asBoolean()).isTrue();
        String payload = queryAsTenant(() -> jdbc.sql("select payload::text from mock_sap_document where xblnr = :x")
                .param("x", txn).query(String.class).single());
        JsonNode call = json.readTree(payload);
        assertThat(call.get("GOODSMVT_CODE").asString()).isEqualTo("04");
        assertThat(call.get("GOODSMVT_ITEM").get(0).get("MOVE_TYPE").asString()).isEqualTo("322");
        assertThat(call.get("GOODSMVT_ITEM").get(0).get("PLANT").asString()).isEqualTo("1000");
    }

    // ------------------------------------------------------------------ helpers

    private ReceiptConfirmation confirmation(String txn, String vbeln) {
        return new ReceiptConfirmation(txn, vbeln, false, "V-100", Instant.now(), true,
                List.of(new ReceiptConfirmation.Line("000010", "SKU-1", new BigDecimal("24"), "EA",
                        List.of(new ReceiptConfirmation.LotSplit("B1", null, new BigDecimal("24"), null)), "AVAILABLE", null)),
                List.of());
    }

    private void send(String topic, String type, String key, String source, Object payload) throws Exception {
        EventEnvelope e = new EventEnvelope(UUID.randomUUID(), type, "1.0", source, "SAP", tenant, "DC1", "ACME", key,
                "test", 1, Instant.now(), json.valueToTree(payload));
        kafka.send(topic, tenant + ":" + key, json.writeValueAsString(e)).get();
    }

    private List<JsonNode> awaitResult(String txn, int count) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(60);
        while (Instant.now().isBefore(deadline)) {
            List<JsonNode> results = outbox(IntegrationContracts.ErpPostingResult.TYPE).stream()
                    .map(e -> e.get("payload")).filter(p -> txn.equals(p.get("wmsTxnId").asString())).toList();
            if (results.size() >= count) {
                return results;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("No ErpPostingResult for " + txn);
    }

    private void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(30);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Condition not met");
    }

    private String idocStatus(String docnum) {
        return queryAsTenant(() -> jdbc.sql("select status from idoc_status where idoc_number = :d")
                .param("d", docnum).query(String.class).optional().orElse(null));
    }

    private int documents(String xblnr) {
        return queryAsTenant(() -> jdbc.sql("select count(*) from mock_sap_document where xblnr = :x")
                .param("x", xblnr).query(Integer.class).single());
    }

    private List<JsonNode> outbox(String type) {
        return queryAsTenant(() -> jdbc.sql(
                        "select envelope::text from outbox where tenant_id = :t and message_type = :type order by id")
                .param("t", tenant).param("type", type).query(String.class).list())
                .stream().map(json::readTree).toList();
    }

    private <T> T queryAsTenant(Supplier<T> work) {
        return TenantContext.callAs(new TenantContext.Scope(tenant, "test", "TEST"), () -> tx.execute(s -> work.get()));
    }

    private ResultActions call(MockHttpServletRequestBuilder request, String body) throws Exception {
        return mvc.perform(request.header(TenantFilter.TENANT_HEADER, tenant).contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }
}
