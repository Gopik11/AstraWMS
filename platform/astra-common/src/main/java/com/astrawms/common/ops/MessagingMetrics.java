package com.astrawms.common.ops;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Messaging health as Prometheus metrics, across tenants (scraped on the private network only, ADR-0018):
 * <ul>
 *   <li>{@code astra_outbox_pending}: rows not yet published to Kafka;</li>
 *   <li>{@code astra_outbox_oldest_pending_seconds}: age of the oldest one (alert when it keeps growing);</li>
 *   <li>{@code astra_kafka_dead_lettered_total} (counter, by topic and group) is incremented by KafkaErrorHandling.</li>
 * </ul>
 */
public class MessagingMetrics implements MeterBinder {

    private final JdbcClient jdbc;
    private final Clock clock;

    public MessagingMetrics(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("astra.outbox.pending", this, m -> m.snapshot("pending"))
                .description("Outbox messages not yet published to Kafka").register(registry);
        Gauge.builder("astra.outbox.oldest.pending", this, m -> m.snapshot("age"))
                .baseUnit("seconds").description("Age of the oldest unpublished outbox message").register(registry);
    }

    private double snapshot(String what) {
        try {
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
