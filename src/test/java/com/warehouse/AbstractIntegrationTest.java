package com.warehouse;

import com.warehouse.entity.Warehouse;
import com.warehouse.postgres.PostgresTestImage;
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
import java.time.Duration;

import static org.testcontainers.shaded.org.awaitility.Awaitility.await;

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

    /**
     * Контейнеры разнесены по отдельным холдерам: каждый поднимается только при обращении
     * именно к нему, и тесту, которому нужна одна база, не приходится платить за Redpanda
     * и Redis. Классам со Spring-контекстом это ничего не меняет — {@link #configure}
     * трогает все три.
     */
    private static final class PostgresHolder {
        static final PostgreSQLContainer<?> INSTANCE =
                new PostgreSQLContainer<>(PostgresTestImage.IMAGE)
                        .withDatabaseName("warehouse")
                        .withUsername("postgres")
                        .withPassword("postgres")
                        .withReuse(true)
                        .withInitScript("init.sql")
                        .withCommand(
                                "postgres",
                                "-c", "shared_preload_libraries=pg_partman_bgw,pg_cron",
                                "-c", "cron.database_name=warehouse"
                        );
        static {
            INSTANCE.start();
        }
    }

    private static final class RedpandaHolder {
        static final RedpandaContainer INSTANCE =
                new RedpandaContainer(DockerImageName.parse("docker.redpanda.com/redpandadata/redpanda:v23.2.11"))
                        .withReuse(true);

        static {
            INSTANCE.start();
        }
    }

    private static final class RedisHolder {
        @SuppressWarnings("resource")
        static final GenericContainer<?> INSTANCE =
                new GenericContainer<>("redis:7-alpine")
                        .withExposedPorts(6379)
                        .withReuse(true);


        static {
            INSTANCE.start();
        }
    }

    @Autowired
    protected StringRedisTemplate redisTemplate;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresHolder.INSTANCE::getJdbcUrl);
        registry.add("spring.datasource.username", PostgresHolder.INSTANCE::getUsername);
        registry.add("spring.datasource.password", PostgresHolder.INSTANCE::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.kafka.bootstrap-servers", RedpandaHolder.INSTANCE::getBootstrapServers);
        registry.add("spring.data.redis.host", RedisHolder.INSTANCE::getHost);
        registry.add("spring.data.redis.port", RedisHolder.INSTANCE::getFirstMappedPort);
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
        return RedpandaHolder.INSTANCE;
    }

    /**
     * Общий Postgres для тестов, которым нужна база, но не нужен Spring-контекст.
     *
     * @return контейнер, поднятый один раз на весь прогон
     */
    public static PostgreSQLContainer<?> getPostgres() {
        return PostgresHolder.INSTANCE;
    }

    /**
     * Удаляет доменные данные в порядке, безопасном по внешним ключам.
     *
     * <p>База у интеграционных тестов общая — контейнер поднимается один раз на весь прогон.
     * Класс, который чистит лишь часть графа, падает на внешнем ключе, как только сосед
     * оставил после себя ссылающиеся строки.
     *
     * <p>Каждая таблица в списке обязательна: без неё падает хотя бы один тест.
     * Сверху те, кто ссылается, снизу те, на кого ссылаются.
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
     * <p>Без этого таблица растёт бесконечно: тесты дают учёткам уникальные имена вида
     * {@code atomic-test-<nanoTime>}, а при переиспользовании контейнеров строки
     * переживают прогон.
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
     * <p>Не удаляем ключи по шаблону {@code rl:*} через {@code StringRedisTemplate}: корзины
     * пишет bucket4j отдельным соединением с {@code ByteArrayCodec}, и такая выборка их
     * не находит. Лимит логина по IP тогда копится через весь прогон, и классы, где каждый
     * тест логинится, получают 429 вместо 200.
     *
     * <p>{@code flushDb} чистит независимо от того, как и чем ключ сериализован.
     */
    @BeforeEach
    void flushRedisBeforeTest() {
        Objects.requireNonNull(redisTemplate.getConnectionFactory())
                .getConnection()
                .serverCommands()
                .flushDb();
    }

    @BeforeEach
    void waitForKafka() {
        await().pollDelay(Duration.ofSeconds(1))
                .pollInterval(Duration.ofSeconds(1))
                .atMost(Duration.ofSeconds(10))
                .until(() -> {
                    return true;
                });
    }

}
