package com.astrawms.task.config;

import com.astrawms.common.security.ServiceCallInterceptor;
import com.astrawms.task.inventory.HttpInventoryClient;
import com.astrawms.task.inventory.InventoryClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
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
}
