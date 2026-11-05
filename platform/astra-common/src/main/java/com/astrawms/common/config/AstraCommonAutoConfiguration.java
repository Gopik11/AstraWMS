package com.astrawms.common.config;

import com.astrawms.common.messaging.EnvelopeCodec;
import com.astrawms.common.messaging.InboxGuard;
import com.astrawms.common.messaging.InboxProcessing;
import com.astrawms.common.messaging.KafkaErrorHandling;
import com.astrawms.common.messaging.MessagingHousekeeping;
import com.astrawms.common.messaging.OutboxRelay;
import com.astrawms.common.messaging.OutboxWriter;
import com.astrawms.common.ops.DeadLetters;
import com.astrawms.common.ops.MessagingMetrics;
import com.astrawms.common.ops.OpsController;
import com.astrawms.common.ops.ReplayRouting;
import com.astrawms.common.tenancy.TenantAwareDataSource;
import com.astrawms.common.web.ProblemHandler;
import java.time.Clock;
import java.time.Duration;
import javax.sql.DataSource;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.RecordInterceptor;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wires the AstraWMS platform conventions into every service that depends on astra-common.
 * Runs after Kafka auto-configuration so that {@code @ConditionalOnBean(KafkaTemplate)} sees the template.
 */
@AutoConfiguration(afterName = "org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration")
@EnableScheduling
public class AstraCommonAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    Clock astraClock() {
        return Clock.systemUTC();
    }

    /** Wraps the application DataSource so every connection carries the tenant for row-level security. */
    @Bean
    static BeanPostProcessor tenantAwareDataSourcePostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof DataSource ds && !(bean instanceof TenantAwareDataSource)) {
                    return new TenantAwareDataSource(ds);
                }
                return bean;
            }
        };
    }

    /**
     * API convention: an omitted optional boolean/number means {@code false}/0 rather than a 400 (Jackson 3 fails
     * on null primitives by default). Required numbers are still enforced by Bean Validation (@Positive etc.).
     */
    @Bean
    JsonMapperBuilderCustomizer astraJsonConventions() {
        return builder -> builder.disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
    }

    @Bean
    ProblemHandler problemHandler() {
        return new ProblemHandler();
    }

    @Bean
    OutboxWriter outboxWriter(JdbcClient jdbc, JsonMapper json, Clock clock) {
        return new OutboxWriter(jdbc, json, clock);
    }

    @Bean
    InboxGuard inboxGuard(JdbcClient jdbc) {
        return new InboxGuard(jdbc);
    }

    /**
     * The tenant registry (ADR-0030 A), read from the compacted tenants topic. {@code astra.tenancy.registry.mode}:
     * ENFORCE (default) refuses unknown, suspended and purged tenants; REPORT only logs and counts, for a first deploy.
     */
    @Bean
    @ConditionalOnBean(ConsumerFactory.class)
    @ConditionalOnMissingBean
    com.astrawms.common.tenancy.TenantRegistry astraTenantRegistry(
            ConsumerFactory<?, ?> consumers, JsonMapper json, @Value("${spring.application.name:service}") String service,
            @Value("${astra.tenancy.registry.mode:ENFORCE}") com.astrawms.common.tenancy.TenantRegistry.Mode mode) {
        return new com.astrawms.common.tenancy.TenantRegistry(consumers, json, service, mode);
    }

    @Bean
    InboxProcessing inboxProcessing(InboxGuard inbox, PlatformTransactionManager txManager,
                                    org.springframework.beans.factory.ObjectProvider<com.astrawms.common.tenancy.TenantRegistry> registry) {
        return new InboxProcessing(inbox, new TransactionTemplate(txManager), registry.getIfAvailable());
    }

    @Bean
    EnvelopeCodec envelopeCodec(JsonMapper json) {
        return new EnvelopeCodec(json);
    }

    /** Retry with backoff, then {@code <topic>.dlq} (KafkaErrorHandling). Boot wires it into every listener container. */
    @Bean
    @ConditionalOnBean(KafkaTemplate.class)
    @ConditionalOnMissingBean(CommonErrorHandler.class)
    CommonErrorHandler astraKafkaErrorHandler(KafkaTemplate<String, String> kafka,
                                              @Value("${astra.kafka.retry.initial-interval-ms:1000}") long initial,
                                              @Value("${astra.kafka.retry.max-interval-ms:30000}") long max,
                                              @Value("${astra.kafka.retry.max-elapsed-ms:300000}") long elapsed) {
        return KafkaErrorHandling.deadLetteringErrorHandler(kafka, initial, max, elapsed);
    }

    @Bean
    @ConditionalOnProperty(name = "astra.housekeeping.enabled", havingValue = "true", matchIfMissing = true)
    MessagingHousekeeping messagingHousekeeping(JdbcClient jdbc, Clock clock,
                                                @Value("${astra.housekeeping.outbox-retention:P7D}") Duration outboxRetention,
                                                @Value("${astra.housekeeping.inbox-retention:P30D}") Duration inboxRetention) {
        return new MessagingHousekeeping(jdbc, clock, outboxRetention, inboxRetention);
    }

    @Bean
    @ConditionalOnBean(KafkaTemplate.class)
    @ConditionalOnProperty(name = "astra.outbox.relay-enabled", havingValue = "true", matchIfMissing = true)
    OutboxRelay outboxRelay(DataSource dataSource, KafkaTemplate<String, String> kafka, Clock clock,
                            ObjectProvider<MeterRegistry> meters,
                            @Value("${astra.outbox.partitions:8}") int partitions,
                            @Value("${astra.outbox.batch-size:200}") int batchSize,
                            @Value("${astra.outbox.send-timeout:PT10S}") Duration sendTimeout,
                            @Value("${astra.outbox.cycle-budget:PT30S}") Duration cycleBudget,
                            @Value("${astra.outbox.breaker-open:PT30S}") Duration breakerOpen,
                            @Value("${astra.outbox.parallelism:2}") int parallelism) {
        return new OutboxRelay(dataSource, kafka, clock,
                new OutboxRelay.Settings(partitions, batchSize, sendTimeout, cycleBudget, breakerOpen, parallelism),
                meters.getIfAvailable());
    }

    /** Delivers a replayed dead letter only to the consumer group that failed it (ADR-0018). */
    @Bean
    @ConditionalOnBean(KafkaTemplate.class)
    @ConditionalOnMissingBean(RecordInterceptor.class)
    RecordInterceptor<Object, Object> astraReplayRouting() {
        return new ReplayRouting();
    }

    @Bean
    @ConditionalOnBean({KafkaTemplate.class, ConsumerFactory.class})
    DeadLetters astraDeadLetters(@Value("${spring.application.name:service}") String service, ConsumerFactory<?, ?> consumers,
                                 KafkaTemplate<String, String> kafka, KafkaListenerEndpointRegistry registry,
                                 JdbcClient jdbc, PlatformTransactionManager txManager, JsonMapper json, Clock clock,
                                 InboxGuard inbox,
                                 @Value("${astra.housekeeping.inbox-retention:P30D}") Duration inboxRetention) {
        return new DeadLetters(service, consumers, kafka, registry, jdbc, new TransactionTemplate(txManager), json, clock,
                inbox, inboxRetention);
    }

    @Bean
    @ConditionalOnBean(DeadLetters.class)
    OpsController astraOpsController(DeadLetters deadLetters, JdbcClient jdbc, Clock clock,
                                     ObjectProvider<OutboxRelay> relay,
                                     ObjectProvider<com.astrawms.common.tenancy.TenantRegistry> registry) {
        return new OpsController(deadLetters, jdbc, clock, relay.getIfAvailable(), registry.getIfAvailable());
    }

    @Bean
    @ConditionalOnClass(name = "io.micrometer.core.instrument.binder.MeterBinder")
    MessagingMetrics astraMessagingMetrics(JdbcClient jdbc, Clock clock) {
        return new MessagingMetrics(jdbc, clock);
    }
}
