package com.astrawms.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import com.jayway.jsonpath.JsonPath;
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
        /** Stock available outside excluded locations, for re-allocation after a short pick (default: none). */
        final Map<String, BigDecimal> elsewhere = new ConcurrentHashMap<>();
        final Map<String, List<String>> exclusionsByKey = new ConcurrentHashMap<>();
        /** What inventory reports as picked (to staging) per order, for cancellations after picking. */
        final Map<String, List<InventoryAllocation>> pickedByOrder = new ConcurrentHashMap<>();

        @Override
        public synchronized AllocateResult allocate(String siteId, String key, String orderRef, String orderLineRef,
                                                    String ownerId, String itemNo, BigDecimal qty, String uom, String lotNo,
                                                    List<String> excludeLocationIds) {
            exclusionsByKey.putIfAbsent(key, excludeLocationIds);
            if (!excludeLocationIds.isEmpty()) {
                return allocateByKey.computeIfAbsent(key, k -> {
                    BigDecimal free = elsewhere.getOrDefault(itemNo, BigDecimal.ZERO);
                    BigDecimal take = free.min(qty);
                    elsewhere.put(itemNo, free.subtract(take));
                    List<Allocation> allocations = take.signum() == 0 ? List.of()
                            : List.of(new Allocation(UUID.randomUUID(), "B-09", "", "L9", take));
                    allocations.forEach(a -> allocationLine.put(a.id(), new String[] {orderRef, orderLineRef, itemNo}));
                    return new AllocateResult("EA", qty, take, qty.subtract(take), allocations);
                });
            }
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

        @Override
        public List<InventoryAllocation> allocations(String siteId, String orderRef) {
            return pickedByOrder.getOrDefault(orderRef, List.of());
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

    // ------------------------------------------------------------------ ADR-0019 backorder recovery

    @Test
    void backorderedOrderIsRecoveredWhenStockOfItsItemsBecomesAvailable() throws Exception {
        inventory.stock.put("SKU-1", BigDecimal.ZERO);
        inventory.stock.put("SKU-SER", BigDecimal.ZERO);
        order(1, "CREATE", "3", "1");
        await(() -> "BACKORDERED".equals(orderStatus()));

        // Stock of SKU-1 is put away: the short line of that item is allocated, the order goes back to RELEASED.
        inventory.stock.put("SKU-1", new BigDecimal("24"));
        stockArrived("SKU-1", "MOVE_IN");
        await(() -> "RELEASED".equals(orderStatus()));
        await(() -> outbox(OutboundContracts.PickRequested.TYPE).size() == 2);
        assertThat(shortOf("000010")).isEqualByComparingTo("0");
        assertThat(shortOf("000020")).isEqualByComparingTo("1");

        inventory.stock.put("SKU-SER", new BigDecimal("5"));
        stockArrived("SKU-SER", "ADJUST_POS");
        await(() -> shortOf("000020").signum() == 0);
        assertThat(outbox(OutboundContracts.PickRequested.TYPE)).hasSize(3);
        // ADR-0021: which stock freed which line.
        call(get("/api/v1/sites/DC1/outbound/orders/" + doc), "")
                .andExpect(jsonPath("$.recoveries[0].erp_line_ref", is("000010")))
                .andExpect(jsonPath("$.recoveries[0].trigger", is("AUTOMATIC")))
                .andExpect(jsonPath("$.recoveries[0].txn_type", is("MOVE_IN")))
                .andExpect(jsonPath("$.recoveries[0].lpn_id", is("LPN-NEW")))
                .andExpect(jsonPath("$.recoveries[1].txn_type", is("ADJUST_POS")));
    }

    @Test
    void earliestGoodsIssueIsRecoveredFirst() throws Exception {
        inventory.stock.put("SKU-1", BigDecimal.ZERO);
        inventory.stock.put("SKU-SER", BigDecimal.ZERO);
        String late = doc;
        order(1, "CREATE", "3", "1");
        await(() -> "BACKORDERED".equals(orderStatus()));
        doc = "08" + (System.nanoTime() % 100_000_000L);
        String early = doc;
        orderAt(Instant.now().minusSeconds(3600), "3");                 // planned earlier
        await(() -> "BACKORDERED".equals(orderStatus()));

        inventory.stock.put("SKU-1", new BigDecimal("3"));               // enough for one order only
        stockArrived("SKU-1", "RETURN_IN");
        await(() -> "RELEASED".equals(statusOf(early)));
        assertThat(statusOf(late)).isEqualTo("BACKORDERED");
    }

    @Test
    void supervisorReallocatesShortsOnDemand() throws Exception {
        inventory.stock.put("SKU-1", BigDecimal.ZERO);
        inventory.stock.put("SKU-SER", BigDecimal.ZERO);
        order(1, "CREATE", "3", "1");
        await(() -> "BACKORDERED".equals(orderStatus()));
        inventory.stock.put("SKU-1", new BigDecimal("100"));
        inventory.stock.put("SKU-SER", new BigDecimal("100"));
        mvc.perform(post("/api/v1/sites/DC1/outbound/orders/" + doc + "/reallocate")
                        .with(TestTokens.as(tenant, "pete", Roles.PICKER)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/sites/DC1/outbound/orders/" + doc + "/reallocate")
                        .with(TestTokens.as(tenant, "sue", Roles.SUPERVISOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("RELEASED")))
                .andExpect(jsonPath("$.recoveredQty", is(4)));
        assertThat(outbox(OutboundContracts.PickRequested.TYPE)).hasSize(3);
        mvc.perform(get("/api/v1/sites/DC1/outbound/orders?q=customer one").with(TestTokens.as(tenant, "sue", Roles.SUPERVISOR)))
                .andExpect(jsonPath("$[0].erp_doc_no", is(doc)));
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
    void changesAfterReleaseAreRejected_IFOB002() throws Exception {
        order(1, "CREATE", "3", "1");
        await(() -> "RELEASED".equals(orderStatus()));
        order(2, "CHANGE", "5", "1");
        await(() -> acks().size() >= 2);
        assertThat(acks().getLast().get("reasonCode").asString()).isEqualTo("RELEASED_TO_PICK");
    }

    @Test
    void cancelAfterPickingReturnsStockAndAcknowledgesOnlyWhenItIsBack_OUTEX02() throws Exception {
        order(1, "CREATE", "3", "1");
        await(() -> "RELEASED".equals(orderStatus()));
        JsonNode pick = outbox(OutboundContracts.PickRequested.TYPE).getFirst().get("payload");
        UUID pickedAllocation = UUID.fromString(pick.get("allocationId").asString());
        completed(pickedAllocation, pick.get("orderLineRef").asString(), pick.get("qty").decimalValue(), BigDecimal.ZERO);
        await(() -> picked().signum() > 0);
        inventory.pickedByOrder.put(doc, List.of(new InventoryClient.InventoryAllocation(pickedAllocation, "000010",
                "ACME", "SKU-1", "L1", "", "A-01", pick.get("qty").decimalValue(), "STAGE-OUT", "PK-" + doc, "PICKED")));

        order(2, "CANCEL", "3", "1");
        await(() -> "CANCEL_REQUESTED".equals(orderStatus()));
        assertThat(inventory.releases).contains(doc);
        assertThat(outbox(OutboundContracts.PickCancelled.TYPE)).hasSize(2);           // the two unpicked allocations
        List<JsonNode> returns = outbox(OutboundContracts.ReturnRequested.TYPE);
        assertThat(returns).hasSize(1);
        JsonNode r = returns.getFirst().get("payload");
        assertThat(r.get("fromLocation").asString()).isEqualTo("STAGE-OUT");
        assertThat(r.get("fromLpn").asString()).isEqualTo("PK-" + doc);
        assertThat(r.get("toLocation").asString()).isEqualTo("A-01");
        assertThat(acks()).hasSize(1);                                                 // cancel not yet acknowledged

        send(OutboundContracts.TOPIC_TASK_EVENTS, TaskCompleted.TYPE, "ASTRAWMS", new TaskCompleted(UUID.randomUUID(),
                "RETURN", pickedAllocation, doc, "000010", pick.get("qty").decimalValue(), BigDecimal.ZERO, "picker1",
                Instant.now()));
        await(() -> "CANCELLED".equals(orderStatus()));
        await(() -> acks().size() == 2);
        assertThat(acks().getLast().get("result").asString()).isEqualTo("ACCEPTED");
        assertThat(acks().getLast().get("sourceDocumentId").asString()).isEqualTo("IDOC-2");
    }

    @Test
    void shortPickIsReallocatedFromAnotherLocation_PCK003() throws Exception {
        inventory.elsewhere.put("SKU-1", new BigDecimal("1"));
        order(1, "CREATE", "4", "1");
        await(() -> "RELEASED".equals(orderStatus()));
        List<JsonNode> picks = outbox(OutboundContracts.PickRequested.TYPE).stream().map(e -> e.get("payload")).toList();
        JsonNode big = picks.stream().filter(p -> p.get("qty").decimalValue().compareTo(BigDecimal.ONE) > 0).findFirst()
                .orElseThrow();
        UUID shorted = UUID.fromString(big.get("allocationId").asString());
        for (JsonNode pick : picks) {
            UUID id = UUID.fromString(pick.get("allocationId").asString());
            BigDecimal qty = pick.get("qty").decimalValue();
            BigDecimal picked = id.equals(shorted) ? qty.subtract(BigDecimal.ONE) : qty;
            completed(id, pick.get("orderLineRef").asString(), picked, qty.subtract(picked));
        }
        await(() -> outbox(OutboundContracts.PickRequested.TYPE).size() == 4);
        assertThat(orderStatus()).isEqualTo("RELEASED");                              // the re-allocated pick is open
        JsonNode again = outbox(OutboundContracts.PickRequested.TYPE).getLast().get("payload");
        assertThat(again.get("fromLocation").asString()).isEqualTo("B-09");
        assertThat(again.get("qty").decimalValue()).isEqualByComparingTo("1");
        assertThat(inventory.exclusionsByKey.get("OUT-RA-" + shorted)).containsExactly("A-01");

        completed(UUID.fromString(again.get("allocationId").asString()), "000010", BigDecimal.ONE, BigDecimal.ZERO);
        await(() -> "PICKED".equals(orderStatus()));
        call(get("/api/v1/sites/DC1/outbound/orders/" + doc), "")
                .andExpect(jsonPath("$.lines[0].qty_picked", is(4)))
                .andExpect(jsonPath("$.lines[0].qty_short_pick", is(0)))
                .andExpect(jsonPath("$.lines[0].qty_allocated", is(4)));
    }

    // ------------------------------------------------------------------ ADR-0020 short-pick actions

    /** Picks everything; the first allocation of line 000010 comes up one short with the given action. */
    private void pickWithOneShort(String action) throws Exception {
        List<JsonNode> picks = outbox(OutboundContracts.PickRequested.TYPE).stream().map(e -> e.get("payload")).toList();
        JsonNode first = picks.stream().filter(p -> p.get("orderLineRef").asString().equals("000010")).findFirst().orElseThrow();
        for (JsonNode pick : picks) {
            UUID id = UUID.fromString(pick.get("allocationId").asString());
            BigDecimal qty = pick.get("qty").decimalValue();
            boolean isShort = pick == first;
            BigDecimal picked = isShort ? qty.subtract(BigDecimal.ONE) : qty;
            send(OutboundContracts.TOPIC_TASK_EVENTS, TaskCompleted.TYPE, "ASTRAWMS",
                    new TaskCompleted(UUID.randomUUID(), "PICK", id, doc, pick.get("orderLineRef").asString(), picked,
                            qty.subtract(picked), "picker1", Instant.now(), isShort ? "NOT_FOUND" : null,
                            isShort ? action : null));
        }
        await(() -> picked().compareTo(new BigDecimal("3")) == 0);
    }

    @Test
    void backorderShortKeepsTheOrderOpenUntilStockArrives() throws Exception {
        order(1, "CREATE", "3", "1");
        await(() -> "RELEASED".equals(orderStatus()));
        int before = outbox(OutboundContracts.PickRequested.TYPE).size();
        pickWithOneShort("BACKORDER");
        Thread.sleep(500);
        assertThat(orderStatus()).isEqualTo("RELEASED");                           // waiting for stock, not PICKED
        assertThat(outbox(OutboundContracts.PickRequested.TYPE)).hasSize(before);  // nothing reallocated now
        assertThat(shortOf("000010")).isEqualByComparingTo("1");

        stockArrived("SKU-1", "MOVE_IN");                                          // backorder recovery
        await(() -> outbox(OutboundContracts.PickRequested.TYPE).size() == before + 1);
        JsonNode again = outbox(OutboundContracts.PickRequested.TYPE).getLast().get("payload");
        completed(UUID.fromString(again.get("allocationId").asString()), "000010", BigDecimal.ONE, BigDecimal.ZERO);
        await(() -> "PICKED".equals(orderStatus()));
    }

    @Test
    void shipShortClosesTheShortAndRecoveryLeavesItAlone() throws Exception {
        order(1, "CREATE", "3", "1");
        await(() -> "RELEASED".equals(orderStatus()));
        int before = outbox(OutboundContracts.PickRequested.TYPE).size();
        pickWithOneShort("SHIP_SHORT");
        await(() -> "PICKED".equals(orderStatus()));
        call(get("/api/v1/sites/DC1/outbound/orders/" + doc), "")
                .andExpect(jsonPath("$.lines[0].qty_short_closed", is(1)))
                .andExpect(jsonPath("$.lines[0].qty_short_pick", is(1)))
                .andExpect(jsonPath("$.allocations[0].short_action", is("SHIP_SHORT")));
        stockArrived("SKU-1", "MOVE_IN");
        Thread.sleep(1500);
        assertThat(outbox(OutboundContracts.PickRequested.TYPE)).hasSize(before);
        assertThat(orderStatus()).isEqualTo("PICKED");
    }

    @Test
    void supervisorClosesBackorderedShortsSoTheOrderShipsShort() throws Exception {
        order(1, "CREATE", "3", "1");
        await(() -> "RELEASED".equals(orderStatus()));
        pickWithOneShort("BACKORDER");
        Thread.sleep(500);
        mvc.perform(post("/api/v1/sites/DC1/outbound/orders/" + doc + "/close-shorts")
                        .with(TestTokens.as(tenant, "pete", Roles.PICKER)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/sites/DC1/outbound/orders/" + doc + "/close-shorts")
                        .with(TestTokens.as(tenant, "sue", Roles.SUPERVISOR)))
                .andExpect(jsonPath("$.status", is("PICKED")))
                .andExpect(jsonPath("$.lines[0].short_hold", is(false)));
        mvc.perform(get("/api/v1/sites/DC1/outbound/orders?q=PK-" + doc).with(TestTokens.as(tenant, "sue", Roles.SUPERVISOR)))
                .andExpect(jsonPath("$[0].erp_doc_no", is(doc)));                  // search by LPN
    }

    @Test
    void waveModePoolsOrdersUntilAPlannedWaveIsReleased_ADV030() throws Exception {
        call(put("/api/v1/sites/DC1/outbound/config"), """
                {"releaseMode":"WAVE"}""").andExpect(jsonPath("$.releaseMode", is("WAVE")));
        order(1, "CREATE", "3", "1");
        await(() -> "POOLED".equals(orderStatus()));
        assertThat(outbox(OutboundContracts.PickRequested.TYPE)).isEmpty();
        order(2, "CHANGE", "5", "1");                                                  // OUT-002: accepted before release
        await(() -> acks().size() == 2);
        assertThat(acks().getLast().get("result").asString()).isEqualTo("ACCEPTED");

        call(post("/api/v1/sites/DC1/outbound/waves/plan"), """
                {"carrierScac":"DHLX"}""").andExpect(jsonPath("$.orderCount", is(0)));
        call(post("/api/v1/sites/DC1/outbound/waves/plan"), """
                {"carrierScac":"UPSN","maxOrders":10}""")
                .andExpect(jsonPath("$.orderCount", is(1)))
                .andExpect(jsonPath("$.lineCount", is(2)))
                .andExpect(jsonPath("$.orders[0].erpDocNo", is(doc)));
        call(get("/api/v1/sites/DC1/outbound/waves"), "").andExpect(jsonPath("$.length()", is(0)));   // plan is a preview

        String waveNo = JsonPath.read(call(post("/api/v1/sites/DC1/outbound/waves"), """
                {"carrierScac":"UPSN"}""").andExpect(status().isCreated())
                .andExpect(jsonPath("$.status", is("PLANNED")))
                .andReturn().getResponse().getContentAsString(), "$.wave_no");
        call(post("/api/v1/sites/DC1/outbound/waves"), "{}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code", is("OUT_WAVE_EMPTY")));

        call(post("/api/v1/sites/DC1/outbound/waves/" + waveNo + "/release"), "")
                .andExpect(jsonPath("$.status", is("RELEASED")))
                .andExpect(jsonPath("$.orders[0].status", is("RELEASED")));
        assertThat(outbox(OutboundContracts.PickRequested.TYPE)).hasSize(3);           // line 10: 5 = 4 + 1, line 20: 1
        call(post("/api/v1/sites/DC1/outbound/waves/" + waveNo + "/release"), "").andExpect(jsonPath("$.status", is("RELEASED")));
        assertThat(outbox(OutboundContracts.PickRequested.TYPE)).hasSize(3);
        call(get("/api/v1/sites/DC1/outbound/orders/" + doc), "")
                .andExpect(jsonPath("$.wave_no", is(waveNo)))
                .andExpect(jsonPath("$.lines[0].qty_allocated", is(5)));
    }

    // ------------------------------------------------------------------ ADR-0021 release policy

    @Test
    void releaseByCarrierCutoffTakesOnlyOrdersDueSoonAndRaisesTheirPickPriority() throws Exception {
        call(put("/api/v1/sites/DC1/outbound/config"), """
                {"releaseMode":"WAVE","timezone":"UTC"}""").andExpect(jsonPath("$.timezone", is("UTC")));
        String soon = java.time.LocalTime.now(java.time.ZoneOffset.UTC).plusMinutes(30)
                .truncatedTo(java.time.temporal.ChronoUnit.MINUTES).toString();
        call(put("/api/v1/sites/DC1/outbound/carrier-cutoffs/upsn"), "{\"cutoffTime\":\"" + soon + "\"}")
                .andExpect(jsonPath("$[0].carrier_scac", is("UPSN")))
                .andExpect(jsonPath("$[0].cutoff_time", is(soon)));
        call(put("/api/v1/sites/DC1/outbound/carrier-cutoffs/upsn"), "{\"cutoffTime\":\"25:00\"}")
                .andExpect(jsonPath("$.code", is("OUT_CUTOFF_INVALID")));
        String due = doc;
        orderFor("UPSN", Instant.now());
        await(() -> "POOLED".equals(statusOf(due)));
        doc = "08" + (System.nanoTime() % 100_000_000L);
        String later = doc;
        orderFor("DHLX", Instant.now().plusSeconds(2 * 86_400));
        await(() -> "POOLED".equals(statusOf(later)));

        call(post("/api/v1/sites/DC1/outbound/waves/plan"), "{\"cutoffWithinMinutes\":120}")
                .andExpect(jsonPath("$.orderCount", is(1)))
                .andExpect(jsonPath("$.orders[0].erpDocNo", is(due)));
        call(post("/api/v1/sites/DC1/outbound/waves/release-by-cutoff"), "{\"withinMinutes\":120}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status", is("RELEASED")))
                .andExpect(jsonPath("$.orders.length()", is(1)));
        assertThat(statusOf(due)).isEqualTo("RELEASED");
        assertThat(statusOf(later)).isEqualTo("POOLED");
        // Due within the hour: picks rise above other picks (60) but stay below replenishment (70).
        assertThat(outbox(OutboundContracts.PickRequested.TYPE))
                .allSatisfy(p -> assertThat(p.get("payload").get("priority").asInt()).isEqualTo(69));
    }

    @Test
    void heldWaveCannotBeReleasedUntilTheHoldIsLifted() throws Exception {
        call(put("/api/v1/sites/DC1/outbound/config"), """
                {"releaseMode":"WAVE"}""").andExpect(status().isOk());
        order(1, "CREATE", "3", "1");
        await(() -> "POOLED".equals(orderStatus()));
        String waveNo = JsonPath.read(call(post("/api/v1/sites/DC1/outbound/waves"), "{}")
                .andReturn().getResponse().getContentAsString(), "$.wave_no");
        call(post("/api/v1/sites/DC1/outbound/waves/" + waveNo + "/hold"), "{}")
                .andExpect(jsonPath("$.code", is("OUT_HOLD_REASON_REQUIRED")));
        call(post("/api/v1/sites/DC1/outbound/waves/" + waveNo + "/hold"), "{\"reason\":\"Carrier late\"}")
                .andExpect(jsonPath("$.status", is("HELD")))
                .andExpect(jsonPath("$.hold_reason", is("Carrier late")));
        call(post("/api/v1/sites/DC1/outbound/waves/" + waveNo + "/release"), "")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code", is("OUT_WAVE_HELD")));
        assertThat(outbox(OutboundContracts.PickRequested.TYPE)).isEmpty();
        call(post("/api/v1/sites/DC1/outbound/waves/" + waveNo + "/unhold"), "").andExpect(jsonPath("$.status", is("PLANNED")));
        call(post("/api/v1/sites/DC1/outbound/waves/" + waveNo + "/release"), "").andExpect(jsonPath("$.status", is("RELEASED")));
    }

    @Test
    void shipCompleteOrderHoldsNoStockUntilEveryLineCanBeFilled() throws Exception {
        call(put("/api/v1/sites/DC1/outbound/owner-policies/acme"), "{\"shipComplete\":true}")
                .andExpect(jsonPath("$.ship_complete", is(true)));
        inventory.stock.put("SKU-SER", BigDecimal.ZERO);
        order(1, "CREATE", "3", "1");
        await(() -> "BACKORDERED".equals(orderStatus()));
        assertThat(outbox(OutboundContracts.PickRequested.TYPE)).isEmpty();
        assertThat(inventory.releases).contains(doc);                    // line 10 was allocable: given back
        call(get("/api/v1/sites/DC1/outbound/orders/" + doc), "")
                .andExpect(jsonPath("$.ship_complete", is(true)))
                .andExpect(jsonPath("$.lines[0].qty_allocated", is(0)))
                .andExpect(jsonPath("$.lines[0].short_reason", is("SHIP_COMPLETE")))
                .andExpect(jsonPath("$.lines[1].qty_short", is(1)));
        call(put("/api/v1/sites/DC1/outbound/orders/" + doc + "/policy"), "{\"priority\":90}")
                .andExpect(jsonPath("$.priority", is(90)));

        inventory.stock.put("SKU-SER", new BigDecimal("5"));
        stockArrived("SKU-SER", "MOVE_IN");
        await(() -> "RELEASED".equals(orderStatus()));
        assertThat(outbox(OutboundContracts.PickRequested.TYPE)).hasSize(3);
        assertThat(shortOf("000010")).isEqualByComparingTo("0");
        assertThat(shortOf("000020")).isEqualByComparingTo("0");
        call(put("/api/v1/sites/DC1/outbound/orders/" + doc + "/policy"), "{\"shipComplete\":false}")
                .andExpect(jsonPath("$.code", is("OUT_ORDER_POLICY_LOCKED")));
        call(get("/api/v1/sites/DC1/outbound/orders/" + doc), "")
                .andExpect(jsonPath("$.recoveries.length()", is(2)));
    }

    // ------------------------------------------------------------------ ADR-0023 transfers started in the WMS

    @Test
    void transferToAnotherSiteShipsAndBecomesItsExpectedReceipt() throws Exception {
        call(post("/api/v1/sites/DC1/outbound/transfers"), """
                {"toSiteId":"dc1","lines":[{"ownerId":"ACME","itemNo":"SKU-1","qty":3,"uom":"EA"}]}""")
                .andExpect(jsonPath("$.code", is("OUT_TRANSFER_INVALID")));               // not to itself
        String body = call(post("/api/v1/sites/DC1/outbound/transfers"), """
                {"toSiteId":"st01","note":"Weekly store replenishment",
                 "lines":[{"ownerId":"ACME","itemNo":"SKU-1","qty":3,"uom":"EA"},{"ownerId":"ACME","itemNo":"SKU-SER","qty":1,"uom":"EA"}]}""")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.order_type", is("TRANSFER")))
                .andExpect(jsonPath("$.transfer_to_site", is("ST01")))
                .andExpect(jsonPath("$.status", is("RELEASED")))
                .andReturn().getResponse().getContentAsString();
        doc = JsonPath.read(body, "$.erp_doc_no");
        assertThat(doc).isEqualTo("TR-DC1-000001");
        for (JsonNode pick : outbox(OutboundContracts.PickRequested.TYPE).stream().map(e -> e.get("payload")).toList()) {
            completed(UUID.fromString(pick.get("allocationId").asString()), pick.get("orderLineRef").asString(),
                    pick.get("qty").decimalValue(), BigDecimal.ZERO);
        }
        await(() -> "PICKED".equals(orderStatus()));
        call(post("/api/v1/sites/DC1/outbound/orders/" + doc + "/ship"), "{}").andExpect(jsonPath("$.status", is("SHIPPED")));

        JsonNode confirmation = outbox(OutboundContracts.ShipmentConfirmation.TYPE).getLast().get("payload");
        assertThat(confirmation.get("transferToSiteId").asString()).isEqualTo("ST01");
        JsonNode expectation = outbox(IntegrationContracts.ReceiptExpectation.TYPE).getLast();
        assertThat(expectation.get("siteId").asString()).isEqualTo("ST01");             // the receiving site's ASN
        JsonNode asn = expectation.get("payload");
        assertThat(asn.get("erpDocNo").asString()).isEqualTo(doc);
        assertThat(asn.get("erpDocType").asString()).isEqualTo("WMS_TRANSFER");
        assertThat(asn.get("supplyingSiteId").asString()).isEqualTo("DC1");
        assertThat(asn.get("lines")).hasSize(3);                                        // SKU-1 in lots L1 and L2, SKU-SER
        assertThat(asn.get("lines").get(0).get("erpLineRef").asString()).isEqualTo("000010-1");
        assertThat(asn.get("lines").get(0).get("lotNo").asString()).isEqualTo("L1");
        call(get("/api/v1/sites/ST01/outbound/transfers?direction=IN"), "")
                .andExpect(jsonPath("$[0].erp_doc_no", is(doc))).andExpect(jsonPath("$[0].from_site", is("DC1")));
    }

    @Test
    void pooledOrderCancelsWithoutTouchingInventory() throws Exception {
        call(put("/api/v1/sites/DC1/outbound/config"), """
                {"releaseMode":"WAVE"}""").andExpect(status().isOk());
        order(1, "CREATE", "3", "1");
        await(() -> "POOLED".equals(orderStatus()));
        order(2, "CANCEL", "3", "1");
        await(() -> "CANCELLED".equals(orderStatus()));
        assertThat(inventory.releases).doesNotContain(doc);
        assertThat(acks().getLast().get("result").asString()).isEqualTo("ACCEPTED");
    }

    @Test
    void ordersOfOtherOwnersAreInvisibleAndNeverWaved_G5() throws Exception {
        call(put("/api/v1/sites/DC1/outbound/config"), """
                {"releaseMode":"WAVE"}""").andExpect(status().isOk());
        order(1, "CREATE", "3", "1");
        await(() -> "POOLED".equals(orderStatus()));
        var beta = TestTokens.bearer(TestTokens.token().tenant(tenant).user("beta-sup").roles(Roles.SUPERVISOR)
                .owners("BETA").sign());
        mvc.perform(get("/api/v1/sites/DC1/outbound/orders/" + doc).with(beta)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/sites/DC1/outbound/orders").with(beta)).andExpect(jsonPath("$.length()", is(0)));
        mvc.perform(post("/api/v1/sites/DC1/outbound/waves/plan").with(beta).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(jsonPath("$.orderCount", is(0)));
        call(post("/api/v1/sites/DC1/outbound/waves/plan"), "{}").andExpect(jsonPath("$.orderCount", is(1)));
    }

    @Test
    void packingWithSsccAndLabelsThenLoadCloseShips_SHP001_SHP002_SHP003() throws Exception {
        call(put("/api/v1/sites/DC1/outbound/owner-policies/ACME"), """
                {"packList":true,"labelTemplate":"retail"}""").andExpect(jsonPath("$.label_template", is("RETAIL")));
        order(1, "CREATE", "3", "1");
        await(() -> "RELEASED".equals(orderStatus()));
        for (JsonNode pick : outbox(OutboundContracts.PickRequested.TYPE).stream().map(e -> e.get("payload")).toList()) {
            completed(UUID.fromString(pick.get("allocationId").asString()), pick.get("orderLineRef").asString(),
                    pick.get("qty").decimalValue(), BigDecimal.ZERO);
        }
        await(() -> "PICKED".equals(orderStatus()));
        call(put("/api/v1/sites/DC1/outbound/config"), """
                {"packRequired":true}""").andExpect(jsonPath("$.packRequired", is(true)))
                .andExpect(jsonPath("$.releaseMode", is("WAVELESS")));
        call(post("/api/v1/sites/DC1/outbound/orders/" + doc + "/ship"), "{}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code", is("OUT_NOT_PACKED")));

        String box1 = JsonPath.read(call(post("/api/v1/sites/DC1/outbound/orders/" + doc + "/cartons"), "{}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.sscc");
        assertThat(box1).hasSize(18).startsWith("00614141");
        call(post("/api/v1/sites/DC1/outbound/cartons/" + box1 + "/items"), """
                {"erpLineRef":"000010","qty":3}""").andExpect(status().isOk());
        call(post("/api/v1/sites/DC1/outbound/cartons/" + box1 + "/items"), """
                {"erpLineRef":"000010","qty":1}""")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code", is("OUT_PACK_EXCEEDS_PICKED")));
        call(post("/api/v1/sites/DC1/outbound/cartons/" + box1 + "/close"), """
                {"weightKg":4.2}""")
                .andExpect(jsonPath("$.status", is("CLOSED")))
                .andExpect(jsonPath("$.carrier_scac", is("UPSN")));
        String box2 = JsonPath.read(call(post("/api/v1/sites/DC1/outbound/orders/" + doc + "/cartons"), "{}")
                .andReturn().getResponse().getContentAsString(), "$.sscc");
        call(post("/api/v1/sites/DC1/outbound/cartons/" + box2 + "/close"), "{}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code", is("OUT_CARTON_EMPTY")));
        call(post("/api/v1/sites/DC1/outbound/cartons/" + box2 + "/items"), """
                {"erpLineRef":"000020","qty":1}""").andExpect(status().isOk());

        String wrongLoad = JsonPath.read(call(post("/api/v1/sites/DC1/outbound/loads"), """
                {"carrierScac":"DHLX","door":"D1"}""").andReturn().getResponse().getContentAsString(), "$.load_no");
        String load = JsonPath.read(call(post("/api/v1/sites/DC1/outbound/loads"), """
                {"carrierScac":"UPSN","door":"D2","trailerNo":"TR-9"}""").andReturn().getResponse().getContentAsString(), "$.load_no");
        call(post("/api/v1/sites/DC1/outbound/loads/" + load + "/orders"), "{\"sscc\":\"" + box1 + "\"}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code", is("OUT_NOT_PACKED")));   // box2 open
        call(post("/api/v1/sites/DC1/outbound/cartons/" + box2 + "/close"), "{\"weightKg\":1.1}").andExpect(status().isOk());
        call(post("/api/v1/sites/DC1/outbound/loads/" + wrongLoad + "/orders"), "{\"erpDocNo\":\"" + doc + "\"}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code", is("OUT_CROSS_LOAD")));
        call(post("/api/v1/sites/DC1/outbound/loads/" + load + "/orders"), "{\"sscc\":\"(00)" + box1 + "\"}")
                .andExpect(jsonPath("$.orders[0].erp_doc_no", is(doc)))
                .andExpect(jsonPath("$.orders[0].cartons", is(2)));

        call(post("/api/v1/sites/DC1/outbound/loads/" + load + "/close"), """
                {"sealNo":"SEAL-77"}""")
                .andExpect(jsonPath("$.status", is("CLOSED")))
                .andExpect(jsonPath("$.bol_no", is("BOL-DC1-" + load)));
        assertThat(orderStatus()).isEqualTo("SHIPPED");
        JsonNode confirmation = outbox(OutboundContracts.ShipmentConfirmation.TYPE).getLast().get("payload");
        assertThat(confirmation.get("billOfLading").asString()).isEqualTo("BOL-DC1-" + load);
        assertThat(confirmation.get("trackingNo").asString()).startsWith("1Z");
        String label = JsonPath.read(call(get("/api/v1/sites/DC1/outbound/cartons/" + box1), "")
                .andReturn().getResponse().getContentAsString(), "$.label");
        assertThat(label).contains("^XA", box1, "Customer One");
        assertThat(label).contains("CONTENTS", "SKU-1 x 3");                      // the owner's retail content label
        call(get("/api/v1/sites/DC1/outbound/cartons/" + box1), "")
                .andExpect(jsonPath("$.owner_id", is("ACME")))
                .andExpect(jsonPath("$.pack_list", org.hamcrest.Matchers.containsString("PACK LIST")));
    }

    @Test
    void onlySupervisorsShip_G5() throws Exception {
        mvc.perform(post("/api/v1/sites/DC1/outbound/orders/0080009999/ship").with(TestTokens.as(tenant, "pete", Roles.PICKER))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"carrierScac\":\"UPSN\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("FORBIDDEN")));
        mvc.perform(post("/api/v1/sites/DC1/outbound/waves/W000001/release").with(TestTokens.as(tenant, "pete", Roles.PICKER)))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/sites/DC1/outbound/config").with(TestTokens.as(tenant, "sue", Roles.SUPERVISOR))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"releaseMode\":\"WAVE\"}"))
                .andExpect(status().isForbidden());
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

    private void orderFor(String scac, Instant plannedGi) throws Exception {
        OutboundOrder o = new OutboundOrder(doc, "CUSTOMER", "CREATE", 1, "IDOC-1",
                new OutboundOrder.ShipTo("C-3", "Customer Three", "Reno", "US"), scac, plannedGi,
                List.of(new OutboundOrder.Line("000010", "ACME", "SKU-1", new BigDecimal("3"), "EA", null)), Instant.now());
        send(OutboundContracts.TOPIC_OUTBOUND_ORDERS, OutboundOrder.TYPE, "SAP_S4_DEV_100", o);
    }

    private void orderAt(Instant plannedGi, String qty10) throws Exception {
        OutboundOrder o = new OutboundOrder(doc, "CUSTOMER", "CREATE", 1, "IDOC-1",
                new OutboundOrder.ShipTo("C-2", "Customer Two", "Austin", "US"), "UPSN", plannedGi,
                List.of(new OutboundOrder.Line("000010", "ACME", "SKU-1", new BigDecimal(qty10), "EA", null)), Instant.now());
        send(OutboundContracts.TOPIC_OUTBOUND_ORDERS, OutboundOrder.TYPE, "SAP_S4_DEV_100", o);
    }

    private void stockArrived(String item, String txnType) throws Exception {
        send(com.astrawms.common.contracts.InventoryContracts.TOPIC,
                com.astrawms.common.contracts.InventoryContracts.InventoryChanged.TYPE, "ASTRAWMS",
                new com.astrawms.common.contracts.InventoryContracts.InventoryChanged(UUID.randomUUID(), "W1", "MOVE", "ACME",
                        item, List.of(new com.astrawms.common.contracts.InventoryContracts.InventoryChanged.Line(txnType, "",
                        "LPN-NEW", "A-01-11", "AVAILABLE", new BigDecimal("24"), new BigDecimal("24"))), Instant.now()));
    }

    private BigDecimal shortOf(String line) {
        return asTenant(() -> jdbc.sql("""
                        select l.qty_short from outbound_line l join outbound_order o on o.id = l.order_id
                        where o.erp_doc_no = :d and l.erp_line_ref = :ref""")
                .param("d", doc).param("ref", line).query(BigDecimal.class).single());
    }

    private String statusOf(String erpDocNo) {
        return asTenant(() -> jdbc.sql("select status from outbound_order where erp_doc_no = :d").param("d", erpDocNo)
                .query(String.class).optional().orElse(null));
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
