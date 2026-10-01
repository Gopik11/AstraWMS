package com.astrawms.inbound.config;

import com.astrawms.common.contracts.IntegrationContracts;
import com.astrawms.common.security.ServiceCallInterceptor;
import com.astrawms.inbound.inventory.HttpInventoryClient;
import com.astrawms.inbound.inventory.InventoryClient;
import java.time.Duration;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

@Configuration
public class InboundConfig {

    @Bean
    TransactionTemplate transactionTemplate(PlatformTransactionManager txManager) {
        return new TransactionTemplate(txManager);
    }

    /**
     * Inventory calls happen inside the receiving transaction, so the timeout bounds how long row locks are held.
     * The RF client retries with the same Idempotency-Key on 503.
     */
    @Bean
    @ConditionalOnMissingBean(InventoryClient.class)
    InventoryClient inventoryClient(JsonMapper json, ServiceCallInterceptor serviceCalls,
                                    @Value("${astra.inventory.base-url}") String baseUrl,
                                    @Value("${astra.inventory.timeout-ms:3000}") long timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(timeoutMs));
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));
        return new HttpInventoryClient(RestClient.builder().baseUrl(baseUrl).requestFactory(factory)
                .requestInterceptor(serviceCalls).build(), json);
    }

    // Declared for local/dev environments; production topics are provisioned by infrastructure.
    @Bean
    NewTopic receiptExpectationsTopic() {
        return TopicBuilder.name(IntegrationContracts.TOPIC_RECEIPT_EXPECTATIONS).partitions(6).replicas(1).build();
    }

    @Bean
    NewTopic receiptConfirmationsTopic() {
        return TopicBuilder.name(IntegrationContracts.TOPIC_RECEIPT_CONFIRMATIONS).partitions(6).replicas(1).build();
    }

    @Bean
    NewTopic postingResultsTopic() {
        return TopicBuilder.name(IntegrationContracts.TOPIC_ERP_POSTING_RESULTS).partitions(6).replicas(1).build();
    }

    @Bean
    NewTopic acksTopic() {
        return TopicBuilder.name(IntegrationContracts.TOPIC_APPLICATION_ACKS).partitions(6).replicas(1).build();
    }
}
