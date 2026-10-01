package com.astrawms.outbound.config;

import com.astrawms.common.contracts.OutboundContracts;
import com.astrawms.common.security.ServiceCallInterceptor;
import com.astrawms.outbound.inventory.HttpInventoryClient;
import com.astrawms.outbound.inventory.InventoryClient;
import java.time.Duration;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

@Configuration
public class OutboundConfig {

    @Bean
    TransactionTemplate transactionTemplate(PlatformTransactionManager txManager) {
        return new TransactionTemplate(txManager);
    }

    @Bean
    InventoryClient inventoryClient(JsonMapper json, ServiceCallInterceptor serviceCalls, @Value("${astra.inventory.base-url}") String baseUrl,
                                    @Value("${astra.inventory.timeout-ms:3000}") long timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(timeoutMs));
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));
        return new HttpInventoryClient(RestClient.builder().baseUrl(baseUrl).requestFactory(factory)
                .requestInterceptor(serviceCalls).build(), json);
    }

    // Declared for local/dev environments; production topics are provisioned by infrastructure.
    @Bean
    NewTopic outboundOrdersTopic() {
        return TopicBuilder.name(OutboundContracts.TOPIC_OUTBOUND_ORDERS).partitions(6).replicas(1).build();
    }

    @Bean
    NewTopic shipmentConfirmationsTopic() {
        return TopicBuilder.name(OutboundContracts.TOPIC_SHIPMENT_CONFIRMATIONS).partitions(6).replicas(1).build();
    }

    @Bean
    NewTopic taskRequestsTopic() {
        return TopicBuilder.name(OutboundContracts.TOPIC_TASK_REQUESTS).partitions(6).replicas(1).build();
    }

    @Bean
    NewTopic taskEventsTopic() {
        return TopicBuilder.name(OutboundContracts.TOPIC_TASK_EVENTS).partitions(6).replicas(1).build();
    }

    /** Consumed here; declared so that start order cannot leave it with one auto-created partition. */
    @Bean
    NewTopic erpPostingResultsTopic() {
        return TopicBuilder.name(com.astrawms.common.contracts.IntegrationContracts.TOPIC_ERP_POSTING_RESULTS)
                .partitions(6).replicas(1).build();
    }
}
