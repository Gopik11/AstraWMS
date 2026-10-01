package com.astrawms.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.astrawms.common.contracts.InventoryContracts;
import com.astrawms.common.contracts.InventoryContracts.InventoryChanged;
import com.astrawms.common.contracts.MasterDataEvents;
import com.astrawms.common.contracts.OutboundContracts;
import com.astrawms.common.contracts.MasterDataEvents.ItemUpserted;
import com.astrawms.common.contracts.MasterDataEvents.LocationUpserted;
import com.astrawms.common.messaging.EventEnvelope;
import com.astrawms.common.tenancy.TenantContext;
import com.astrawms.common.tenancy.TenantFilter;
import com.astrawms.task.inventory.InventoryClient;
import com.astrawms.test.AstraContainers;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
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
        final List<Pick> picks = new CopyOnWriteArrayList<>();

        @Override
        public UUID pick(String siteId, String key, UUID allocationId, BigDecimal qty, String to, String toLpn,
                         List<String> serials, boolean shortClose) {
            if (picks.stream().noneMatch(p -> p.key().equals(key))) {
                picks.add(new Pick(key, allocationId, qty, to, toLpn, serials, shortClose));
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

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(new TenantFilter()).build();
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
        confirm(id, "LPN-1", "A-01", "33").andExpect(jsonPath("$.strategy", is("OVERRIDE")))
                .andExpect(jsonPath("$.confirmedLocation", is("A-01")));
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
        pickConfirm(id, "33", "6").andExpect(jsonPath("$.status", is("COMPLETED")))
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
        pickConfirm(id, "33", "4").andExpect(jsonPath("$.exceptionReason", is("SHORT_PICK")));
        assertThat(inventory.picks.stream().filter(x -> x.key().equals("TSK-" + id)).findFirst().orElseThrow().shortClose())
                .isTrue();
        assertThat(taskCompleted(allocation).get("qtyShort").decimalValue()).isEqualByComparingTo("2");
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
        return tasks(post("/api/v1/sites/DC1/tasks/" + id + "/pick"), """
                {"checkDigit":"%s","qty":%s}""".formatted(checkDigit, qty));
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
        send(MasterDataEvents.TOPIC, MasterDataEvents.LOCATION_UPSERTED, "DC1:" + id,
                new LocationUpserted("DC1", id, "Z", type, "0001", temperature, hazmat, true, true, "ACTIVE",
                        Instant.now(), checkDigit, seq));
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
        return mvc.perform(r.header(TenantFilter.TENANT_HEADER, tenant).header(TenantFilter.USER_HEADER, "driver1")
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
