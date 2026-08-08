package com.warehouse;

import com.warehouse.entity.Warehouse;
import com.warehouse.repository.WarehouseRepository;
import com.warehouse.web.ApiPaths;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.redpanda.RedpandaContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Абстрактный базовый класс для интеграционных тестов.
 * Управляет жизненным циклом тестовых контейнеров через static-блок.
 *
 * <p>Контейнеры запускаются один раз при загрузке класса и живут до завершения JVM.
 * Порты не меняются между тест-классами, поэтому кэшированный Spring-контекст
 * всегда имеет актуальные адреса.
 *
 * <p>Включён {@code withReuse(true)}: контейнеры переживают и сам прогон, если разработчик
 * согласился на это у себя в {@code ~/.testcontainers.properties}:
 *
 * <pre>testcontainers.reuse.enable=true</pre>
 *
 * <p>Без этой строки флаг игнорируется и всё работает как раньше — Ryuk убирает контейнеры
 * после прогона. Поэтому на CI поведение не меняется, а локально повторный запуск
 * экономит время подъёма Postgres, Redpanda и Redis.
 *
 * <p>При включённом переиспользовании данные переживают прогон, так что рассчитывать
 * на чистую базу нельзя: каждый класс приводит её в нужное состояние сам
 * (см. {@link #cleanDomainData()}).
 *
 * <p>{@code @ResourceLock} сериализует наследников между собой при параллельном прогоне.
 * Postgres, Redis и Kafka общие на весь прогон, и каждый класс вычищает их в
 * {@code @BeforeEach} — два таких класса рядом затёрли бы данные друг друга посреди теста.
 * Классы, которым Spring-контекст не нужен, замок не берут и идут параллельно.
 */
@AutoConfigureMockMvc
@ResourceLock(AbstractIntegrationTest.SHARED_INFRASTRUCTURE)
public abstract class AbstractIntegrationTest {

    /** Имя общего ресурса: база, кэш и брокер, поднятые один раз на весь прогон. */
    public static final String SHARED_INFRASTRUCTURE = "warehouse-shared-infrastructure";

    /**
     * Учётки из миграций: {@code admin} — V5, {@code system-batch-cleanup} — V29.
     * Всё остальное в {@code users} создано тестами и подлежит удалению.
     */
    private static final List<String> SEEDED_USERNAMES =
            List.of("admin", "system-batch-cleanup");

    protected static final String V1_API_ROOT = ApiPaths.V1_API_ROOT;
    protected static final String V1_BACKFILL_ROOT = ApiPaths.V1_BACKFILL_ROOT;

    @Autowired
    protected WarehouseRepository warehouseRepository;

    @Autowired
    protected JdbcTemplate testJdbcTemplate;

    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withReuse(true);

    static final RedpandaContainer redpanda =
            new RedpandaContainer(DockerImageName.parse("docker.redpanda.com/redpandadata/redpanda:v23.2.11"))
                    .withReuse(true);

    @SuppressWarnings("resource")
    static final GenericContainer<?> redis =
            new GenericContainer<>("redis:7-alpine")
                    .withExposedPorts(6379)
                    .withReuse(true);

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

    /**
     * Общий Postgres для тестов, которым нужна база, но не нужен Spring-контекст.
     *
     * @return контейнер, поднятый один раз на весь прогон
     */
    public static PostgreSQLContainer<?> getPostgres() {
        return postgres;
    }

    /**
     * Удаляет доменные данные в порядке, безопасном по внешним ключам.
     *
     * <p>База у интеграционных тестов общая — контейнер поднимается один раз на весь прогон.
     * Класс, который чистит лишь часть графа, падает на внешнем ключе, как только сосед
     * оставил после себя ссылающиеся строки.
     *
     * <p>Порядок получен опытным путём: каждая таблица добавлена сюда потому, что её
     * отсутствие роняло конкретный тест. Сверху те, кто ссылается, снизу те, на кого ссылаются.
     *
     * <p>{@code warehouses} не трогаем — склады засеяны миграциями и общие для всех.
     * В {@code users} остаются только засеянные учётки, всё созданное тестами удаляется:
     * см. {@link #SEEDED_USERNAMES}.
     */
    protected void cleanDomainData() {
        testJdbcTemplate.update("DELETE FROM stock_alerts");
        testJdbcTemplate.update("DELETE FROM reserves");
        testJdbcTemplate.update("DELETE FROM purchase_order_items");
        testJdbcTemplate.update("DELETE FROM purchase_orders");
        testJdbcTemplate.update("DELETE FROM stock_movements");
        testJdbcTemplate.update("DELETE FROM batches");
        testJdbcTemplate.update("DELETE FROM stock");
        testJdbcTemplate.update("DELETE FROM items");
        testJdbcTemplate.update("DELETE FROM categories");
        cleanTestUsers();
    }

    /**
     * Удаляет учётки, созданные тестами, оставляя засеянные миграциями.
     *
     * <p>Раньше {@code users} не чистил никто: таблица считалась общей, потому что в ней
     * сидят учётки из миграций. В итоге строки с уникальными именами вида
     * {@code atomic-test-<nanoTime>} копились между прогонами без ограничений —
     * при переиспользовании контейнеров таблица росла бесконечно.
     *
     * <p>{@code idempotency_keys} удаляются здесь же: это третий внешний ключ на
     * {@code users} помимо {@code reserves} и {@code stock_movements}, которые сняты выше.
     */
    private void cleanTestUsers() {
        testJdbcTemplate.update("DELETE FROM idempotency_keys");
        String placeholders = String.join(", ", Collections.nCopies(SEEDED_USERNAMES.size(), "?"));
        testJdbcTemplate.update(
                "DELETE FROM users WHERE username NOT IN (" + placeholders + ")",
                SEEDED_USERNAMES.toArray());
    }

    protected Warehouse defaultWarehouse() {
        return warehouseRepository.findByDefaultWarehouseTrue()
                .orElseThrow(() -> new IllegalStateException("Default warehouse is not configured"));
    }

    /**
     * Сбрасывает состояние Redis перед каждым тестом — прежде всего корзины rate limiting.
     *
     * <p>Раньше здесь удалялись ключи по шаблону {@code rl:*} через {@code StringRedisTemplate}.
     * Корзины пишет bucket4j отдельным соединением с {@code ByteArrayCodec}, и выборка
     * их не находила: лимит логина по IP (10 попыток на 2 секунды) копился через весь прогон.
     * Пока тесты были медленными, это не проявлялось; после ускорения классы, где
     * каждый тест логинится, стали упираться в лимит и получать 429 вместо 200.
     *
     * <p>{@code flushDb} чистит независимо от того, как и чем ключ сериализован.
     * {@code RateLimitIntegrationTest} давно делает ровно это и работает стабильно.
     */
    @BeforeEach
    void clearRateLimitKeys() {
        Objects.requireNonNull(redisTemplate.getConnectionFactory())
                .getConnection()
                .serverCommands()
                .flushDb();
    }
}
