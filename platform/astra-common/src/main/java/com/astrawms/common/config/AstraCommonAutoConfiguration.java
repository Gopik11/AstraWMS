package com.astrawms.common.config;

import com.astrawms.common.messaging.EnvelopeCodec;
import com.astrawms.common.messaging.InboxGuard;
import com.astrawms.common.messaging.KafkaErrorHandling;
import com.astrawms.common.messaging.MessagingHousekeeping;
import com.astrawms.common.messaging.OutboxRelay;
import com.astrawms.common.messaging.OutboxWriter;
import com.astrawms.common.tenancy.TenantAwareDataSource;
import com.astrawms.common.web.ProblemHandler;
import java.time.Clock;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
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
    OutboxRelay outboxRelay(JdbcClient jdbc, PlatformTransactionManager txManager, KafkaTemplate<String, String> kafka,
                            Clock clock) {
        return new OutboxRelay(jdbc, new TransactionTemplate(txManager), kafka, clock, 200, Duration.ofSeconds(10));
    }
}
