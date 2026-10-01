package com.astrawms.test;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * One Postgres 16 and one Kafka container per test JVM, shared by all integration tests of a service.
 *
 * <p>The application connects as {@code astra_app} (non-owner, NOBYPASSRLS) created by the init script, while Flyway
 * migrates as the container superuser. Superusers and owners bypass row-level security, so tests connecting as the
 * owner would silently skip tenant isolation (ADR-0003).
 */
public final class AstraContainers {

    public static final String APP_USER = "astra_app";
    public static final String APP_PASSWORD = "astra_app_test";

    public static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withInitScript("astra-test/init-roles.sql");
    public static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka-native:3.8.0");

    static {
        POSTGRES.start();
        KAFKA.start();
    }

    private AstraContainers() {
    }

    /** Call from a {@code @DynamicPropertySource} method. */
    public static void register(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", () -> APP_USER);
        r.add("spring.datasource.password", () -> APP_PASSWORD);
        r.add("spring.flyway.user", POSTGRES::getUsername);
        r.add("spring.flyway.password", POSTGRES::getPassword);
        r.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }
}
