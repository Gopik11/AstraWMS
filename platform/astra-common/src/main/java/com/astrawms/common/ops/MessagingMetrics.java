package com.astrawms.common.ops;

import com.astrawms.common.tenancy.TenantTags;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Messaging health as Prometheus metrics, across tenants (scraped on the private network only, ADR-0018):
 * <ul>
 *   <li>{@code astra_outbox_pending}: rows not yet published to Kafka;</li>
 *   <li>{@code astra_outbox_oldest_pending_seconds}: age of the oldest one (alert when it keeps growing);</li>
 *   <li>{@code astra_kafka_dead_lettered_total} (counter, by topic and group) is incremented by KafkaErrorHandling;</li>
 *   <li>{@code astra_outbox_pending_tenant{tenant}} (ADR-0030 E): the backlog of the {@value #TOP_TENANTS} tenants with
 *       the most unpublished messages by name, the rest summed as {@code other}; refreshed every 30 s. Tenants the
 *       registry does not know are {@code unregistered}, so the number of series stays bounded ({@link TenantTags}).</li>
 * </ul>
 */
public class MessagingMetrics implements MeterBinder {

    static final int TOP_TENANTS = 20;

    private final JdbcClient jdbc;
    private final Clock clock;
    private final TenantTags tags;
    private volatile MultiGauge tenantBacklog;

    public MessagingMetrics(JdbcClient jdbc, Clock clock) {
        this(jdbc, clock, new TenantTags(null));
    }

    public MessagingMetrics(JdbcClient jdbc, Clock clock, TenantTags tags) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.tags = tags;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("astra.outbox.pending", this, m -> m.snapshot("pending"))
                .description("Outbox messages not yet published to Kafka").register(registry);
        Gauge.builder("astra.outbox.oldest.pending", this, m -> m.snapshot("age"))
                .baseUnit("seconds").description("Age of the oldest unpublished outbox message").register(registry);
        tenantBacklog = MultiGauge.builder("astra.outbox.pending.tenant")
                .description("Outbox messages not yet published, per tenant (the largest backlogs by name, the rest as other)")
                .register(registry);
    }

    /** Recomputes the per-tenant backlog gauge. */
    @Scheduled(fixedDelayString = "${astra.metrics.tenant-backlog-interval-ms:30000}")
    public void refreshTenantBacklog() {
        MultiGauge gauge = tenantBacklog;
        if (gauge == null) {
            return;
        }
        try {
            gauge.register(tenantRows(), true);
        } catch (RuntimeException e) {
            // the next refresh tries again; the platform-wide gauges still report
        }
    }

    /** The backlog per tag: the {@value #TOP_TENANTS} largest registered tenants by name, the rest summed as other. */
    List<MultiGauge.Row<?>> tenantRows() {
        Map<String, Double> byTag = new LinkedHashMap<>();
        int named = 0;
        for (Map<String, Object> r : backlogPerTenant()) {
            String tag = tags.of((String) r.get("tenant_id"));
            if (!TenantTags.UNREGISTERED.equals(tag)) {
                if (named < TOP_TENANTS) {
                    named++;
                } else {
                    tag = "other";
                }
            }
            byTag.merge(tag, ((Number) r.get("pending")).doubleValue(), Double::sum);
        }
        return byTag.entrySet().stream()
                .<MultiGauge.Row<?>>map(e -> MultiGauge.Row.of(Tags.of("tenant", e.getKey()), e.getValue()))
                .toList();
    }

    private List<Map<String, Object>> backlogPerTenant() {
        // cross-tenant: the backlog of every tenant, for a platform metric
        return jdbc.sql("select tenant_id, count(*) as pending from outbox where published_at is null "
                        + "group by tenant_id order by pending desc, tenant_id")
                .query().listOfRows();
    }

    private double snapshot(String what) {
        try {
            // cross-tenant: one platform-wide backlog gauge
            Map<String, Object> row = jdbc.sql("select count(*) as pending, min(created_at) as oldest from outbox where published_at is null")
                    .query().singleRow();
            if ("pending".equals(what)) {
                return ((Number) row.get("pending")).doubleValue();
            }
            return row.get("oldest") instanceof Timestamp ts ? Duration.between(ts.toInstant(), clock.instant()).toSeconds() : 0;
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }
}
