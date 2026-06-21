package com.warehouse;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.redpanda.RedpandaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Абстрактный интеграционный тест для запуска контейнеров PostgreSQL, Redis и Redpanda через Testcontainers.
 * Контейнеры запускаются один раз в static-блоке и живут до завершения JVM.
 * Используется как база для всех интеграционных тестов в проекте.
 */
@SpringBootTest
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    static final RedpandaContainer redpanda =
            new RedpandaContainer(DockerImageName.parse("docker.redpanda.com/redpandadata/redpanda:v23.2.11"));

    @SuppressWarnings("resource")
    static final GenericContainer<?> redis =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static {
        postgres.start();
        redpanda.start();
        redis.start();
    }

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.kafka.bootstrap-servers", redpanda::getBootstrapServers);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
        registry.add("spring.data.redis.password", () -> "");
    }
}
