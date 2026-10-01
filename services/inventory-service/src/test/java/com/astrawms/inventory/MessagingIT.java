package com.astrawms.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.astrawms.inventory.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class MessagingIT extends IntegrationTest {

    @Autowired
    KafkaTemplate<String, String> kafka;
    @Autowired
    JsonMapper json;
    @Autowired
    com.astrawms.common.messaging.MessagingHousekeeping housekeeping;

    @Test
    void goodsMovementIsRelayedToKafkaInCanonicalEnvelope_IFINV001() throws Exception {
        receive("SKU-EA", "10", "EA", "A-01-01", null, null).andExpect(status().isCreated());
        post("/adjustments", """
                {"ownerId":"ACME","itemNo":"SKU-EA","locationId":"A-01-01","qtyDelta":-1,"uom":"EA","reasonCode":"CC_TOL"}""")
                .andExpect(status().isCreated());

        ConsumerRecord<String, String> record = awaitRecord("wms.integration.outbound.goodsmovement.v1");
        JsonNode envelope = json.readTree(record.value());
        assertThat(record.key()).isEqualTo(tenant + ":DC1:SKU-EA");
        assertThat(header(record, "ce_type")).isEqualTo("GoodsMovement");
        assertThat(header(record, "tenantid")).isEqualTo(tenant);
        assertThat(envelope.get("schemaVersion").asString()).isEqualTo("2.0");
        assertThat(envelope.get("targetSystem").asString()).isEqualTo("ERP");
        assertThat(envelope.get("businessKey").asString()).isEqualTo("DC1:SKU-EA");
        assertThat(envelope.get("sequence").asLong()).isEqualTo(1);
        JsonNode payload = envelope.get("payload");
        assertThat(payload.get("wmsTxnId").asString()).hasSize(16);
        assertThat(payload.get("movementType").asString()).isEqualTo("ADJ_NEG");
        assertThat(payload.get("items").get(0).get("qty").decimalValue()).isEqualByComparingTo("1");

        Integer unpublished = queryAsTenant(() -> jdbc.sql(
                "select count(*) from outbox where tenant_id = :t and published_at is null")
                .param("t", tenant).query(Integer.class).single());
        assertThat(unpublished).isZero();
    }

    @Test
    void sequenceIsGapFreePerBusinessKey() throws Exception {
        receive("SKU-EA", "1", "EA", "A-01-01", null, null).andExpect(status().isCreated());
        receive("SKU-EA", "1", "EA", "A-01-02", null, null).andExpect(status().isCreated());
        receive("SKU-LOT", "1", "EA", "A-01-01", null, "L1").andExpect(status().isCreated());
        List<Long> sequences = outboxEnvelopes("InventoryChanged").stream()
                .map(e -> json.readTree(e))
                .filter(e -> e.get("businessKey").asString().equals("DC1:SKU-EA"))
                .map(e -> e.get("sequence").asLong())
                .toList();
        assertThat(sequences).containsExactly(1L, 2L);
    }

    @Test
    void masterDataEventsAreProjectedDeduplicatedAndStaleSafe() throws Exception {
        Instant now = Instant.now();
        UUID messageId = UUID.randomUUID();
        String current = itemEvent(messageId, "SKU-NEW", "EA", now);
        kafka.send("wms.masterdata.events.v1", tenant + ":SKU-NEW", current).get();
        kafka.send("wms.masterdata.events.v1", tenant + ":SKU-NEW", current).get();           // duplicate
        kafka.send("wms.masterdata.events.v1", tenant + ":SKU-NEW",
                itemEvent(UUID.randomUUID(), "SKU-NEW", "KG", now.minusSeconds(3600))).get();  // stale

        awaitCondition(() -> queryAsTenant(() -> jdbc.sql(
                "select count(*) from inbox where message_type = 'ItemUpserted' and message_id <> :id")
                .param("id", messageId.toString()).query(Integer.class).single()) >= 1);

        String baseUom = queryAsTenant(() -> jdbc.sql("select base_uom from ref_item where item_no = 'SKU-NEW'")
                .query(String.class).single());
        assertThat(baseUom).isEqualTo("EA");
        Integer processed = queryAsTenant(() -> jdbc.sql("select count(*) from inbox where message_id = :id")
                .param("id", messageId.toString()).query(Integer.class).single());
        assertThat(processed).isEqualTo(1);

        // The projected item is immediately usable.
        receive("SKU-NEW", "2", "EA", "A-01-01", null, null).andExpect(status().isCreated());
    }

    @Test
    void poisonMessagesGoToTheDeadLetterTopic_F81() throws Exception {
        String key = tenant + ":poison";
        kafka.send("wms.masterdata.events.v1", key, "{not json").get();

        ConsumerRecord<String, String> dead = awaitRecord("wms.masterdata.events.v1.dlq", key);
        assertThat(dead.value()).isEqualTo("{not json");
        assertThat(header(dead, "kafka_dlt-original-topic")).isEqualTo("wms.masterdata.events.v1");
        assertThat(header(dead, "kafka_dlt-exception-fqcn")).contains("Exception");
        // the listener keeps consuming after the poison message
        receive("SKU-EA", "1", "EA", "A-01-01", null, null).andExpect(status().isCreated());
    }

    @Test
    void deadLettersAreListedPerTenantAndReplayedOnlyToTheFailingGroup_ADR0018() throws Exception {
        // A readable envelope whose payload cannot be mapped: dead-lettered without retries.
        String broken = itemEvent(UUID.randomUUID(), "SKU-BAD", "EA", Instant.now()).replace("\"payload\":{","\"payload\":\"oops\",\"x\":{");
        kafka.send("wms.masterdata.events.v1", tenant + ":SKU-BAD", broken).get();
        awaitRecord("wms.masterdata.events.v1.dlq", tenant + ":SKU-BAD");

        var admin = com.astrawms.test.TestTokens.as(tenant, "ada", com.astrawms.common.security.Roles.SOLUTION_ADMIN);
        JsonNode listing = awaitDeadLetters(admin, 1);
        JsonNode m = listing.get("messages").get(0);
        assertThat(m.get("originalTopic").asString()).isEqualTo("wms.masterdata.events.v1");
        assertThat(m.get("originalGroup").asString()).isEqualTo("inventory-service.reference");
        assertThat(m.get("messageType").asString()).isEqualTo("ItemUpserted");
        assertThat(m.get("error").asString()).isNotBlank();

        // Other tenants and non-admins see nothing.
        JsonNode other = json.readTree(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/ops/dlq").with(com.astrawms.test.TestTokens.as("other-" + tenant, "ada",
                                com.astrawms.common.security.Roles.SOLUTION_ADMIN)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(other.get("messages")).isEmpty();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/ops/dlq")
                        .with(com.astrawms.test.TestTokens.as(tenant, "sue", com.astrawms.common.security.Roles.SUPERVISOR)))
                .andExpect(status().isForbidden());

        String replay = "{\"topic\":\"%s\",\"partition\":%d,\"offset\":%d}".formatted(m.get("dlqTopic").asString(),
                m.get("partition").asInt(), m.get("offset").asLong());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/ops/dlq/replay").with(admin)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(replay))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.replayedBy").value("ada"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/ops/dlq/replay").with(admin)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(replay))
                .andExpect(status().isConflict());

        // Still broken, so the failing group dead-letters it again: proof it was redelivered to that group.
        JsonNode after = awaitDeadLetters(admin, 2);
        assertThat(after.get("messages").get(0).get("replayedBy").asString()).isEqualTo("ada");
    }

    private JsonNode awaitDeadLetters(org.springframework.test.web.servlet.request.RequestPostProcessor auth, int count)
            throws Exception {
        Instant deadline = Instant.now().plusSeconds(30);
        JsonNode listing = null;
        while (Instant.now().isBefore(deadline)) {
            listing = json.readTree(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .get("/api/v1/ops/dlq").with(auth)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            if (listing.get("messages").size() >= count) {
                return listing;
            }
            Thread.sleep(300);
        }
        throw new AssertionError("Expected " + count + " dead letters, got " + listing);
    }

    @Test
    void housekeepingPurgesOldPublishedOutboxAndInboxRowsOnly() {
        Instant old = Instant.now().minus(Duration.ofDays(40));
        UUID oldPublished = UUID.randomUUID();
        UUID oldUnpublished = UUID.randomUUID();
        asTenant(() -> {
            for (UUID id : List.of(oldPublished, oldUnpublished)) {
                jdbc.sql("""
                                insert into outbox (message_id, topic, message_key, message_type, tenant_id, envelope,
                                                    created_at, published_at)
                                values (:id, 't', 'k', 'Test', :tenant, '{}'::jsonb, :at, :published)""")
                        .param("id", id).param("tenant", tenant).param("at", java.sql.Timestamp.from(old))
                        .param("published", id.equals(oldPublished) ? java.sql.Timestamp.from(old) : null)
                        .update();
            }
            jdbc.sql("insert into inbox (source_system, message_id, message_type, processed_at) values ('T', :id, 'Test', :at)")
                    .param("id", tenant + "-old").param("at", java.sql.Timestamp.from(old)).update();
        });

        housekeeping.purge();

        assertThat(queryAsTenant(() -> jdbc.sql("select message_id from outbox where message_id in (:ids)")
                .param("ids", List.of(oldPublished, oldUnpublished)).query(UUID.class).list()))
                .containsExactly(oldUnpublished);   // never published: must stay for the relay
        assertThat(queryAsTenant(() -> jdbc.sql("select count(*) from inbox where message_id = :id")
                .param("id", tenant + "-old").query(Integer.class).single())).isZero();
    }

    private String itemEvent(UUID messageId, String itemNo, String baseUom, Instant changedAt) {
        Map<String, Object> payload = Map.of(
                "ownerId", OWNER, "itemNo", itemNo, "baseUom", baseUom, "status", "ACTIVE", "hazardous", false,
                "sites", List.of(Map.of("siteId", SITE, "lotControlled", false, "serialControl", "NONE", "status", "ACTIVE")),
                "uoms", List.of(), "sourceChangedAt", changedAt.toString());
        Map<String, Object> envelope = Map.ofEntries(
                Map.entry("messageId", messageId.toString()), Map.entry("messageType", "ItemUpserted"),
                Map.entry("schemaVersion", "1.0"), Map.entry("sourceSystem", "ASTRAWMS"),
                Map.entry("tenantId", tenant), Map.entry("siteId", SITE), Map.entry("ownerId", OWNER),
                Map.entry("businessKey", OWNER + ":" + itemNo), Map.entry("correlationId", "test"),
                Map.entry("sequence", 1), Map.entry("createdAtUtc", Instant.now().toString()),
                Map.entry("payload", payload));
        return json.writeValueAsString(envelope);
    }

    private ConsumerRecord<String, String> awaitRecord(String topic) {
        return awaitRecord(topic, null);
    }

    private ConsumerRecord<String, String> awaitRecord(String topic, String exactKey) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
            consumer.subscribe(List.of(topic));
            Instant deadline = Instant.now().plusSeconds(30);
            while (Instant.now().isBefore(deadline)) {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(500))) {
                    if (exactKey == null ? r.key().startsWith(tenant + ":") : exactKey.equals(r.key())) {
                        return r;
                    }
                }
            }
        }
        throw new AssertionError("No record for tenant " + tenant + " on " + topic);
    }

    private static String header(ConsumerRecord<String, String> r, String name) {
        return new String(r.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }

    private static void awaitCondition(java.util.function.BooleanSupplier condition) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(30);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Condition not met within 30 s");
    }
}
