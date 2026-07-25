package com.warehouse;

import com.warehouse.entity.Warehouse;
import com.warehouse.repository.WarehouseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.redpanda.RedpandaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Абстрактный базовый класс для интеграционных тестов.
 * Управляет жизненным циклом тестовых контейнеров через static-блок.
 *
 * <p>Контейнеры запускаются один раз при загрузке контекста и живут до завершения JVM
 * (Ryuk автоматически останавливает их после завершения тестов).
 * Порты не меняются между тест-классами, поэтому кэшированный Spring-контекст
 * всегда имеет актуальные адреса.
 */
@AutoConfigureMockMvc
public abstract class AbstractIntegrationTest {

    @Autowired
    protected WarehouseRepository warehouseRepository;

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

    @Autowired
    protected StringRedisTemplate redisTemplate;

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

        // Задаем тестовые лимиты: для тестов удобно использовать ультра-короткие окна (например, 1-2 секунды)
        registry.add("rate-limiting.login.ip.capacity", () -> 10);
        registry.add("rate-limiting.login.ip.refill-tokens", () -> 10);
        registry.add("rate-limiting.login.ip.duration", () -> "2s");

        registry.add("rate-limiting.login.username.capacity", () -> 2);
        registry.add("rate-limiting.login.username.refill-tokens", () -> 2);
        registry.add("rate-limiting.login.username.duration", () -> "2s");

        registry.add("rate-limiting.movements.ip.capacity", () -> 3);
        registry.add("rate-limiting.movements.ip.refill-tokens", () -> 3);
        registry.add("rate-limiting.movements.ip.duration", () -> "2s");
    }

    protected static RedpandaContainer getRedpanda() {
        return redpanda;
    }

    protected Warehouse defaultWarehouse() {
        return warehouseRepository.findByDefaultWarehouseTrue()
                .orElseThrow(() -> new IllegalStateException("Default warehouse is not configured"));
    }

    @BeforeEach
    void clearRateLimitKeys() {
        // Очищаем Redis ключи rate limiting перед каждым тестом
        // Используем keys() для получения всех ключей с префиксом rl: и удаляем их
        java.util.Set<String> keys = redisTemplate.keys("rl:*");
        if (keys != null) {
            redisTemplate.delete(keys);
        }
    }
}