package com.astrawms.common.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import org.apache.kafka.clients.consumer.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * A registry that cannot load (here: the tenants topic is not visible) says so at WARN and on its gauge, instead of
 * retrying quietly while ENFORCE refuses every tenant (found on the VPS: nothing at INFO or WARN told it apart).
 */
class TenantRegistryTest {

    final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    final Logger logger = (Logger) LoggerFactory.getLogger(TenantRegistry.class);
    TenantRegistry registry;

    @BeforeEach
    void setUp() {
        Metrics.addRegistry(meters);
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        if (registry != null) {
            registry.stop();
        }
        logger.detachAppender(logs);
        Metrics.removeRegistry(meters);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aRegistryThatCannotFindItsTopicWarnsAndReportsNotLoaded() throws Exception {
        Consumer<String, String> consumer = mock(Consumer.class);
        when(consumer.partitionsFor(anyString(), any(Duration.class))).thenReturn(List.of());
        ConsumerFactory<String, String> factory = mock(ConsumerFactory.class);
        when(factory.createConsumer(isNull(), anyString(), isNull(), any(Properties.class))).thenReturn(consumer);

        registry = new TenantRegistry(factory, JsonMapper.builder().build(), "test-service", TenantRegistry.Mode.ENFORCE);
        registry.slowAfter(Duration.ZERO);
        registry.start();

        Instant deadline = Instant.now().plusSeconds(10);
        while (warnings().isEmpty() && Instant.now().isBefore(deadline)) {
            Thread.sleep(50);
        }
        assertThat(warnings()).singleElement().satisfies(m -> assertThat(m)
                .contains("Tenant registry not loaded").contains("the topic was not found")
                .contains("refused with 503").contains("READ and DESCRIBE"));
        assertThat(registry.loaded()).isFalse();
        assertThat(meters.get("astra.tenant.registry.loaded").gauge().value()).isZero();
    }

    private List<String> warnings() {
        return logs.list.stream().filter(e -> e.getFormattedMessage().startsWith("Tenant registry not loaded"))
                .map(ILoggingEvent::getFormattedMessage).toList();
    }
}
