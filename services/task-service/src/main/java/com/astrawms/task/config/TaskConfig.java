package com.astrawms.task.config;

import com.astrawms.common.contracts.InventoryContracts;
import com.astrawms.common.contracts.MasterDataEvents;
import com.astrawms.common.contracts.OutboundContracts;
import com.astrawms.common.security.ServiceCallInterceptor;
import com.astrawms.task.inventory.HttpInventoryClient;
import com.astrawms.task.inventory.InventoryClient;
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
public class TaskConfig {

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

    // Every service declares the topics it consumes as well as those it produces: if a consumer subscribed first,
    // the broker would auto-create the topic with one partition, and the consumer would not see the partitions added
    // later until its next metadata refresh (minutes). Partition counts match the producers' declarations.
    // Production topics are provisioned by infrastructure.

    @Bean
    NewTopic masterDataEventsTopic() {
        return TopicBuilder.name(MasterDataEvents.TOPIC).partitions(6).replicas(1).build();
    }

    @Bean
    NewTopic inventoryEventsTopic() {
        return TopicBuilder.name(InventoryContracts.TOPIC).partitions(12).replicas(1).build();
    }

    @Bean
    NewTopic taskRequestsTopic() {
        return TopicBuilder.name(OutboundContracts.TOPIC_TASK_REQUESTS).partitions(6).replicas(1).build();
    }

    @Bean
    NewTopic taskEventsTopic() {
        return TopicBuilder.name(OutboundContracts.TOPIC_TASK_EVENTS).partitions(6).replicas(1).build();
    }
}
