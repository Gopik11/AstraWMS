package com.astrawms.masterdata.config;

import com.astrawms.common.contracts.MasterDataEvents;
import com.astrawms.common.contracts.PlatformContracts;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class MasterDataConfig {

    // Declared for local/dev environments; production topics are provisioned by infrastructure.
    @Bean
    NewTopic masterdataEventsTopic() {
        return TopicBuilder.name(MasterDataEvents.TOPIC).partitions(6).replicas(1).build();
    }

    /** The tenant registry (ADR-0030 A): compacted, so the latest state of every tenant stays for good. */
    @Bean
    NewTopic tenantsTopic() {
        return TopicBuilder.name(PlatformContracts.TOPIC_TENANTS).partitions(3).replicas(1).compact().build();
    }
}
