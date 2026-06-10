package com.warehouse;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

// @AutoConfigureMockMvc нужен, чтобы этот класс использовал тот же Spring-контекст,
// что ItemControllerTest и AuthControllerTest. Без него создаётся отдельный контекст,
// JUnit 5 останавливает контейнеры между классами, и кэшированный контекст теряет соединение с БД.
@AutoConfigureMockMvc
class WarehouseAppContextTest extends AbstractIntegrationTest {

    @Test
    void contextLoads() {
    }
}