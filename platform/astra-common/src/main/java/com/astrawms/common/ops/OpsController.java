package com.astrawms.common.ops;

import com.astrawms.common.tenancy.TenantContext;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Per-service operations API for the caller's tenant (ADR-0018). The gateway publishes it as
 * {@code /api/v1/ops/<service>/...}. Solution administrators only: dead-lettered messages carry business data.
 */
@RestController
@RequestMapping("/api/v1/ops")
@PreAuthorize("hasRole('SOLUTION_ADMIN')")
public class OpsController {

    private final DeadLetters deadLetters;
    private final JdbcClient jdbc;
    private final Clock clock;
    private final com.astrawms.common.messaging.OutboxRelay relay;
    private final com.astrawms.common.tenancy.TenantRegistry registry;

    public OpsController(DeadLetters deadLetters, JdbcClient jdbc, Clock clock,
                         com.astrawms.common.messaging.OutboxRelay relay) {
        this(deadLetters, jdbc, clock, relay, null);
    }

    public OpsController(DeadLetters deadLetters, JdbcClient jdbc, Clock clock,
                         com.astrawms.common.messaging.OutboxRelay relay, com.astrawms.common.tenancy.TenantRegistry registry) {
        this.deadLetters = deadLetters;
        this.jdbc = jdbc;
        this.clock = clock;
        this.relay = relay;
        this.registry = registry;
    }

    /** {@code force} and {@code reason}: only for a dead letter older than the inbox retention (ADR-0029). */
    public record ReplayRequest(String topic, int partition, long offset, boolean force, String reason) {
    }

    @GetMapping("/dlq")
    public DeadLetters.Listing deadLetters() {
        return deadLetters.list();
    }

    @PostMapping("/dlq/replay")
    public DeadLetters.Message replay(@RequestBody ReplayRequest r) {
        return deadLetters.replay(r.topic(), r.partition(), r.offset(), r.force(), r.reason());
    }

    /**
     * Fast service status for the Operations page (ADR-0029): this tenant's outbox backlog, topics the relay paused
     * after failed sends, and the time of the answer. Kafka is not read, so it answers even when the broker does not.
     */
    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>(outbox());
        out.put("pausedTopics", relay == null ? Map.of() : relay.pausedTopics());
        out.put("relayEnabled", relay != null);
        // ADR-0030 A: whether this service enforces the tenant registry, has read it, and how many tenants per status
        out.put("tenantRegistry", registry == null ? Map.of("mode", "NONE") : registry.snapshot());
        out.put("at", clock.instant());
        return out;
    }

    /** Messages written but not yet on Kafka for this tenant: a growing backlog means Kafka or the relay is down. */
    @GetMapping("/outbox")
    public Map<String, Object> outbox() {
        Map<String, Object> row = jdbc.sql("""
                        select count(*) as pending, min(created_at) as oldest from outbox
                        where published_at is null and tenant_id = :t""")
                .param("t", TenantContext.tenantId()).query().singleRow();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pending", row.get("pending"));
        out.put("oldestPendingSeconds", row.get("oldest") instanceof Timestamp ts
                ? Duration.between(ts.toInstant(), clock.instant()).toSeconds() : null);
        return out;
    }
}
