package com.astrawms.inbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.astrawms.common.contracts.IntegrationContracts;
import com.astrawms.common.contracts.IntegrationContracts.ErpPostingResult;
import com.astrawms.common.contracts.ReturnsContracts;
import com.astrawms.common.contracts.ReturnsContracts.ReturnExpectation;
import com.astrawms.common.messaging.EventEnvelope;
import com.astrawms.common.security.Roles;
import com.astrawms.common.tenancy.TenantContext;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Customer returns (§8, IF-RET-001/002, RET-001/002, RET-EX-02/03). */
@SpringBootTest(properties = "astra.outbox.relay-enabled=false")
@Import(InboundIT.Stubs.class)
class ReturnsIT {

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
    String rma;

    @BeforeEach
    void setUp() throws Exception {
        mvc = AstraMockMvc.create(context);
        tenant = "t-" + UUID.randomUUID().toString().substring(0, 8);
        rma = "06" + (System.nanoTime() % 100_000_000L);
    }

    private void expect(String type, List<String> serials) throws Exception {
        ReturnExpectation e = new ReturnExpectation(rma, "CREATE", 1, "IDOC-R1", type, null,
                new ReturnExpectation.Customer("C-1", "Customer One"), Instant.now(), List.of(),
                List.of(new ReturnExpectation.Line("000010", "ACME", "SKU-1", new BigDecimal("3"), "EA", "DEFECTIVE", serials, true)),
                Instant.now());
        send(ReturnsContracts.TOPIC_RETURN_EXPECTATIONS, ReturnExpectation.TYPE, e);
        await(() -> rmaStatus() != null);
    }

    private ResultActions unit(String key, String body, RequestPostProcessor auth) throws Exception {
        return mvc.perform(post("/api/v1/sites/DC1/returns/" + rma + "/units").with(auth).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private RequestPostProcessor receiver() {
        return TestTokens.as(tenant, "rita", Roles.RECEIVER);
    }

    @Test
    void rmaUnitsAreGradedDispositionedAndConfirmedInTwoSteps_IFRET002() throws Exception {
        expect("CUSTOMER", List.of());
        assertThat(acks().getLast().get("result").asString()).isEqualTo("ACCEPTED");

        unit("u-1", """
                {"erpLineRef":"000010","itemNo":"SKU-1","qty":2,"uom":"EA","conditionGrade":"A","locationId":"RET-01"}""", receiver())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.disposition", is("RESTOCK")))
                .andExpect(jsonPath("$.stock_status", is("AVAILABLE")));
        unit("u-1", """
                {"erpLineRef":"000010","itemNo":"SKU-1","qty":2,"uom":"EA","conditionGrade":"A","locationId":"RET-01"}""", receiver())
                .andExpect(status().isCreated());                                           // replay, no second receipt
        unit("u-2", """
                {"itemNo":"SKU-1","qty":1,"uom":"EA","conditionGrade":"D","locationId":"RET-01"}""", receiver())
                .andExpect(jsonPath("$.disposition", is("RTV")))
                .andExpect(jsonPath("$.stock_status", is("BLOCKED")));
        assertThat(inventory.callsWithKeyPrefix("RET-u-")).hasSize(2);
        assertThat(inventory.callsWithKeyPrefix("RET-u-1").getFirst().command().status()).isEqualTo("AVAILABLE");
        // ADR-0019: every unit is on an LPN, so it gets a putaway task like a vendor receipt.
        assertThat(inventory.callsWithKeyPrefix("RET-u-1").getFirst().command().lpnId()).isEqualTo("R" + rma + "-1");
        assertThat(inventory.callsWithKeyPrefix("RET-u-2").getFirst().command().lpnId()).isEqualTo("R" + rma + "-2");
        assertThat(outbox("ReceiveRequested").getLast().get("payload").get("docNo").asString()).isEqualTo(rma);

        mvc.perform(post("/api/v1/sites/DC1/returns/" + rma + "/close").with(receiver()))
                .andExpect(jsonPath("$.status", is("CLOSED")));
        assertThat(outbox("ReceiveEnded").getLast().get("payload").get("kind").asString()).isEqualTo("RMA");
        JsonNode c = outbox(ReturnsContracts.ReturnConfirmation.TYPE).getLast().get("payload");
        assertThat(c.get("rmaNo").asString()).isEqualTo(rma);
        assertThat(c.get("receiptTxnId").asString()).isNotEqualTo(c.get("dispositionTxnId").asString());
        assertThat(c.get("lines")).hasSize(2);
        assertThat(c.get("lines").get(0).get("disposition").asString()).isEqualTo("RESTOCK");
        assertThat(c.get("lines").get(1).get("conditionGrade").asString()).isEqualTo("D");

        send(IntegrationContracts.TOPIC_ERP_POSTING_RESULTS, ErpPostingResult.TYPE, new ErpPostingResult(
                c.get("receiptTxnId").asString(), ReturnsContracts.ReturnConfirmation.TYPE, rma, true, "4900000501", "2026",
                false, null, null, null, Instant.now()));
        await(() -> "CONFIRMED".equals(rmaStatus()));
    }

    @Test
    void overRmaNeedsSupervisorOverride_RET002() throws Exception {
        expect("CUSTOMER", List.of());
        unit("o-1", """
                {"erpLineRef":"000010","itemNo":"SKU-1","qty":3,"uom":"EA","conditionGrade":"B","locationId":"RET-01"}""", receiver())
                .andExpect(status().isCreated());
        unit("o-2", """
                {"erpLineRef":"000010","itemNo":"SKU-1","qty":1,"uom":"EA","conditionGrade":"B","locationId":"RET-01","override":true}""", receiver())
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code", is("RET_QTY_OVER_RMA")));
        unit("o-3", """
                {"erpLineRef":"000010","itemNo":"SKU-1","qty":1,"uom":"EA","conditionGrade":"B","locationId":"RET-01","override":true}""",
                TestTokens.as(tenant, "sue", Roles.SUPERVISOR))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.over_rma", is(true)));
    }

    @Test
    void unexpectedSerialIsQuarantinedAndWrongItemFlagged_RET001_RETEX02_RETEX03() throws Exception {
        expect("CUSTOMER", List.of("SN-1", "SN-2"));
        unit("s-1", """
                {"erpLineRef":"000010","itemNo":"SKU-1","qty":1,"uom":"EA","serials":["SN-9"],"conditionGrade":"A",
                 "disposition":"RESTOCK","locationId":"RET-01"}""", receiver())
                .andExpect(jsonPath("$.serial_flag", is(true)))
                .andExpect(jsonPath("$.disposition", is("QUARANTINE")))
                .andExpect(jsonPath("$.stock_status", is("QI")));
        unit("w-1", """
                {"itemNo":"SKU-OTHER","ownerId":"ACME","qty":1,"uom":"EA","conditionGrade":"A","locationId":"RET-01"}""", receiver())
                .andExpect(jsonPath("$.wrong_item", is(true)))
                .andExpect(jsonPath("$.erp_line_ref").doesNotExist());
    }

    @Test
    void recallsAlwaysGoToQuarantine() throws Exception {
        expect("RECALL", List.of());
        unit("r-1", """
                {"itemNo":"SKU-1","qty":1,"uom":"EA","conditionGrade":"A","disposition":"RESTOCK","locationId":"RET-01"}""", receiver())
                .andExpect(jsonPath("$.disposition", is("QUARANTINE")));
    }

    @Test
    void blindReturnHasNoRmaInTheConfirmation() throws Exception {
        String body = mvc.perform(post("/api/v1/sites/DC1/returns").with(receiver()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerName\":\"Walk-in\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        rma = com.jayway.jsonpath.JsonPath.read(body, "$.rma_no");
        assertThat(rma).startsWith("BLIND-");
        unit("b-1", """
                {"itemNo":"SKU-1","qty":1,"uom":"EA","conditionGrade":"C","locationId":"RET-01"}""", receiver())
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code", is("RET_OWNER_REQUIRED")));
        unit("b-2", """
                {"itemNo":"SKU-1","ownerId":"ACME","qty":1,"uom":"EA","conditionGrade":"C","locationId":"RET-01"}""", receiver())
                .andExpect(jsonPath("$.disposition", is("REFURBISH")));
        mvc.perform(post("/api/v1/sites/DC1/returns/" + rma + "/close").with(receiver())).andExpect(status().isOk());
        assertThat(outbox(ReturnsContracts.ReturnConfirmation.TYPE).getLast().get("payload").get("rmaNo").isNull()).isTrue();
        mvc.perform(get("/api/v1/sites/DC1/returns/" + rma).with(receiver())).andExpect(jsonPath("$.status", is("CLOSED")));
    }

    // ------------------------------------------------------------------ helpers

    private String rmaStatus() {
        return asTenant(() -> jdbc.sql("select status from return_order where rma_no = :r").param("r", rma)
                .query(String.class).optional().orElse(null));
    }

    private List<JsonNode> acks() {
        return outbox(IntegrationContracts.ApplicationAck.TYPE).stream().map(e -> e.get("payload")).toList();
    }

    private void send(String topic, String type, Object payload) throws Exception {
        EventEnvelope e = new EventEnvelope(UUID.randomUUID(), type, "1.0", "SAP_S4_DEV_100", "ASTRAWMS", tenant, "DC1",
                "ACME", "DC1:" + rma, "test", 1, Instant.now(), json.valueToTree(payload));
        kafka.send(topic, tenant + ":DC1:" + rma, json.writeValueAsString(e)).get();
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
