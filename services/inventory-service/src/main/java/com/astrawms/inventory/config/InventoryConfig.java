package com.astrawms.inventory.config;

import com.astrawms.inventory.events.InventoryEvents.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
@EnableConfigurationProperties(Topics.class)
public class InventoryConfig {

    @Bean
    TransactionTemplate transactionTemplate(PlatformTransactionManager txManager) {
        return new TransactionTemplate(txManager);
    }

    /** ADR-0025: what open orders and transfers hold, for store replenishment. */
    @Bean
    com.astrawms.inventory.outbound.OutboundClient outboundClient(com.astrawms.common.security.ServiceCallInterceptor serviceCalls,
            @org.springframework.beans.factory.annotation.Value("${astra.outbound.base-url}") String baseUrl,
            @org.springframework.beans.factory.annotation.Value("${astra.outbound.timeout-ms:3000}") long timeoutMs) {
        org.springframework.http.client.SimpleClientHttpRequestFactory factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(java.time.Duration.ofMillis(timeoutMs));
        factory.setReadTimeout(java.time.Duration.ofMillis(timeoutMs));
        return new com.astrawms.inventory.outbound.HttpOutboundClient(org.springframework.web.client.RestClient.builder()
                .baseUrl(baseUrl).requestFactory(factory).requestInterceptor(serviceCalls).build());
    }

    // Topics are declared for local/dev environments; production topics are provisioned by infrastructure.
    @Bean
    NewTopic inventoryEventsTopic(Topics topics) {
        return TopicBuilder.name(topics.inventoryEvents()).partitions(12).replicas(1).build();
    }

    @Bean
    NewTopic goodsMovementTopic(Topics topics) {
        return TopicBuilder.name(topics.goodsMovements()).partitions(6).replicas(1).build();
    }

    @Bean
    NewTopic masterdataEventsTopic(Topics topics) {
        return TopicBuilder.name(topics.masterdataEvents()).partitions(6).replicas(1).build();
    }
}
