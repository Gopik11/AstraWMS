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

    public OpsController(DeadLetters deadLetters, JdbcClient jdbc, Clock clock) {
        this.deadLetters = deadLetters;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public record ReplayRequest(String topic, int partition, long offset) {
    }

    @GetMapping("/dlq")
    public DeadLetters.Listing deadLetters() {
        return deadLetters.list();
    }

    @PostMapping("/dlq/replay")
    public DeadLetters.Message replay(@RequestBody ReplayRequest r) {
        return deadLetters.replay(r.topic(), r.partition(), r.offset());
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
