package com.astrawms.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.astrawms.common.messaging.OutboxRelay;
import com.astrawms.test.AstraContainers;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

/**
 * The partitioned outbox relay (ADR-0029) on its own database, so no relay of another test context touches its rows:
 * per-key order, a failing topic paused without stopping others, no relay while the pre-ADR-0029 relay holds its lock,
 * and partitions held by another replica skipped.
 */
class OutboxRelayIT {

    /** Records sends in order; topics in {@link #failing} fail. */
    static final class FakeKafka extends KafkaTemplate<String, String> {
        final List<ProducerRecord<String, String>> sent = new CopyOnWriteArrayList<>();
        final Set<String> failing = ConcurrentHashMap.newKeySet();

        FakeKafka() {
            super(new DefaultKafkaProducerFactory<>(Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:1"),
                    new StringSerializer(), new StringSerializer()));
        }

        @Override
        public CompletableFuture<SendResult<String, String>> send(ProducerRecord<String, String> record) {
            if (failing.contains(record.topic())) {
                return CompletableFuture.failedFuture(new IllegalStateException("broker unavailable for " + record.topic()));
            }
            sent.add(record);
            return CompletableFuture.completedFuture(new SendResult<>(record, null));
        }
    }

    static final class MutableClock extends Clock {
        volatile Instant now = Instant.parse("2026-11-01T08:00:00Z");

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private String database;
    private DriverManagerDataSource ds;
    private JdbcClient jdbc;
    private final FakeKafka kafka = new FakeKafka();
    private final MutableClock clock = new MutableClock();
    private OutboxRelay relay;

    @BeforeEach
    void freshDatabase() throws Exception {
        database = "relay_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        try (Connection c = superuser("postgres")) {
            c.createStatement().execute("create database " + database);
        }
        ds = new DriverManagerDataSource(url(database), AstraContainers.POSTGRES.getUsername(),
                AstraContainers.POSTGRES.getPassword());
        jdbc = JdbcClient.create(ds);
        String ddl = new ClassPathResource("db/migration/common/V0_1__outbox_inbox.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        try (Connection c = ds.getConnection()) {
            c.createStatement().execute(ddl);
        }
        relay = new OutboxRelay(ds, kafka, clock,
                new OutboxRelay.Settings(4, 200, Duration.ofSeconds(2), Duration.ofSeconds(30), Duration.ofSeconds(30), 2),
                null);
    }

    @AfterEach
    void dropDatabase() throws Exception {
        relay.close();
        try (Connection c = superuser("postgres")) {
            c.createStatement().execute("drop database if exists " + database + " with (force)");
        }
    }

    private static String url(String db) {
        return AstraContainers.POSTGRES.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + db + "$1");
    }

    private static Connection superuser(String db) throws Exception {
        return DriverManager.getConnection(url(db), AstraContainers.POSTGRES.getUsername(),
                AstraContainers.POSTGRES.getPassword());
    }

    private void append(String topic, String key, int seq) {
        jdbc.sql("""
                        insert into outbox (message_id, topic, message_key, message_type, tenant_id, envelope, created_at)
                        values (:id, :topic, :key, 'T', 't1', cast(:env as jsonb), now())""")
                .param("id", UUID.randomUUID()).param("topic", topic).param("key", key)
                .param("env", "{\"seq\":" + seq + ",\"sourceSystem\":\"ASTRAWMS\"}").update();
    }

    private List<String> sentFor(String topic, String key) {
        return kafka.sent.stream().filter(r -> r.topic().equals(topic) && r.key().equals(key))
                .map(ProducerRecord::value).toList();
    }

    private int unpublished(String topic) {
        return jdbc.sql("select count(*) from outbox where published_at is null and topic = :t").param("t", topic)
                .query(Integer.class).single();
    }

    @Test
    void publishesEveryKeyInOrderAcrossPartitions() {
        List<String> keys = new ArrayList<>();
        for (int k = 0; k < 20; k++) {
            keys.add("t1:ORDER-" + k);
        }
        for (int seq = 1; seq <= 5; seq++) {
            for (String key : keys) {
                append("wms.a", key, seq);
            }
        }
        assertThat(relay.relayOnce()).isEqualTo(100);
        for (String key : keys) {
            assertThat(sentFor("wms.a", key)).containsExactly("{\"seq\": 1, \"sourceSystem\": \"ASTRAWMS\"}",
                    "{\"seq\": 2, \"sourceSystem\": \"ASTRAWMS\"}", "{\"seq\": 3, \"sourceSystem\": \"ASTRAWMS\"}",
                    "{\"seq\": 4, \"sourceSystem\": \"ASTRAWMS\"}", "{\"seq\": 5, \"sourceSystem\": \"ASTRAWMS\"}");
        }
        assertThat(relay.relayOnce()).isZero();
        // the keys really are spread over the relay partitions
        assertThat(jdbc.sql("select count(distinct abs(hashtext(message_key)::bigint) % 4) from outbox")
                .query(Integer.class).single()).isGreaterThan(1);
    }

    @Test
    void aFailingTopicIsPausedWhileOtherTopicsFlowAndResumesInOrder() {
        for (int seq = 1; seq <= 3; seq++) {
            append("wms.down", "t1:K", seq);
            append("wms.up", "t1:K", seq);
        }
        kafka.failing.add("wms.down");

        assertThat(relay.relayOnce()).isEqualTo(3);
        assertThat(unpublished("wms.down")).isEqualTo(3);
        assertThat(sentFor("wms.up", "t1:K")).hasSize(3);
        assertThat(relay.pausedTopics()).containsOnlyKeys("wms.down");

        kafka.failing.clear();
        assertThat(relay.relayOnce()).isZero();               // still paused: no attempt within the breaker window
        clock.now = clock.now.plus(Duration.ofSeconds(31));
        assertThat(relay.relayOnce()).isEqualTo(3);
        assertThat(sentFor("wms.down", "t1:K").stream().map(v -> v.substring(8, 9)).collect(Collectors.joining()))
                .isEqualTo("123");
        assertThat(relay.pausedTopics()).isEmpty();
    }

    @Test
    void doesNotRelayWhileThePreviousRelayHoldsItsLock() throws Exception {
        append("wms.a", "t1:K", 1);
        try (Connection legacy = ds.getConnection()) {
            legacy.setAutoCommit(false);
            legacy.createStatement().execute("select pg_advisory_xact_lock(" + 0x4153_5452_4F42_5831L + ")");
            assertThat(relay.relayOnce()).isZero();
            legacy.commit();
        }
        assertThat(relay.relayOnce()).isEqualTo(1);
    }

    @Test
    void partitionsHeldByAnotherReplicaAreSkippedAndLocksAreReleased() throws Exception {
        for (int k = 0; k < 12; k++) {
            append("wms.a", "t1:K" + k, 1);
        }
        try (Connection other = ds.getConnection()) {
            for (int p = 0; p < 4; p++) {
                other.createStatement().execute("select pg_advisory_lock(" + 0x41535452 + ", " + p + ")");
            }
            assertThat(relay.relayOnce()).isZero();
            other.createStatement().execute("select pg_advisory_unlock_all()");
        }
        assertThat(relay.relayOnce()).isEqualTo(12);
        // the relay released its own locks: nothing of it is left in pg_locks
        assertThat(jdbc.sql("select count(*) from pg_locks where locktype = 'advisory'").query(Integer.class).single())
                .isZero();
        assertThat(Collections.frequency(kafka.sent.stream().map(ProducerRecord::topic).toList(), "wms.a")).isEqualTo(12);
    }
}
