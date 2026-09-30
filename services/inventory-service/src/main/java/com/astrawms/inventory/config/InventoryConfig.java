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
