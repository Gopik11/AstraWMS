package com.astrawms.sapadapter.config;

import com.astrawms.common.contracts.IntegrationContracts;
import com.astrawms.sapadapter.sap.MockSapGateway;
import com.astrawms.sapadapter.sap.SapGateway;
import java.time.Clock;
import org.apache.kafka.clients.admin.NewTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.backoff.ExponentialBackOff;
import tools.jackson.databind.json.JsonMapper;

@Configuration
@EnableConfigurationProperties(SapProperties.class)
public class AdapterConfig {

    private static final Logger log = LoggerFactory.getLogger(AdapterConfig.class);

    @Bean
    TransactionTemplate transactionTemplate(PlatformTransactionManager txManager) {
        return new TransactionTemplate(txManager);
    }

    @Bean
    @ConditionalOnProperty(name = "astra.sap.gateway", havingValue = "mock", matchIfMissing = true)
    SapGateway mockSapGateway(JdbcClient jdbc, JsonMapper json, Clock clock) {
        log.warn("Using MockSapGateway: postings go to a simulated SAP backend, not to a real SAP system");
        return new MockSapGateway(jdbc, json, clock);
    }

    /**
     * Transient SAP failures (locks, communication) are retried with exponential backoff (1 s → 30 s, ~5 min in
     * total, ISD-00 §6). Exhausted messages are logged and skipped. Parking them in a dead-letter topic is the next
     * platform increment; until then the reconciliation run (IF-INV-003) detects unposted confirmations.
     */
    @Bean
    CommonErrorHandler kafkaErrorHandler() {
        ExponentialBackOff backOff = new ExponentialBackOff(1_000, 2.0);
        backOff.setMaxInterval(30_000);
        backOff.setMaxElapsedTime(300_000);
        return new DefaultErrorHandler((record, e) ->
                log.error("Giving up on {} offset {} after retries: {}", record.topic(), record.offset(), e.getMessage()),
                backOff);
    }

    @Bean
    NewTopic confirmationsTopic() {
        return TopicBuilder.name(IntegrationContracts.TOPIC_RECEIPT_CONFIRMATIONS).partitions(6).replicas(1).build();
    }

    @Bean
    NewTopic goodsMovementsTopic() {
        return TopicBuilder.name(IntegrationContracts.TOPIC_GOODS_MOVEMENTS).partitions(6).replicas(1).build();
    }

    @Bean
    NewTopic acksTopic() {
        return TopicBuilder.name(IntegrationContracts.TOPIC_APPLICATION_ACKS).partitions(6).replicas(1).build();
    }

    @Bean
    NewTopic expectationsTopic() {
        return TopicBuilder.name(IntegrationContracts.TOPIC_RECEIPT_EXPECTATIONS).partitions(6).replicas(1).build();
    }

    @Bean
    NewTopic postingResultsTopic() {
        return TopicBuilder.name(IntegrationContracts.TOPIC_ERP_POSTING_RESULTS).partitions(6).replicas(1).build();
    }
}
