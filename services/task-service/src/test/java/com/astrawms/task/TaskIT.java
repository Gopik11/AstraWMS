package com.astrawms.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.astrawms.common.security.Roles;
import com.astrawms.common.contracts.InventoryContracts;
import com.astrawms.common.contracts.InventoryContracts.InventoryChanged;
import com.astrawms.common.contracts.MasterDataEvents;
import com.astrawms.common.contracts.OutboundContracts;
import com.astrawms.common.contracts.MasterDataEvents.ItemUpserted;
import com.astrawms.common.contracts.MasterDataEvents.LocationUpserted;
import com.astrawms.common.messaging.EventEnvelope;
import com.astrawms.common.tenancy.TenantContext;
import com.astrawms.task.inventory.InventoryClient;
import com.astrawms.test.AstraContainers;
import com.astrawms.test.AstraMockMvc;
import com.astrawms.test.TestTokens;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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
import tools.jackson.databind.json.JsonMapper;

/** Directed putaway and RF task execution (scope §2.2–2.4, PUT-001/002, PUT-EX-01/02/05). */
@SpringBootTest(properties = {"astra.outbox.relay-enabled=false", "astra.kafka.retry.max-elapsed-ms=1000"})
@Import(TaskIT.Stubs.class)
class TaskIT {

    /** Records LPN moves; idempotent by key like the real inventory service. */
    static class StubInventory implements InventoryClient {
        record Move(String key, String lpn, String from, String to) {
        }

        record Pick(String key, UUID allocation, BigDecimal qty, String to, String toLpn, List<String> serials,
                    boolean shortClose) {
        }

        final List<Move> moves = new CopyOnWriteArrayList<>();

        record QtyMove(String key, String item, String status, BigDecimal qty, String from, String to, String toLpn,
                       String fromLpn) {
        }

        final List<QtyMove> qtyMoves = new CopyOnWriteArrayList<>();

        @Override
        public UUID moveQuantity(String siteId, String key, String ownerId, String itemNo, String lotNo, String status,
                                 BigDecimal qty, String from, String fromLpn, String to, String toLpn) {
            if (qtyMoves.stream().noneMatch(m -> m.key().equals(key))) {
                qtyMoves.add(new QtyMove(key, itemNo, status, qty, from, to, toLpn, fromLpn));
            }
            return UUID.nameUUIDFromBytes(key.getBytes());
        }
        final List<Pick> picks = new CopyOnWriteArrayList<>();

        @Override
        public UUID pick(String siteId, String key, UUID allocationId, BigDecimal qty, String to, String toLpn,
                         List<String> serials, boolean shortClose) {
            if (picks.stream().noneMatch(p -> p.key().equals(key))) {
                picks.add(new Pick(key, allocationId, qty, to, toLpn, serials, shortClose));
            }
            return UUID.nameUUIDFromBytes(key.getBytes());
        }

        record Return(String key, UUID allocation, String to, String toLpn) {
        }

        record Count(String key, UUID countId, List<?> lines) {
        }

        final List<Count> counts = new CopyOnWriteArrayList<>();
        final List<String> replenishments = new CopyOnWriteArrayList<>();

        @Override
        public UUID confirmReplenishment(String siteId, String key, UUID replenishmentId) {
            if (!replenishments.contains(key + "|" + replenishmentId)) {
                replenishments.add(key + "|" + replenishmentId);
            }
            return UUID.nameUUIDFromBytes(key.getBytes());
        }

        @Override
        public String submitCount(String siteId, String key, UUID countId, List<?> lines) {
            if (counts.stream().noneMatch(c -> c.key().equals(key))) {
                counts.add(new Count(key, countId, lines));
            }
            return "RECOUNT";
        }

        final List<Return> returns = new CopyOnWriteArrayList<>();

        @Override
        public UUID returnToStock(String siteId, String key, UUID allocationId, String to, String toLpn) {
            if (returns.stream().noneMatch(r -> r.key().equals(key))) {
                returns.add(new Return(key, allocationId, to, toLpn));
            }
            return UUID.nameUUIDFromBytes(key.getBytes());
        }

        @Override
        public UUID moveLpn(String siteId, String key, String lpnId, String from, String to) {
            if (moves.stream().noneMatch(m -> m.key().equals(key))) {
                moves.add(new Move(key, lpnId, from, to));
            }
            return UUID.nameUUIDFromBytes(key.getBytes());
        }
    }

    /** Records what RF receiving sends to the inbound service. */
    static class StubInbound implements com.astrawms.task.inbound.InboundClient {
        record Call(String what, String doc, String key, java.util.Map<String, ?> body) {
        }

        final List<Call> calls = new CopyOnWriteArrayList<>();

        @Override
        public tools.jackson.databind.JsonNode receiveAsnItem(String siteId, String docNo, String key, java.util.Map<String, Object> scan) {
            calls.add(new Call("ASN", docNo, key, scan));
            return tools.jackson.databind.json.JsonMapper.builder().build().createObjectNode().put("erpDocNo", docNo);
        }

        @Override
        public tools.jackson.databind.JsonNode receiveReturnUnit(String siteId, String rmaNo, String key, java.util.Map<String, Object> unit) {
            calls.add(new Call("RMA", rmaNo, key, unit));
            return tools.jackson.databind.json.JsonMapper.builder().build().createObjectNode().put("disposition", "RESTOCK");
        }

        @Override
        public tools.jackson.databind.JsonNode closeAsn(String siteId, String docNo, java.util.Map<String, String> shortReasons) {
            calls.add(new Call("CLOSE_ASN", docNo, null, shortReasons == null ? java.util.Map.of() : shortReasons));
            return tools.jackson.databind.json.JsonMapper.builder().build().createObjectNode().put("status", "CLOSED");
        }

        @Override
        public tools.jackson.databind.JsonNode closeReturn(String siteId, String rmaNo) {
            calls.add(new Call("CLOSE_RMA", rmaNo, null, java.util.Map.of()));
            return tools.jackson.databind.json.JsonMapper.builder().build().createObjectNode().put("status", "CLOSED");
        }
    }

    @TestConfiguration
    static class Stubs {
        @Bean
        @Primary
        StubInventory stubInventory() {
            return new StubInventory();
        }

        @Bean
        @Primary
        StubInbound stubInbound() {
            return new StubInbound();
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
    StubInbound inbound;
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
        mvc = AstraMockMvc.create(context);
        tenant = "t-" + UUID.randomUUID().toString().substring(0, 8);
        item("SKU-1", null, false);
        item("SKU-FZ", "FROZEN", false);
        location("DOCK-1", "DOOR", null, false, "11", 0);
        location("A-02", "RACK", null, false, "22", 1);
        location("A-01", "RACK", null, false, "33", 2);
        location("F-01", "RACK", "FROZEN", false, "44", 0);
        await(() -> locations() == 4);
    }

    // ------------------------------------------------------------------ planning

    @Test
    void receiptAtDockCreatesTaskToNearestCompatibleEmptyLocation() throws Exception {
        received("LPN-1", "SKU-1");
        String id = awaitTask("LPN-1", "RELEASED");
        tasks(get("/api/v1/sites/DC1/tasks/" + id))
                .andExpect(jsonPath("$.targetLocation", is("A-02")))        // lowest pick sequence; F-01 is frozen
                .andExpect(jsonPath("$.strategy", is("EMPTY_NEAREST")))
                .andExpect(jsonPath("$.contents[0].itemNo", is("SKU-1")));
    }

    @Test
    void frozenGoodsGoToFrozenLocations_PUT001() throws Exception {
        received("LPN-F", "SKU-FZ");
        String id = awaitTask("LPN-F", "RELEASED");
        tasks(get("/api/v1/sites/DC1/tasks/" + id)).andExpect(jsonPath("$.targetLocation", is("F-01")));
    }

    @Test
    void consolidatesWithSameItemBeforeUsingEmptyLocations() throws Exception {
        location("B-01", "FLOOR", null, false, "55", 9);
        await(() -> locations() == 5);
        stockEvent(UUID.randomUUID(), "SKU-1", "RECEIPT", "LPN-OLD", "B-01", "4", "4");
        received("LPN-2", "SKU-1");
        String id = awaitTask("LPN-2", "RELEASED");
        tasks(get("/api/v1/sites/DC1/tasks/" + id))
                .andExpect(jsonPath("$.targetLocation", is("B-01")))
                .andExpect(jsonPath("$.strategy", is("CONSOLIDATE")));
    }

    @Test
    void reservationsKeepTwoPalletsOutOfTheSameSlot() throws Exception {
        received("LPN-A", "SKU-1");
        received("LPN-B", "SKU-1");
        String a = awaitTask("LPN-A", "RELEASED");
        String b = awaitTask("LPN-B", "RELEASED");
        String ta = JsonPath.read(body(get("/api/v1/sites/DC1/tasks/" + a)), "$.targetLocation");
        String tb = JsonPath.read(body(get("/api/v1/sites/DC1/tasks/" + b)), "$.targetLocation");
        assertThat(List.of(ta, tb)).containsExactlyInAnyOrder("A-02", "A-01");
    }

    @Test
    void noCompatibleLocationIsAnExceptionUntilReplanned_PUTEX01() throws Exception {
        received("LPN-1", "SKU-1");
        received("LPN-2", "SKU-1");
        received("LPN-3", "SKU-1");                                    // only two ambient slots exist
        String id = awaitTask("LPN-3", "EXCEPTION");
        tasks(get("/api/v1/sites/DC1/tasks/" + id)).andExpect(jsonPath("$.exceptionReason", is("NO_LOCATION")));

        location("A-03", "RACK", null, false, "66", 3);
        await(() -> locations() == 5);
        tasks(post("/api/v1/sites/DC1/tasks/" + id + "/replan"))
                .andExpect(jsonPath("$.status", is("RELEASED")))
                .andExpect(jsonPath("$.targetLocation", is("A-03")));
    }

    // ------------------------------------------------------------------ RF execution

    @Test
    void rfConfirmationValidatesLpnAndCheckDigitThenMovesStock_PUT002() throws Exception {
        received("LPN-1", "SKU-1");
        awaitTask("LPN-1", "RELEASED");
        String id = JsonPath.read(body(post("/api/v1/sites/DC1/tasks/next")), "$.id");
        tasks(post("/api/v1/sites/DC1/tasks/next")).andExpect(jsonPath("$.id", is(id)));   // resumes own task

        confirm(id, "LPN-9", "A-02", "22").andExpect(jsonPath("$.code", is("TSK_WRONG_LPN")));
        confirm(id, "LPN-1", "A-02", "99").andExpect(jsonPath("$.code", is("TSK_CHECK_DIGIT_MISMATCH")));
        confirm(id, "LPN-1", "A-02", "22").andExpect(status().isOk()).andExpect(jsonPath("$.status", is("COMPLETED")));
        confirm(id, "LPN-1", "A-02", "22").andExpect(jsonPath("$.status", is("COMPLETED")));   // idempotent

        assertThat(inventory.moves.stream().filter(m -> m.key().equals("TSK-" + id))).singleElement().satisfies(m -> {
            assertThat(m.key()).isEqualTo("TSK-" + id);
            assertThat(m.from()).isEqualTo("DOCK-1");
            assertThat(m.to()).isEqualTo("A-02");
        });
        tasks(post("/api/v1/sites/DC1/tasks/next")).andExpect(status().isNoContent());
    }

    @Test
    void overrideLocationMustPassTheSameConstraints() throws Exception {
        received("LPN-1", "SKU-1");
        awaitTask("LPN-1", "RELEASED");
        String id = JsonPath.read(body(post("/api/v1/sites/DC1/tasks/next")), "$.id");
        confirm(id, "LPN-1", "F-01", "44")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code", is("TSK_LOCATION_NOT_ALLOWED")));
        confirm(id, "LPN-1", "A-01", "33").andExpect(jsonPath("$.code", is("TSK_OVERRIDE_REASON_REQUIRED")));   // ADR-0021
        tasks(post("/api/v1/sites/DC1/tasks/" + id + "/confirm"), """
                {"lpnId":"LPN-1","locationId":"A-01","checkDigit":"33","overrideReason":"LOCATION_FULL"}""")
                .andExpect(jsonPath("$.strategy", is("OVERRIDE")))
                .andExpect(jsonPath("$.overrideReason", is("LOCATION_FULL")))
                .andExpect(jsonPath("$.confirmedLocation", is("A-01")))
                // ADR-0019: the task shows where the LPN went; the engine's suggestion is kept beside it.
                .andExpect(jsonPath("$.targetLocation", is("A-01")))
                .andExpect(jsonPath("$.suggestedLocation", is("A-02")));
        assertThat(inventory.moves.getLast().to()).isEqualTo("A-01");
        tasks(get("/api/v1/sites/DC1/tasks?q=LPN-1&type=PUTAWAY")).andExpect(jsonPath("$[0].targetLocation", is("A-01")));
    }

    // ------------------------------------------------------------------ ADR-0019 putaway rules

    @Test
    void putawayNeverTargetsOutboundStagingOrShipping() throws Exception {
        location("STAGE-OUT", "STAGING_OUT", null, false, "66", -2, "SHIPPING");
        location("SHIP-01", "RACK", null, false, "67", -1, "SHIPPING");
        await(() -> locations() == 6);
        received("LPN-S", "SKU-1");
        String id = awaitTask("LPN-S", "RELEASED");
        tasks(get("/api/v1/sites/DC1/tasks/" + id)).andExpect(jsonPath("$.targetLocation", is("A-02")));
        String next = JsonPath.read(body(post("/api/v1/sites/DC1/tasks/next")), "$.id");
        confirm(next, "LPN-S", "STAGE-OUT", "66").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code", is("TSK_LOCATION_NOT_ALLOWED")));
        confirm(next, "LPN-S", "DOCK-1", "11").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code", is("TSK_LOCATION_NOT_ALLOWED")));
    }

    @Test
    void availableStockGoesToItsPickFaceFirstThenReserve() throws Exception {
        location("P-00", "RACK", null, false, "70", -3, "PICK");      // a pick slot without a face for SKU-1: skipped
        location("P-01", "RACK", null, false, "71", 5, "PICK");
        await(() -> locations() == 6);
        send(InventoryContracts.TOPIC, InventoryContracts.PickFaceChanged.TYPE, "DC1:P-01",
                new InventoryContracts.PickFaceChanged("P-01", "ACME", "SKU-1", new BigDecimal("2"), new BigDecimal("20"),
                        true, Instant.now()));
        await(() -> asTenant(() -> jdbc.sql("select count(*) from ref_pick_face").query(Integer.class).single()) == 1);
        received("LPN-P1", "SKU-1");
        String first = awaitTask("LPN-P1", "RELEASED");
        tasks(get("/api/v1/sites/DC1/tasks/" + first))
                .andExpect(jsonPath("$.targetLocation", is("P-01"))).andExpect(jsonPath("$.strategy", is("PICK_FACE")));
        received("LPN-P2", "SKU-1");                                    // the face is reserved by the first pallet
        String second = awaitTask("LPN-P2", "RELEASED");
        tasks(get("/api/v1/sites/DC1/tasks/" + second))
                .andExpect(jsonPath("$.targetLocation", is("A-02"))).andExpect(jsonPath("$.strategy", is("EMPTY_NEAREST")));
    }

    @Test
    void stockThatIsNotAvailableGoesToQc() throws Exception {
        location("QC-01", "FLOOR", null, false, "80", 9, "QC");
        await(() -> locations() == 5);
        send(InventoryContracts.TOPIC, InventoryChanged.TYPE, "DC1:SKU-1", new InventoryChanged(UUID.randomUUID(), "W1",
                "TEST", "ACME", "SKU-1", List.of(new InventoryChanged.Line("RECEIPT", "", "LPN-Q", "DOCK-1", "QI",
                new BigDecimal("1"), new BigDecimal("1"))), Instant.now()));
        String id = awaitTask("LPN-Q", "RELEASED");
        tasks(get("/api/v1/sites/DC1/tasks/" + id))
                .andExpect(jsonPath("$.targetLocation", is("QC-01"))).andExpect(jsonPath("$.strategy", is("QC_EMPTY")));
        received("LPN-OK", "SKU-1");                                    // available stock never goes to QC
        String ok = awaitTask("LPN-OK", "RELEASED");
        tasks(get("/api/v1/sites/DC1/tasks/" + ok)).andExpect(jsonPath("$.targetLocation", is("A-02")));
    }

    @Test
    void stockArrivingAtAReturnsLocationGetsAPutawayTask() throws Exception {
        location("RET-01", "FLOOR", null, false, "90", 0, "RETURNS");
        await(() -> locations() == 5);
        stockEvent(UUID.randomUUID(), "SKU-1", "RECEIPT", "R9000001-1", "RET-01", "1", "1");
        String id = awaitTask("R9000001-1", "RELEASED");
        tasks(get("/api/v1/sites/DC1/tasks/" + id))
                .andExpect(jsonPath("$.fromLocation", is("RET-01"))).andExpect(jsonPath("$.targetLocation", is("A-02")));
    }

    // ------------------------------------------------------------------ ADR-0019 RF receiving and role routing

    @Test
    void receiveTaskGuidesTheReceiverAndPostsToInbound() throws Exception {
        send(OutboundContracts.TOPIC_TASK_REQUESTS, com.astrawms.common.contracts.ReceivingContracts.ReceiveRequested.TYPE,
                "DC1:1800001", new com.astrawms.common.contracts.ReceivingContracts.ReceiveRequested("ASN", "1800001",
                        "ACME", "V-100", Instant.now(), List.of(new com.astrawms.common.contracts.ReceivingContracts
                        .ReceiveRequested.Line("000010", "SKU-1", new BigDecimal("24"), "EA", null)), 40));
        await(() -> receiveTasks("1800001", "RELEASED") == 1);
        var rita = TestTokens.as(tenant, "rita", Roles.RECEIVER);
        mvc.perform(post("/api/v1/sites/DC1/tasks/next").with(TestTokens.as(tenant, "pete", Roles.PICKER)))
                .andExpect(status().isNoContent());                     // pickers do not receive
        String id = JsonPath.read(mvc.perform(post("/api/v1/sites/DC1/tasks/next").with(rita))
                .andExpect(jsonPath("$.taskType", is("RECEIVE")))
                .andExpect(jsonPath("$.docNo", is("1800001")))
                .andExpect(jsonPath("$.expectedLines[0].itemNo", is("SKU-1")))
                .andReturn().getResponse().getContentAsString(), "$.id");
        String scan = """
                {"scanId":"%s","docNo":"%s","itemNo":"SKU-1","qty":24,"uom":"EA","lpnId":"LPN1800001",
                 "locationId":"%s","checkDigit":"%s"}""";
        receive(rita, id, scan.formatted("s1", "1800002", "DOCK-1", "11"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code", is("TSK_WRONG_DOCUMENT")));
        receive(rita, id, scan.formatted("s1", "1800001", "A-02", "22"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code", is("TSK_LOCATION_NOT_ALLOWED")));
        receive(rita, id, scan.formatted("s1", "1800001", "DOCK-1", "99"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code", is("TSK_CHECK_DIGIT_MISMATCH")));
        assertThat(inbound.calls).isEmpty();
        receive(rita, id, scan.formatted("s1", "1800001", "DOCK-1", "11"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.task.scans", is(1)))
                .andExpect(jsonPath("$.task.status", is("ASSIGNED")));
        StubInbound.Call call = inbound.calls.getFirst();
        assertThat(call.what()).isEqualTo("ASN");
        assertThat(call.key()).isEqualTo("RF-s1");
        assertThat(call.body().get("itemNo")).isEqualTo("SKU-1");
        assertThat(call.body().get("lpnId")).isEqualTo("LPN1800001");
        assertThat(call.body().get("locationId")).isEqualTo("DOCK-1");

        mvc.perform(post("/api/v1/sites/DC1/tasks/" + id + "/receive/close").with(rita)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.task.status", is("COMPLETED")));
        assertThat(inbound.calls.getLast().what()).isEqualTo("CLOSE_ASN");

        // A document closed or cancelled on the desktop ends its open RF task.
        send(OutboundContracts.TOPIC_TASK_REQUESTS, com.astrawms.common.contracts.ReceivingContracts.ReceiveRequested.TYPE,
                "DC1:9000001", new com.astrawms.common.contracts.ReceivingContracts.ReceiveRequested("RMA", "9000001",
                        "ACME", "Customer One", null, List.of(), 40));
        await(() -> receiveTasks("9000001", "RELEASED") == 1);
        send(OutboundContracts.TOPIC_TASK_REQUESTS, com.astrawms.common.contracts.ReceivingContracts.ReceiveEnded.TYPE,
                "DC1:9000001", new com.astrawms.common.contracts.ReceivingContracts.ReceiveEnded("RMA", "9000001", "CLOSED"));
        await(() -> receiveTasks("9000001", "CANCELLED") == 1);
    }

    @Test
    void operatorsOnlyGetTheTaskTypesOfTheirRoles() throws Exception {
        received("LPN-R", "SKU-1");
        awaitTask("LPN-R", "RELEASED");
        mvc.perform(post("/api/v1/sites/DC1/tasks/next").with(TestTokens.as(tenant, "pete", Roles.PICKER)))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/sites/DC1/tasks/next").with(TestTokens.as(tenant, "rita", Roles.RECEIVER)))
                .andExpect(jsonPath("$.taskType", is("PUTAWAY")));
    }

    private ResultActions receive(org.springframework.test.web.servlet.request.RequestPostProcessor who, String id,
                                  String body) throws Exception {
        return mvc.perform(post("/api/v1/sites/DC1/tasks/" + id + "/receive").with(who)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private int receiveTasks(String doc, String status) {
        return asTenant(() -> jdbc.sql("select count(*) from task where task_type = 'RECEIVE' and doc_no = :d and status = :s")
                .param("d", doc).param("s", status).query(Integer.class).single());
    }

    @Test
    void blockedTargetIsExcludedAndTaskReplanned_PUTEX02() throws Exception {
        received("LPN-1", "SKU-1");
        awaitTask("LPN-1", "RELEASED");
        String id = JsonPath.read(body(post("/api/v1/sites/DC1/tasks/next")), "$.id");
        tasks(post("/api/v1/sites/DC1/tasks/" + id + "/exception"), """
                {"reason":"LOCATION_BLOCKED","detail":"pallet in the way"}""")
                .andExpect(jsonPath("$.status", is("RELEASED")))
                .andExpect(jsonPath("$.targetLocation", is("A-01")));
    }

    @Test
    void lpnMovedOutsideTheTaskCancelsIt() throws Exception {
        received("LPN-1", "SKU-1");
        String id = awaitTask("LPN-1", "RELEASED");
        stockEvent(UUID.randomUUID(), "SKU-1", "MOVE_OUT", "LPN-1", "DOCK-1", "-10", "0");
        await(() -> "CANCELLED".equals(taskStatus(id)));
    }

    // ------------------------------------------------------------------ PICK tasks

    @Test
    void pickRequestCreatesTaskAndRfPickCompletesIt() throws Exception {
        UUID allocation = UUID.randomUUID();
        pickRequested(allocation, "SO-1", "A-01", "6");
        String id = awaitPickTask(allocation, "RELEASED");
        String next = JsonPath.read(body(post("/api/v1/sites/DC1/tasks/next")), "$.id");
        assertThat(next).isEqualTo(id);
        tasks(get("/api/v1/sites/DC1/tasks/" + id))
                .andExpect(jsonPath("$.taskType", is("PICK")))
                .andExpect(jsonPath("$.itemNo", is("SKU-1")))
                .andExpect(jsonPath("$.qty", is(6)))
                .andExpect(jsonPath("$.targetLocation", is("STAGE-OUT")));

        pickConfirm(id, "22", "6").andExpect(jsonPath("$.code", is("TSK_CHECK_DIGIT_MISMATCH")));   // A-02's digit
        confirm(id, "LPN-X", "A-01", "33").andExpect(jsonPath("$.code", is("TSK_WRONG_TYPE")));
        // ADR-0020: a location-only pick is refused; the item must be scanned (number or GTIN).
        pickConfirm(id, "33", "6", null, "").andExpect(jsonPath("$.code", is("TSK_ITEM_SCAN_REQUIRED")));
        pickConfirm(id, "33", "6", "SKU-FZ", "").andExpect(jsonPath("$.code", is("TSK_WRONG_ITEM")));
        pickConfirm(id, "33", "6", "4012345678901", "").andExpect(jsonPath("$.code", is("TSK_WRONG_ITEM")));
        send(MasterDataEvents.TOPIC, MasterDataEvents.ITEM_UPSERTED, "ACME:SKU-1",
                new ItemUpserted("ACME", "SKU-1", "EA", "ACTIVE", null, null, false,
                        List.of(new ItemUpserted.Site("DC1", false, "NONE", "ACTIVE")),
                        List.of(new ItemUpserted.Uom("EA", 1, 1, "04012345678901")), Instant.now()));
        await(() -> asTenant(() -> jdbc.sql("select count(*) from ref_item_gtin").query(Integer.class).single()) == 1);
        pickConfirm(id, "33", "6", "4012345678901", "").andExpect(jsonPath("$.status", is("COMPLETED")))   // GTIN-13 = GTIN-14
                .andExpect(jsonPath("$.qtyPicked", is(6)));

        StubInventory.Pick p = inventory.picks.stream().filter(x -> x.key().equals("TSK-" + id)).findFirst().orElseThrow();
        assertThat(p.allocation()).isEqualTo(allocation);
        assertThat(p.to()).isEqualTo("STAGE-OUT");
        assertThat(p.toLpn()).isEqualTo("PK-SO-1");
        assertThat(p.shortClose()).isFalse();
        tools.jackson.databind.JsonNode done = taskCompleted(allocation);
        assertThat(done.get("qtyPicked").decimalValue()).isEqualByComparingTo("6");
        assertThat(done.get("qtyShort").decimalValue()).isEqualByComparingTo("0");
    }

    @Test
    void pickingLessIsAShortPick_PCK003() throws Exception {
        UUID allocation = UUID.randomUUID();
        pickRequested(allocation, "SO-2", "A-01", "6");
        awaitPickTask(allocation, "RELEASED");
        String id = JsonPath.read(body(post("/api/v1/sites/DC1/tasks/next")), "$.id");
        pickConfirm(id, "33", "4").andExpect(jsonPath("$.code", is("TSK_SHORT_REASON_REQUIRED")));   // ADR-0020
        pickConfirm(id, "33", "4", "SKU-1", ",\"shortReason\":\"NOT_FOUND\",\"shortAction\":\"WAIT\"")
                .andExpect(jsonPath("$.code", is("TSK_SHORT_ACTION_INVALID")));
        pickConfirm(id, "33", "4", "SKU-1", ",\"shortReason\":\"NOT_FOUND\",\"shortAction\":\"BACKORDER\"")
                .andExpect(jsonPath("$.exceptionReason", is("SHORT_PICK")));
        assertThat(inventory.picks.stream().filter(x -> x.key().equals("TSK-" + id)).findFirst().orElseThrow().shortClose())
                .isTrue();
        tools.jackson.databind.JsonNode done = taskCompleted(allocation);
        assertThat(done.get("qtyShort").decimalValue()).isEqualByComparingTo("2");
        assertThat(done.get("shortReason").asString()).isEqualTo("NOT_FOUND");
        assertThat(done.get("shortAction").asString()).isEqualTo("BACKORDER");
    }

    @Test
    void cancelledPickRequestsCancelTheTask() throws Exception {
        UUID allocation = UUID.randomUUID();
        pickRequested(allocation, "SO-3", "A-01", "1");
        awaitPickTask(allocation, "RELEASED");
        send(OutboundContracts.TOPIC_TASK_REQUESTS, OutboundContracts.PickCancelled.TYPE, "DC1:SO-3",
                new OutboundContracts.PickCancelled(allocation, "SO-3"));
        awaitPickTask(allocation, "CANCELLED");
    }

    @Test
    void returnRequestCreatesReturnTaskConfirmedAtTheTargetLocation_OUTEX02() throws Exception {
        UUID allocation = UUID.randomUUID();
        location("STAGE-OUT", "STAGING_OUT", null, false, "77", 99);
        send(OutboundContracts.TOPIC_TASK_REQUESTS, OutboundContracts.ReturnRequested.TYPE, "DC1:SO-9",
                new OutboundContracts.ReturnRequested(allocation, "SO-9", "000010", "ACME", "SKU-1", "",
                        new BigDecimal("4"), "EA", "STAGE-OUT", "PK-SO-9", "A-01", "LPN-ORIG", 70));
        String id = awaitPickTask(allocation, "RELEASED");
        assertThat((String) JsonPath.read(body(post("/api/v1/sites/DC1/tasks/next")), "$.id")).isEqualTo(id);
        tasks(get("/api/v1/sites/DC1/tasks/" + id))
                .andExpect(jsonPath("$.taskType", is("RETURN")))
                .andExpect(jsonPath("$.fromLocation", is("STAGE-OUT")))
                .andExpect(jsonPath("$.targetLocation", is("A-01")));

        pickConfirm(id, "33", "4").andExpect(jsonPath("$.code", is("TSK_WRONG_TYPE")));
        tasks(post("/api/v1/sites/DC1/tasks/" + id + "/return"), "{\"checkDigit\":\"77\"}")
                .andExpect(jsonPath("$.code", is("TSK_CHECK_DIGIT_MISMATCH")));
        tasks(post("/api/v1/sites/DC1/tasks/" + id + "/return"), "{\"checkDigit\":\"33\"}")
                .andExpect(jsonPath("$.status", is("COMPLETED")));

        StubInventory.Return r = inventory.returns.stream().filter(x -> x.key().equals("TSK-" + id)).findFirst().orElseThrow();
        assertThat(r.allocation()).isEqualTo(allocation);
        assertThat(r.to()).isEqualTo("A-01");
        assertThat(r.toLpn()).isEqualTo("LPN-ORIG");
        tools.jackson.databind.JsonNode done = taskCompleted(allocation);
        assertThat(done.get("taskType").asString()).isEqualTo("RETURN");
        assertThat(done.get("qtyPicked").decimalValue()).isEqualByComparingTo("4");
    }

    @Test
    void operatorsOnlyGetWorkInTheirZonesAndOwners_G5() throws Exception {
        UUID allocation = UUID.randomUUID();
        pickRequested(allocation, "SO-Z", "A-01", "1");
        awaitPickTask(allocation, "RELEASED");
        mvc.perform(post("/api/v1/sites/DC1/tasks/next").with(picker(TestTokens.token().zones("FREEZER"))))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/sites/DC1/tasks/next").with(picker(TestTokens.token().owners("BETA"))))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/sites/DC1/tasks/next").with(picker(TestTokens.token().zones("Z").owners("ACME"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allocationId", is(allocation.toString())));
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor picker(TestTokens.Builder token) {
        return TestTokens.bearer(token.tenant(tenant).user("zone-picker").roles(Roles.PICKER).sign());
    }

    @Test
    void replenishmentTaskMovesReserveStockToTheForwardLocation() throws Exception {
        UUID replen = UUID.randomUUID();
        send(OutboundContracts.TOPIC_TASK_REQUESTS, com.astrawms.common.contracts.InventoryContracts.ReplenRequested.TYPE,
                "DC1:A-01", new com.astrawms.common.contracts.InventoryContracts.ReplenRequested(replen, "ACME", "SKU-1", "",
                        new BigDecimal("8"), "EA", "A-02", "LPN-R", "A-01", 70));
        await(() -> asTenant(() -> jdbc.sql("select count(*) from task where replenishment_id = :r").param("r", replen)
                .query(Integer.class).single()) == 1);
        String id = JsonPath.read(body(post("/api/v1/sites/DC1/tasks/next")), "$.id");
        tasks(get("/api/v1/sites/DC1/tasks/" + id))
                .andExpect(jsonPath("$.taskType", is("REPLEN")))
                .andExpect(jsonPath("$.fromLocation", is("A-02")))
                .andExpect(jsonPath("$.targetLocation", is("A-01")))
                .andExpect(jsonPath("$.qty", is(8)));
        tasks(post("/api/v1/sites/DC1/tasks/" + id + "/replenish"), "{\"checkDigit\":\"22\"}")
                .andExpect(jsonPath("$.code", is("TSK_CHECK_DIGIT_MISMATCH")));          // A-02's digit, not A-01's
        tasks(post("/api/v1/sites/DC1/tasks/" + id + "/replenish"), "{\"checkDigit\":\"33\"}")
                .andExpect(jsonPath("$.status", is("COMPLETED")));
        assertThat(inventory.replenishments).contains("TSK-" + id + "|" + replen);
    }

    @Test
    void countTaskIsBlindAndRecountsExcludeEarlierCounters_INV003() throws Exception {
        UUID count = UUID.randomUUID();
        send(OutboundContracts.TOPIC_TASK_REQUESTS, com.astrawms.common.contracts.InventoryContracts.CountRequested.TYPE,
                "DC1:A-01", new com.astrawms.common.contracts.InventoryContracts.CountRequested(count, "A-01", 2,
                        List.of("cathy"), "ADHOC", 65));
        await(() -> asTenant(() -> jdbc.sql("select count(*) from task where count_id = :c").param("c", count)
                .query(Integer.class).single()) == 1);
        mvc.perform(post("/api/v1/sites/DC1/tasks/next").with(TestTokens.as(tenant, "cathy", Roles.PICKER)))
                .andExpect(status().isNoContent());                                   // cathy counted already
        String body = mvc.perform(post("/api/v1/sites/DC1/tasks/next").with(TestTokens.as(tenant, "dave", Roles.INV_ANALYST)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskType", is("COUNT")))
                .andExpect(jsonPath("$.countSequence", is(2)))
                .andExpect(jsonPath("$.contents.length()", is(0)))                     // blind
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.id");
        String lines = "{\"checkDigit\":\"%s\",\"lines\":[{\"ownerId\":\"ACME\",\"itemNo\":\"SKU-1\",\"qty\":7}]}";
        mvc.perform(post("/api/v1/sites/DC1/tasks/" + id + "/count").with(TestTokens.as(tenant, "dave", Roles.INV_ANALYST))
                        .contentType(MediaType.APPLICATION_JSON).content(lines.formatted("22")))
                .andExpect(jsonPath("$.code", is("TSK_CHECK_DIGIT_MISMATCH")));
        mvc.perform(post("/api/v1/sites/DC1/tasks/" + id + "/count").with(TestTokens.as(tenant, "dave", Roles.INV_ANALYST))
                        .contentType(MediaType.APPLICATION_JSON).content(lines.formatted("33")))
                .andExpect(jsonPath("$.status", is("COMPLETED")));
        StubInventory.Count c = inventory.counts.stream().filter(x -> x.key().equals("TSK-" + id)).findFirst().orElseThrow();
        assertThat(c.countId()).isEqualTo(count);
        assertThat(c.lines()).hasSize(1);
    }

    private void pickRequested(UUID allocation, String order, String from, String qty) throws Exception {
        location("STAGE-OUT", "STAGING_OUT", null, false, "77", 99);
        send(OutboundContracts.TOPIC_TASK_REQUESTS, OutboundContracts.PickRequested.TYPE, "DC1:" + order,
                new OutboundContracts.PickRequested(allocation, order, "000010", "ACME", "SKU-1", "", new BigDecimal(qty),
                        "EA", from, "", "STAGE-OUT", "PK-" + order, 60));
    }

    private String awaitPickTask(UUID allocation, String status) throws InterruptedException {
        await(() -> status.equals(asTenant(() -> jdbc.sql("select status from task where allocation_id = :a")
                .param("a", allocation).query(String.class).optional().orElse(null))));
        return asTenant(() -> jdbc.sql("select id::text from task where allocation_id = :a").param("a", allocation)
                .query(String.class).single());
    }

    private tools.jackson.databind.JsonNode taskCompleted(UUID allocation) {
        return asTenant(() -> jdbc.sql("select envelope::text from outbox where tenant_id = :t and message_type = 'TaskCompleted'")
                .param("t", tenant).query(String.class).list()).stream().map(json::readTree)
                .map(e -> e.get("payload")).filter(p -> p.get("allocationId").asString().equals(allocation.toString()))
                .findFirst().orElseThrow();
    }

    private ResultActions pickConfirm(String id, String checkDigit, String qty) throws Exception {
        return pickConfirm(id, checkDigit, qty, "SKU-1", "");
    }

    private ResultActions pickConfirm(String id, String checkDigit, String qty, String item, String extra) throws Exception {
        return tasks(post("/api/v1/sites/DC1/tasks/" + id + "/pick"), """
                {"checkDigit":"%s","qty":%s%s%s}""".formatted(checkDigit, qty,
                item == null ? "" : ",\"item\":\"" + item + "\"", extra));
    }

    // ------------------------------------------------------------------ ADR-0021 labor

    @Test
    void tasksGoOnlyToOperatorsWithTheSkillAndEquipmentAndTheBoardMeasuresAgainstStandard() throws Exception {
        received("LPN-L", "SKU-1");
        String id = awaitTask("LPN-L", "RELEASED");                     // DOCK-1 -> A-02, both in zone Z
        tasks(put("/api/v1/sites/DC1/tasks/zone-equipment/z"), "{\"equipment\":\"reach_truck\"}")
                .andExpect(jsonPath("$[0].equipment", is("REACH_TRUCK")));
        tasks(put("/api/v1/sites/DC1/tasks/standards/PUTAWAY"), "{\"baseSeconds\":90,\"requiredSkill\":\"forklift\"}")
                .andExpect(jsonPath("$[?(@.taskType == 'PUTAWAY')].requiredSkill", org.hamcrest.Matchers.contains("FORKLIFT")));
        var rita = TestTokens.as(tenant, "rita", Roles.RECEIVER);
        mvc.perform(post("/api/v1/sites/DC1/tasks/next").with(rita)).andExpect(status().isNoContent());   // no profile
        tasks(put("/api/v1/sites/DC1/tasks/operators/rita"), "{\"equipment\":[\"reach_truck\"],\"skills\":[]}")
                .andExpect(jsonPath("$.equipment", is("REACH_TRUCK")));
        mvc.perform(post("/api/v1/sites/DC1/tasks/next").with(rita)).andExpect(status().isNoContent());   // lacks the skill
        tasks(put("/api/v1/sites/DC1/tasks/operators/rita"), "{\"equipment\":[\"REACH_TRUCK\"],\"skills\":[\"FORKLIFT\"]}")
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/sites/DC1/tasks/next").with(rita)).andExpect(jsonPath("$.id", is(id)));

        tasks(get("/api/v1/sites/DC1/tasks/labor"))
                .andExpect(jsonPath("$.activeOperators", is(1)))
                .andExpect(jsonPath("$.operators[0].userId", is("rita")))
                .andExpect(jsonPath("$.operators[0].current.taskType", is("PUTAWAY")))
                .andExpect(jsonPath("$.operators[0].current.expectedMinutes", is(1.5)));
        mvc.perform(post("/api/v1/sites/DC1/tasks/" + id + "/confirm").with(rita).contentType(MediaType.APPLICATION_JSON)
                .content("{\"lpnId\":\"LPN-L\",\"locationId\":\"A-02\",\"checkDigit\":\"22\"}"))
                .andExpect(jsonPath("$.status", is("COMPLETED")));
        tasks(get("/api/v1/sites/DC1/tasks/labor"))
                .andExpect(jsonPath("$.operators[0].completed", is(1)))
                .andExpect(jsonPath("$.operators[0].standardMinutes", is(1.5)))
                .andExpect(jsonPath("$.operators[0].performancePct").isNumber());
        mvc.perform(get("/api/v1/sites/DC1/tasks/labor").with(rita)).andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ ADR-0021 slotting and MOVE tasks

    private void slotting(String item, String zone, String velocity) throws Exception {
        send(InventoryContracts.TOPIC, InventoryContracts.SlottingChanged.TYPE, "DC1:ACME:" + item,
                new InventoryContracts.SlottingChanged("ACME", item, zone, null, velocity, Instant.now()));
        await(() -> asTenant(() -> jdbc.sql("select count(*) from ref_item_slotting where item_no = :i")
                .param("i", item).query(Integer.class).single()) == 1);
    }

    @Test
    void putawayPrefersTheItemsReserveZoneAndSendsSlowMoversFar() throws Exception {
        location("R-NEAR", "RACK", null, false, "61", -5, "RESERVE");   // zone "RESERVE" (location helper uses type as id)
        location("Z-FAR", "RACK", null, false, "62", 50, null);          // zone "Z"
        await(() -> locations() == 6);
        slotting("SKU-1", "Z", "A");
        received("LPN-Z", "SKU-1");
        String zoned = awaitTask("LPN-Z", "RELEASED");
        tasks(get("/api/v1/sites/DC1/tasks/" + zoned))
                .andExpect(jsonPath("$.targetLocation", is("A-02")))            // nearest location of zone Z
                .andExpect(jsonPath("$.strategy", is("EMPTY_NEAREST_ZONE")));
        slotting("SKU-1", null, "C");
        await(() -> asTenant(() -> jdbc.sql("select velocity_class from ref_item_slotting where item_no = 'SKU-1'")
                .query(String.class).single()).equals("C"));
        received("LPN-C", "SKU-1");
        String slow = awaitTask("LPN-C", "RELEASED");
        tasks(get("/api/v1/sites/DC1/tasks/" + slow))
                .andExpect(jsonPath("$.targetLocation", is("Z-FAR")))           // furthest empty slot
                .andExpect(jsonPath("$.strategy", is("EMPTY_FAR_SLOW_MOVER")));
    }

    @Test
    void reslotMoveTaskMovesTheStockToTheNewFace() throws Exception {
        UUID move = UUID.randomUUID();
        send(OutboundContracts.TOPIC_TASK_REQUESTS, InventoryContracts.MoveRequested.TYPE, "DC1:A-01",
                new InventoryContracts.MoveRequested(move, "ACME", "SKU-1", "", "", new BigDecimal("3"), "EA", "A-01", "A-02",
                        "RESLOT", 45));
        await(() -> asTenant(() -> jdbc.sql("select count(*) from task where move_id = :m").param("m", move)
                .query(Integer.class).single()) == 1);
        String id = JsonPath.read(mvc.perform(post("/api/v1/sites/DC1/tasks/next").with(TestTokens.as(tenant, "pete", Roles.PICKER)))
                .andExpect(jsonPath("$.taskType", is("MOVE"))).andExpect(jsonPath("$.strategy", is("RESLOT")))
                .andReturn().getResponse().getContentAsString(), "$.id");
        var pete = TestTokens.as(tenant, "pete", Roles.PICKER);
        mvc.perform(post("/api/v1/sites/DC1/tasks/" + id + "/move").with(pete).contentType(MediaType.APPLICATION_JSON)
                .content("{\"checkDigit\":\"33\"}")).andExpect(jsonPath("$.code", is("TSK_CHECK_DIGIT_MISMATCH")));
        mvc.perform(post("/api/v1/sites/DC1/tasks/" + id + "/move").with(pete).contentType(MediaType.APPLICATION_JSON)
                .content("{\"checkDigit\":\"22\"}")).andExpect(jsonPath("$.status", is("COMPLETED")));
        StubInventory.QtyMove m = inventory.qtyMoves.getLast();
        assertThat(m.from()).isEqualTo("A-01");
        assertThat(m.to()).isEqualTo("A-02");
        assertThat(m.qty()).isEqualByComparingTo("3");
    }

    // ------------------------------------------------------------------ ADR-0020 dock sweep

    @Test
    void dockSweepCreatesPutawaysForEverythingLeftAtTheDock() throws Exception {
        stockEvent(UUID.randomUUID(), "SKU-1", "ADJUST_POS", "LPN-LEFT", "DOCK-1", "5", "5");     // an LPN without a task
        stockEvent(UUID.randomUUID(), "SKU-1", "RECEIPT", "", "DOCK-1", "1", "1");               // a loose unit
        await(() -> asTenant(() -> jdbc.sql("select count(*) from stock_projection").query(Integer.class).single()) == 2);
        mvc.perform(post("/api/v1/sites/DC1/tasks/sweep-dock").with(TestTokens.as(tenant, "rita", Roles.RECEIVER)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/sites/DC1/tasks/sweep-dock").with(TestTokens.as(tenant, "sue", Roles.SUPERVISOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.putawaysCreated", is(1)))
                .andExpect(jsonPath("$.lpnsCreated", is(1)));
        awaitTask("LPN-LEFT", "RELEASED");
        StubInventory.QtyMove m = inventory.qtyMoves.getLast();
        assertThat(m.from()).isEqualTo("DOCK-1");
        assertThat(m.to()).isEqualTo("DOCK-1");
        assertThat(m.toLpn()).startsWith("DK");
        assertThat(m.qty()).isEqualByComparingTo("1");
        // The loose unit arrives on its new LPN at the dock: that is a normal putaway trigger.
        stockEvent(UUID.randomUUID(), "SKU-1", "MOVE_IN", m.toLpn(), "DOCK-1", "1", "1");
        awaitTask(m.toLpn(), "RELEASED");
        // A second sweep finds nothing new for the LPNs that have tasks.
        mvc.perform(post("/api/v1/sites/DC1/tasks/sweep-dock").with(TestTokens.as(tenant, "sue", Roles.SUPERVISOR)))
                .andExpect(jsonPath("$.putawaysCreated", is(0)));
    }

    @Test
    void receiversCannotPickAndOnlySupervisorsReplan_G5() throws Exception {
        String id = java.util.UUID.randomUUID().toString();
        mvc.perform(post("/api/v1/sites/DC1/tasks/" + id + "/pick").with(TestTokens.as(tenant, "rita", Roles.RECEIVER))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"checkDigit\":\"11\",\"qty\":1}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/sites/DC1/tasks/" + id + "/replan").with(TestTokens.as(tenant, "pete", Roles.PICKER)))
                .andExpect(status().isForbidden());
    }


    // ------------------------------------------------------------------ helpers

    private void received(String lpn, String item) throws Exception {
        stockEvent(UUID.randomUUID(), item, "RECEIPT", lpn, "DOCK-1", "10", "10");
    }

    private void stockEvent(UUID op, String item, String txnType, String lpn, String location, String delta,
                            String after) throws Exception {
        send(InventoryContracts.TOPIC, InventoryChanged.TYPE, "DC1:" + item, new InventoryChanged(op, "W1", "TEST",
                "ACME", item, List.of(new InventoryChanged.Line(txnType, "", lpn, location, "AVAILABLE",
                new BigDecimal(delta), new BigDecimal(after))), Instant.now()));
    }

    private void item(String itemNo, String temperature, boolean hazardous) throws Exception {
        send(MasterDataEvents.TOPIC, MasterDataEvents.ITEM_UPSERTED, "ACME:" + itemNo,
                new ItemUpserted("ACME", itemNo, "EA", "ACTIVE", null, temperature, hazardous,
                        List.of(new ItemUpserted.Site("DC1", false, "NONE", "ACTIVE")), List.of(), Instant.now()));
    }

    private void location(String id, String type, String temperature, boolean hazmat, String checkDigit, int seq)
            throws Exception {
        location(id, type, temperature, hazmat, checkDigit, seq, null);
    }

    private void location(String id, String type, String temperature, boolean hazmat, String checkDigit, int seq,
                          String zoneType) throws Exception {
        send(MasterDataEvents.TOPIC, MasterDataEvents.LOCATION_UPSERTED, "DC1:" + id,
                new LocationUpserted("DC1", id, zoneType == null ? "Z" : zoneType, type, "0001", temperature, hazmat, true,
                        true, "ACTIVE", Instant.now(), checkDigit, seq, zoneType));
    }

    private void send(String topic, String type, String key, Object payload) throws Exception {
        EventEnvelope e = new EventEnvelope(UUID.randomUUID(), type, "1.0", "ASTRAWMS", null, tenant, "DC1", "ACME",
                key, "test", 1, Instant.now(), json.valueToTree(payload));
        kafka.send(topic, tenant + ":" + key, json.writeValueAsString(e)).get();
    }

    private String awaitTask(String lpn, String status) throws InterruptedException {
        await(() -> status.equals(asTenant(() -> jdbc.sql("""
                        select status from task where lpn_id = :lpn order by created_at desc limit 1""")
                .param("lpn", lpn).query(String.class).optional().orElse(null))));
        return asTenant(() -> jdbc.sql("select id::text from task where lpn_id = :lpn order by created_at desc limit 1")
                .param("lpn", lpn).query(String.class).single());
    }

    private String taskStatus(String id) {
        return asTenant(() -> jdbc.sql("select status from task where id = cast(:id as uuid)").param("id", id)
                .query(String.class).single());
    }

    private int locations() {
        return asTenant(() -> jdbc.sql("select count(*) from ref_location").query(Integer.class).single());
    }

    private ResultActions confirm(String id, String lpn, String location, String checkDigit) throws Exception {
        return tasks(post("/api/v1/sites/DC1/tasks/" + id + "/confirm"), """
                {"lpnId":"%s","locationId":"%s","checkDigit":"%s"}""".formatted(lpn, location, checkDigit));
    }

    private ResultActions tasks(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder r) throws Exception {
        return tasks(r, "");
    }

    private ResultActions tasks(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder r, String body)
            throws Exception {
        return mvc.perform(r.with(TestTokens.as(tenant, "driver1", TestTokens.ALL_ROLES))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String body(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder r) throws Exception {
        return tasks(r).andReturn().getResponse().getContentAsString();
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
