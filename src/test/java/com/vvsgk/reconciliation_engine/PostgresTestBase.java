package com.vvsgk.reconciliation_engine;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest
@ActiveProfiles("test")
public abstract class PostgresTestBase {

    private static final PostgreSQLContainer<?> POSTGRES;

    static {
        POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
                .withDatabaseName("reconciliation_test")
                .withUsername("test_user")
                .withPassword("test_password");

        POSTGRES.start();

        Runtime.getRuntime().addShutdownHook(
                new Thread(POSTGRES::stop)
        );
    }

    @DynamicPropertySource
    static void registerPostgresProperties(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.datasource.url",
                POSTGRES::getJdbcUrl
        );

        registry.add(
                "spring.datasource.username",
                POSTGRES::getUsername
        );

        registry.add(
                "spring.datasource.password",
                POSTGRES::getPassword
        );

        registry.add(
                "spring.datasource.driver-class-name",
                POSTGRES::getDriverClassName
        );

        registry.add(
                "spring.jpa.hibernate.ddl-auto",
                () -> "create-drop"
        );
    }
}