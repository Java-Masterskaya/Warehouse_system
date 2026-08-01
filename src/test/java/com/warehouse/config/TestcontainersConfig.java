package com.warehouse.config;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfig {

    @Bean
    @ServiceConnection
    public PostgreSQLContainer<?> postgresContainer() {
        try (PostgreSQLContainer<?> container = new PostgreSQLContainer<>(
                DockerImageName.parse("ghcr.io/dbsystel/postgresql-partman:16")
                        .asCompatibleSubstituteFor("postgres")
        )) {
            container.withInitScript("init.sql");
            return container;
        }
    }
}