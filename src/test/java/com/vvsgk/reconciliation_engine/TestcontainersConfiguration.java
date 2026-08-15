package com.vvsgk.reconciliation_engine;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;

@TestConfiguration
public class TestcontainersConfiguration {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("reconciliation_test")
            .withUsername("test_user")
            .withPassword("test_password");

    static {
        postgres.start();
    }

    @Bean
    public PostgreSQLContainer<?> postgresContainer() {
        return postgres;
    }
}
