package com.astrawms.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.astrawms.common.security.Roles;
import com.astrawms.common.contracts.IntegrationContracts;
import com.astrawms.common.contracts.IntegrationContracts.ErpPostingResult;
import com.astrawms.common.contracts.OutboundContracts;
import com.astrawms.common.contracts.OutboundContracts.OutboundOrder;
import com.astrawms.common.contracts.OutboundContracts.TaskCompleted;
import com.astrawms.common.messaging.EventEnvelope;
import com.astrawms.common.tenancy.TenantContext;
import com.astrawms.outbound.inventory.InventoryClient;
import com.astrawms.test.AstraContainers;
import com.astrawms.test.AstraMockMvc;
import com.astrawms.test.TestTokens;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
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

/** Outbound order lifecycle (scope §3–5, IF-OB-001/002/003, OUT-EX-01/02, SHP-005). */
@SpringBootTest(properties = {"astra.outbox.relay-enabled=false", "astra.kafka.retry.max-elapsed-ms=1000"})
@Import(OutboundIT.Stubs.class)
class OutboundIT {

    /** In-memory inventory: available stock per item, allocations per order, idempotent by key. */
    static class StubInventory implements InventoryClient {
        final Map<String, BigDecimal> stock = new ConcurrentHashMap<>();
        final Map<String, AllocateResult> allocateByKey = new ConcurrentHashMap<>();
        final Map<String, List<IssuedLine>> issueByKey = new ConcurrentHashMap<>();
        final List<String> releases = new CopyOnWriteArrayList<>();
        final Map<UUID, String[]> allocationLine = new ConcurrentHashMap<>();   // id -> order, line, item

        @Override
        public synchronized AllocateResult allocate(String siteId, String key, String orderRef, String orderLineRef,
                                                    String ownerId, String itemNo, BigDecimal qty, String uom, String lotNo) {
            return allocateByKey.computeIfAbsent(key, k -> {
                BigDecimal free = stock.getOrDefault(itemNo, BigDecimal.ZERO);
                BigDecimal take = free.min(qty);
                stock.put(itemNo, free.subtract(take));
                List<Allocation> allocations = new java.util.ArrayList<>();
                if (take.signum() > 0) {
                    // split into two allocations to exercise multi-allocation lines
                    BigDecimal first = take.compareTo(BigDecimal.ONE) > 0 ? take.subtract(BigDecimal.ONE) : take;
                    allocations.add(new Allocation(UUID.randomUUID(), "A-01", "", "L1", first));
                    if (take.compareTo(first) > 0) {
                        allocations.add(new Allocation(UUID.randomUUID(), "A-02", "", "L2", take.subtract(first)));
                    }
                }
                allocations.forEach(a -> allocationLine.put(a.id(), new String[] {orderRef, orderLineRef, itemNo}));
                return new AllocateResult("EA", qty, take, qty.subtract(take), allocations);
            });
        }

        @Override
        public synchronized List<IssuedLine> issue(String siteId, String key, String orderRef) {
            return issueByKey.computeIfAbsent(key, k -> List.of(
                    new IssuedLine("000010", "SKU-1", new BigDecimal("3"), "EA",
                            List.of(new LotQty("L1", new BigDecimal("2")), new LotQty("L2", BigDecimal.ONE)), List.of()),
                    new IssuedLine("000020", "SKU-SER", new BigDecimal("1"), "EA", List.of(), List.of("SN-1"))));
        }

        @Override
        public void release(String siteId, String key, String orderRef) {
            releases.add(orderRef);
        }
    }

    @TestConfiguration
    static class Stubs {
        @Bean
        @Primary
        StubInventory stubInventory() {
            return new StubInventory();
        }
    }

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry r) {
        AstraContainers.register(r);
    }

    @Autowired
    WebApplicationContext context;
    @Autowired
    StubInventory inventory;
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
    String doc;

    @BeforeEach
    void setUp() {
        mvc = AstraMockMvc.create(context);
        tenant = "t-" + UUID.randomUUID().toString().substring(0, 8);
        doc = "08" + (System.nanoTime() % 100_000_000L);
        inventory.stock.put("SKU-1", new BigDecimal("1000"));
        inventory.stock.put("SKU-SER", new BigDecimal("1000"));
    }

    @Test
    void orderIsAllocatedAndReleasedToPicking_IFOB001() throws Exception {
        order(1, "CREATE", "3", "1");
        await(() -> "RELEASED".equals(orderStatus()));
        List<JsonNode> picks = outbox(OutboundContracts.PickRequested.TYPE);
        assertThat(picks).hasSize(3);                                  // line 10: two allocations, line 20: one
        JsonNode p = picks.getFirst().get("payload");
        assertThat(p.get("toLocation").asString()).isEqualTo("STAGE-OUT");
        assertThat(p.get("toLpn").asString()).isEqualTo("PK-" + doc);
        assertThat(acks().getLast().get("result").asString()).isEqualTo("ACCEPTED");
    }

    @Test
    void nothingAllocableIsBackordered_OUTEX01() throws Exception {
        inventory.stock.put("SKU-1", BigDecimal.ZERO);
        inventory.stock.put("SKU-SER", BigDecimal.ZERO);
        order(1, "CREATE", "3", "1");
        await(() -> "BACKORDERED".equals(orderStatus()));
        assertThat(outbox(OutboundContracts.PickRequested.TYPE)).isEmpty();
    }

    @Test
    void picksThenShipSendsConfirmationAndErpResultConfirms_IFOB003_SHP005() throws Exception {
        order(1, "CREATE", "4", "1");                                  // line 10 requests 4, ships 3 (short pick)
        await(() -> "RELEASED".equals(orderStatus()));
        List<JsonNode> picks = outbox(OutboundContracts.PickRequested.TYPE).stream().map(e -> e.get("payload")).toList();
        for (JsonNode pick : picks) {
            BigDecimal qty = pick.get("qty").decimalValue();
            BigDecimal picked = pick.get("orderLineRef").asString().equals("000010") && qty.compareTo(BigDecimal.ONE) > 0
                    ? qty.subtract(BigDecimal.ONE) : qty;
            completed(UUID.fromString(pick.get("allocationId").asString()), pick.get("orderLineRef").asString(), picked,
                    qty.subtract(picked));
        }
        await(() -> "PICKED".equals(orderStatus()));

        call(post("/api/v1/sites/DC1/outbound/orders/" + doc + "/ship"), """
                {"carrierScac":"UPSN","trackingNo":"1Z999"}""")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status", is("SHIPPED")));
        call(post("/api/v1/sites/DC1/outbound/orders/" + doc + "/ship"), "{}").andExpect(jsonPath("$.status", is("SHIPPED")));

        List<JsonNode> confirmations = outbox(OutboundContracts.ShipmentConfirmation.TYPE);
        assertThat(confirmations).hasSize(1);                          // ship is idempotent
        JsonNode c = confirmations.getFirst().get("payload");
        String txn = c.get("wmsTxnId").asString();
        assertThat(c.get("trackingNo").asString()).isEqualTo("1Z999");
        assertThat(c.get("lines").get(0).get("qtyShipped").decimalValue()).isEqualByComparingTo("3");
        assertThat(c.get("lines").get(0).get("shortReason").asString()).isEqualTo("SHORT_PICK");
        assertThat(c.get("lines").get(0).get("lotSplits")).hasSize(2);
        assertThat(c.get("lines").get(1).get("serials").get(0).asString()).isEqualTo("SN-1");

        posting(new ErpPostingResult(txn, "ShipmentConfirmation", doc, false, null, null, false,
                "BUSINESS_CORRECTABLE", "M7 021", "Deficit of unrestricted stock", Instant.now()));
        await(() -> "SHIP_ERROR".equals(orderStatus()));
        call(post("/api/v1/sites/DC1/outbound/orders/" + doc + "/repost"), "").andExpect(jsonPath("$.status", is("SHIPPED")));
        assertThat(outbox(OutboundContracts.ShipmentConfirmation.TYPE).get(1).get("payload").get("wmsTxnId").asString())
                .isEqualTo(txn);

        posting(new ErpPostingResult(txn, "ShipmentConfirmation", doc, true, "4900000077", "2026", false, null, null,
                null, Instant.now()));
        await(() -> "CONFIRMED".equals(orderStatus()));
    }

    @Test
    void shipBeforeAllPicksIsRejected() throws Exception {
        order(1, "CREATE", "3", "1");
        await(() -> "RELEASED".equals(orderStatus()));
        call(post("/api/v1/sites/DC1/outbound/orders/" + doc + "/ship"), "{}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code", is("OUT_NOT_PICKED")));
    }

    @Test
    void cancelBeforePickingReleasesAndCancelsPicks_OUTEX02() throws Exception {
        order(1, "CREATE", "3", "1");
        await(() -> "RELEASED".equals(orderStatus()));
        order(2, "CANCEL", "3", "1");
        await(() -> "CANCELLED".equals(orderStatus()));
        assertThat(inventory.releases).contains(doc);
        assertThat(outbox(OutboundContracts.PickCancelled.TYPE)).hasSize(3);
    }

    @Test
    void changesAndLateCancellationsAreRejected_IFOB002() throws Exception {
        order(1, "CREATE", "3", "1");
        await(() -> "RELEASED".equals(orderStatus()));
        order(2, "CHANGE", "5", "1");
        await(() -> acks().size() >= 2);
        assertThat(acks().getLast().get("reasonCode").asString()).isEqualTo("RELEASED_TO_PICK");

        JsonNode pick = outbox(OutboundContracts.PickRequested.TYPE).getFirst().get("payload");
        completed(UUID.fromString(pick.get("allocationId").asString()), pick.get("orderLineRef").asString(),
                pick.get("qty").decimalValue(), BigDecimal.ZERO);
        await(() -> picked().signum() > 0);
        order(3, "CANCEL", "3", "1");
        await(() -> acks().size() >= 3);
        assertThat(acks().getLast().get("reasonCode").asString()).isEqualTo("PICK_STARTED");
        assertThat(orderStatus()).isEqualTo("RELEASED");
    }

    @Test
    void onlySupervisorsShip_G5() throws Exception {
        mvc.perform(post("/api/v1/sites/DC1/outbound/orders/0080009999/ship").with(TestTokens.as(tenant, "pete", Roles.PICKER))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"carrierScac\":\"UPSN\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("FORBIDDEN")));
    }


    // ------------------------------------------------------------------ helpers

    private void order(long revision, String action, String qty10, String qty20) throws Exception {
        OutboundOrder o = new OutboundOrder(doc, "CUSTOMER", action, revision, "IDOC-" + revision,
                new OutboundOrder.ShipTo("C-1", "Customer One", "Dallas", "US"), "UPSN", Instant.now(),
                List.of(new OutboundOrder.Line("000010", "ACME", "SKU-1", new BigDecimal(qty10), "EA", null),
                        new OutboundOrder.Line("000020", "ACME", "SKU-SER", new BigDecimal(qty20), "EA", null)),
                Instant.now());
        send(OutboundContracts.TOPIC_OUTBOUND_ORDERS, OutboundOrder.TYPE, "SAP_S4_DEV_100", o);
    }

    private void completed(UUID allocation, String line, BigDecimal picked, BigDecimal shortQty) throws Exception {
        send(OutboundContracts.TOPIC_TASK_EVENTS, TaskCompleted.TYPE, "ASTRAWMS",
                new TaskCompleted(UUID.randomUUID(), "PICK", allocation, doc, line, picked, shortQty, "picker1", Instant.now()));
    }

    private void posting(ErpPostingResult r) throws Exception {
        send(IntegrationContracts.TOPIC_ERP_POSTING_RESULTS, ErpPostingResult.TYPE, "SAP_S4_DEV_100", r);
    }

    private void send(String topic, String type, String source, Object payload) throws Exception {
        EventEnvelope e = new EventEnvelope(UUID.randomUUID(), type, "1.0", source, "ASTRAWMS", tenant, "DC1", "ACME",
                "DC1:" + doc, "test", 1, Instant.now(), json.valueToTree(payload));
        kafka.send(topic, tenant + ":DC1:" + doc, json.writeValueAsString(e)).get();
    }

    private String orderStatus() {
        return asTenant(() -> jdbc.sql("select status from outbound_order where erp_doc_no = :d").param("d", doc)
                .query(String.class).optional().orElse(null));
    }

    private BigDecimal picked() {
        return asTenant(() -> jdbc.sql("""
                        select coalesce(sum(l.qty_picked), 0) from outbound_line l
                        join outbound_order o on o.id = l.order_id where o.erp_doc_no = :d""")
                .param("d", doc).query(BigDecimal.class).single());
    }

    private List<JsonNode> acks() {
        return outbox(IntegrationContracts.ApplicationAck.TYPE).stream().map(e -> e.get("payload")).toList();
    }

    private List<JsonNode> outbox(String type) {
        return asTenant(() -> jdbc.sql("select envelope::text from outbox where tenant_id = :t and message_type = :type order by id")
                .param("t", tenant).param("type", type).query(String.class).list())
                .stream().map(json::readTree).toList();
    }

    private ResultActions call(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder r, String body)
            throws Exception {
        return mvc.perform(r.with(TestTokens.as(tenant, "shipper1", TestTokens.ALL_ROLES))
                .contentType(MediaType.APPLICATION_JSON).content(body));
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
