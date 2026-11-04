package com.astrawms.common.messaging;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Publishes committed outbox rows to Kafka (ADR-0002, ADR-0029).
 *
 * <p><b>Partitioned relay.</b> Outbox rows are split into {@code partitions} relay partitions by a hash of their
 * message key ({@code tenant:businessKey}). One relay partition is worked by one replica at a time (a session-level
 * advisory lock per partition), rows in id order, one send acknowledged before the next. Messages of one key always
 * land in the same relay partition, so per-key order is kept across replicas; unrelated keys go through other
 * partitions in parallel.
 *
 * <p><b>No transaction across Kafka I/O.</b> The partition lock is held on a connection that is <em>not</em> in a
 * transaction: no row locks, no long transaction, no held MVCC horizon while a send waits. Each acknowledged row is
 * marked published at once (auto-commit). A crash between the acknowledgement and the update re-sends that message:
 * delivery stays at-least-once, and consumers de-duplicate by message id ({@link InboxProcessing}).
 *
 * <p><b>Failure isolation.</b> A failed send stops that topic for the rest of the partition's batch (so no later
 * message of a key overtakes an earlier one) and opens a per-topic circuit breaker for {@code breakerOpen}: other
 * topics keep flowing. A partition's batch also stops at {@code cycleBudget}, bounding how long a lock is held.
 *
 * <p><b>Rolling upgrade.</b> Each partition worker also holds the pre-ADR-0029 relay lock in shared mode; the old relay
 * took it exclusively, so old and new replicas never publish at the same time.
 *
 * <p>The number of partitions must be the same on every replica; change it only with the relay stopped everywhere.
 */
public class OutboxRelay implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    /** The single relay lock of ADR-0002; held in shared mode by partition workers (rolling-upgrade fence). */
    static final long LEGACY_RELAY_LOCK = 0x4153_5452_4F42_5831L; // "ASTROBX1"
    /** First key of the two-int advisory locks of relay partitions. */
    static final int PARTITION_LOCK_CLASS = 0x41535452; // "ASTR"

    private final DataSource dataSource;
    private final KafkaTemplate<String, String> kafka;
    private final Clock clock;
    private final Settings settings;
    private final ExecutorService workers;
    private final Map<String, Instant> openBreakers = new ConcurrentHashMap<>();

    private final MeterRegistry meters;
    private final Timer cycleTimer;
    private final Timer holdTimer;

    /**
     * @param partitions  relay partitions (same on every replica)
     * @param batchSize   rows per partition per cycle
     * @param sendTimeout how long one Kafka send may wait for its acknowledgement
     * @param cycleBudget how long one partition's batch may run (bounds the lock hold time)
     * @param breakerOpen how long a topic is skipped after a failed send
     * @param parallelism partition workers per replica (each holds one database connection while it runs)
     */
    public record Settings(int partitions, int batchSize, Duration sendTimeout, Duration cycleBudget, Duration breakerOpen,
                           int parallelism) {

        public Settings {
            if (partitions < 1 || batchSize < 1 || parallelism < 1) {
                throw new IllegalArgumentException("partitions, batchSize and parallelism must be positive");
            }
        }

        public static Settings defaults() {
            return new Settings(8, 200, Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofSeconds(30), 2);
        }
    }

    public OutboxRelay(DataSource dataSource, KafkaTemplate<String, String> kafka, Clock clock, Settings settings,
                       MeterRegistry meters) {
        this.dataSource = dataSource;
        this.kafka = kafka;
        this.clock = clock;
        this.settings = settings;
        this.meters = meters != null ? meters : new SimpleMeterRegistry();
        this.workers = Executors.newFixedThreadPool(Math.min(settings.parallelism(), settings.partitions()), r -> {
            Thread t = new Thread(r, "outbox-relay");
            t.setDaemon(true);
            return t;
        });
        this.cycleTimer = Timer.builder("astra.outbox.relay.cycle").description("One relay cycle over all partitions")
                .register(this.meters);
        this.holdTimer = Timer.builder("astra.outbox.relay.partition.hold")
                .description("How long a relay partition lock was held").register(this.meters);
    }

    private record Row(long id, UUID messageId, String topic, String messageKey, String messageType, String tenantId,
                       String sourceSystem, String envelope) {
    }

    @Scheduled(fixedDelayString = "${astra.outbox.relay-interval-ms:500}")
    public void relayScheduled() {
        try {
            relayOnce();
        } catch (RuntimeException e) {
            log.warn("Outbox relay cycle failed; will retry", e);
        }
    }

    /** Works every relay partition once (those another replica holds are skipped); returns the messages published. */
    public int relayOnce() {
        long start = System.nanoTime();
        List<Future<Integer>> runs = new ArrayList<>();
        for (int p = 0; p < settings.partitions(); p++) {
            int partition = p;
            runs.add(workers.submit(() -> relayPartition(partition)));
        }
        int published = 0;
        for (Future<Integer> f : runs) {
            try {
                published += f.get(settings.cycleBudget().toMillis() + settings.sendTimeout().toMillis() + 5_000,
                        TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("Outbox relay partition failed; will retry", e);
            }
        }
        cycleTimer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        return published;
    }

    /** One partition's batch: rows in id order, one acknowledged send at a time; returns the messages published. */
    int relayPartition(int partition) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(true);
            if (!lock(c, "select pg_try_advisory_lock_shared(?)", LEGACY_RELAY_LOCK)) {
                skipped("legacy_relay");
                return 0;
            }
            long held = System.nanoTime();
            try {
                if (!lock(c, "select pg_try_advisory_lock(?, ?)", PARTITION_LOCK_CLASS, partition)) {
                    skipped("partition_busy");
                    return 0;
                }
                return publish(c, partition);
            } finally {
                holdTimer.record(System.nanoTime() - held, TimeUnit.NANOSECONDS);
                try (PreparedStatement s = c.prepareStatement("select pg_advisory_unlock_all()")) {
                    s.execute();
                }
            }
        }
    }

    private int publish(Connection c, int partition) throws SQLException {
        List<Row> rows = pending(c, partition);
        Instant deadline = clock.instant().plus(settings.cycleBudget());
        Set<String> stopped = new HashSet<>();
        int published = 0;
        for (Row row : rows) {
            if (stopped.contains(row.topic())) {
                continue;
            }
            if (breakerOpen(row.topic())) {
                stopped.add(row.topic());
                continue;
            }
            if (clock.instant().isAfter(deadline)) {
                break;
            }
            long sent = System.nanoTime();
            try {
                send(row);
            } catch (RuntimeException e) {
                sendTimer(row.topic(), "error").record(System.nanoTime() - sent, TimeUnit.NANOSECONDS);
                stopped.add(row.topic());   // no later message of this topic may overtake the failed one
                openBreaker(row.topic());
                log.warn("Outbox relay: publishing to {} failed, topic paused for {} ({})", row.topic(),
                        settings.breakerOpen(), e.getMessage());
                continue;
            }
            sendTimer(row.topic(), "ok").record(System.nanoTime() - sent, TimeUnit.NANOSECONDS);
            // cross-tenant: by the primary key of a row read below
            try (PreparedStatement s = c.prepareStatement("update outbox set published_at = ? where id = ?")) {
                s.setTimestamp(1, Timestamp.from(clock.instant()));
                s.setLong(2, row.id());
                s.executeUpdate();
            }
            published++;
        }
        return published;
    }

    private List<Row> pending(Connection c, int partition) throws SQLException {
        List<Row> rows = new ArrayList<>();
        // cross-tenant: the relay publishes every tenant's messages (ADR-0002, ADR-0029)
        try (PreparedStatement s = c.prepareStatement("""
                select id, message_id, topic, message_key, message_type, tenant_id,
                       envelope->>'sourceSystem' as source_system, envelope::text as envelope
                from outbox
                where published_at is null and abs(hashtext(message_key)::bigint) % ? = ?
                order by id limit ?""")) {
            s.setInt(1, settings.partitions());
            s.setInt(2, partition);
            s.setInt(3, settings.batchSize());
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) {
                    rows.add(new Row(rs.getLong("id"), rs.getObject("message_id", UUID.class), rs.getString("topic"),
                            rs.getString("message_key"), rs.getString("message_type"), rs.getString("tenant_id"),
                            rs.getString("source_system"), rs.getString("envelope")));
                }
            }
        }
        return rows;
    }

    private void send(Row row) {
        ProducerRecord<String, String> record = new ProducerRecord<>(row.topic(), row.messageKey(), row.envelope());
        record.headers()
                .add("ce_specversion", bytes("1.0"))
                .add("ce_id", bytes(row.messageId().toString()))
                .add("ce_type", bytes(row.messageType()))
                .add("ce_source", bytes(row.sourceSystem() != null ? row.sourceSystem() : OutboxWriter.SOURCE_SYSTEM))
                .add("tenantid", bytes(row.tenantId()));
        try {
            kafka.send(record).get(settings.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while publishing outbox message " + row.messageId(), e);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to publish outbox message " + row.messageId(), e);
        }
    }

    // ------------------------------------------------------------------ circuit breaker

    private boolean breakerOpen(String topic) {
        Instant until = openBreakers.get(topic);
        if (until == null) {
            return false;
        }
        if (clock.instant().isBefore(until)) {
            return true;
        }
        openBreakers.remove(topic, until);   // half-open: the next send is a trial
        return false;
    }

    private void openBreaker(String topic) {
        openBreakers.put(topic, clock.instant().plus(settings.breakerOpen()));
        Counter.builder("astra.outbox.breaker.opened").tag("topic", topic)
                .description("Times a topic was paused after a failed send").register(meters).increment();
    }

    /** Topics currently paused after a failed send, with the time they are tried again. */
    public Map<String, Instant> pausedTopics() {
        Instant now = clock.instant();
        Map<String, Instant> out = new java.util.TreeMap<>();
        openBreakers.forEach((t, until) -> {
            if (now.isBefore(until)) {
                out.put(t, until);
            }
        });
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private Timer sendTimer(String topic, String outcome) {
        return Timer.builder("astra.outbox.send").tag("topic", topic).tag("outcome", outcome)
                .description("Kafka send of one outbox message, until acknowledged").register(meters);
    }

    private void skipped(String reason) {
        Counter.builder("astra.outbox.relay.skipped").tag("reason", reason)
                .description("Relay partitions skipped because another relay holds them").register(meters).increment();
    }

    private static boolean lock(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                s.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        workers.shutdownNow();
    }
}
