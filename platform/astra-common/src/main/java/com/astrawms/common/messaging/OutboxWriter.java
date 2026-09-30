package com.astrawms.common.messaging;

import com.astrawms.common.tenancy.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * Transactional outbox (architecture §F.8.2): the event row is written in the same database transaction as the
 * business change, so a change is published if and only if it commits. {@link OutboxRelay} publishes to Kafka.
 */
public class OutboxWriter {

    public static final String SOURCE_SYSTEM = "ASTRAWMS";

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final Clock clock;

    public OutboxWriter(JdbcClient jdbc, JsonMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    public record Message(String topic, String messageType, String schemaVersion, String targetSystem,
                          String siteId, String ownerId, String businessKey, Object payload) {
    }

    /** Appends a message to the outbox; must be called inside the business transaction. */
    public EventEnvelope append(Message m) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("OutboxWriter.append requires an active transaction");
        }
        String tenant = TenantContext.tenantId();
        long sequence = jdbc.sql("""
                        insert into outbox_key_sequence (topic, business_key, last_seq) values (:topic, :key, 1)
                        on conflict (topic, business_key)
                        do update set last_seq = outbox_key_sequence.last_seq + 1
                        returning last_seq""")
                .param("topic", m.topic())
                .param("key", tenant + ":" + m.businessKey())
                .query(Long.class)
                .single();
        String correlationId = MDC.get("correlationId");
        EventEnvelope envelope = new EventEnvelope(
                UUID.randomUUID(), m.messageType(), m.schemaVersion(), SOURCE_SYSTEM, m.targetSystem(),
                tenant, m.siteId(), m.ownerId(), m.businessKey(),
                correlationId != null ? correlationId : UUID.randomUUID().toString(),
                sequence, Instant.now(clock), json.valueToTree(m.payload()));
        jdbc.sql("""
                        insert into outbox (message_id, topic, message_key, message_type, tenant_id, envelope, created_at)
                        values (:id, :topic, :key, :type, :tenant, cast(:envelope as jsonb), :createdAt)""")
                .param("id", envelope.messageId())
                .param("topic", m.topic())
                .param("key", tenant + ":" + m.businessKey())
                .param("type", m.messageType())
                .param("tenant", tenant)
                .param("envelope", json.writeValueAsString(envelope))
                .param("createdAt", java.sql.Timestamp.from(envelope.createdAtUtc()))
                .update();
        return envelope;
    }
}
