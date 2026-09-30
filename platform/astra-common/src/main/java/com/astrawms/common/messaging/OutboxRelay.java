package com.astrawms.common.messaging;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publishes committed outbox rows to Kafka in insertion order.
 *
 * <p>Only one relay instance works at a time (transaction-scoped advisory lock), which preserves per-key
 * ordering across horizontally scaled service replicas. Delivery is at-least-once: a crash after the Kafka send
 * and before the row update re-sends the message, and consumers de-duplicate by {@code messageId}
 * ({@link InboxGuard}).
 */
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final long RELAY_LOCK = 0x4153_5452_4F42_5831L; // "ASTROBX1"

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final KafkaTemplate<String, String> kafka;
    private final Clock clock;
    private final int batchSize;
    private final Duration sendTimeout;

    public OutboxRelay(JdbcClient jdbc, TransactionTemplate tx, KafkaTemplate<String, String> kafka, Clock clock,
                       int batchSize, Duration sendTimeout) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.kafka = kafka;
        this.clock = clock;
        this.batchSize = batchSize;
        this.sendTimeout = sendTimeout;
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

    /** Publishes one batch; returns the number of messages published. */
    public int relayOnce() {
        Integer published = tx.execute(status -> {
            Boolean locked = jdbc.sql("select pg_try_advisory_xact_lock(:lock)").param("lock", RELAY_LOCK)
                    .query(Boolean.class).single();
            if (!Boolean.TRUE.equals(locked)) {
                return 0;
            }
            List<Row> rows = jdbc.sql("""
                            select id, message_id, topic, message_key, message_type, tenant_id,
                                   envelope->>'sourceSystem' as source_system, envelope::text as envelope
                            from outbox where published_at is null order by id limit :limit""")
                    .param("limit", batchSize)
                    .query((rs, n) -> new Row(rs.getLong("id"), rs.getObject("message_id", UUID.class),
                            rs.getString("topic"), rs.getString("message_key"), rs.getString("message_type"),
                            rs.getString("tenant_id"), rs.getString("source_system"), rs.getString("envelope")))
                    .list();
            for (Row row : rows) {
                send(row);
                jdbc.sql("update outbox set published_at = :now where id = :id")
                        .param("now", Timestamp.from(clock.instant())).param("id", row.id()).update();
            }
            return rows.size();
        });
        return published == null ? 0 : published;
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
            kafka.send(record).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while publishing outbox message " + row.messageId(), e);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to publish outbox message " + row.messageId(), e);
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
