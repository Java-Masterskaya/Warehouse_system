package com.warehouse.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.warehouse.AbstractIntegrationTest;
import com.warehouse.test.snapshot.OpenApiSnapshotSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Фиксирует OpenAPI-спеку как контракт: сравнивает то, что реально генерирует
 * springdoc из аннотированных контроллеров/DTO ({@code GET /v3/api-docs}), с версией,
 * зафиксированной в репозитории ({@code src/test/resources/openapi/warehouse.json}).
 *
 * <p>Источник правды остаётся code-first (спека выводится из кода), а не наоборот:
 * контроллеры и DTO по-прежнему описывают API, а зафиксированный JSON-файл — это снятый
 * с них слепок. Любое расхождение (убрали/переименовали поле, изменили статус ответа,
 * поменяли тип параметра) ломает сборку до тех пор, пока изменение не будет подтверждено
 * осознанным обновлением снапшота — см. {@link OpenApiSnapshotSupport#UPDATE_SNAPSHOT_PROPERTY}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiContractDriftTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DirtiesContext
    void generatedSpecMatchesCommittedContract() throws Exception {
        JsonNode liveSpec = OpenApiSnapshotSupport.fetchLiveSpec(mockMvc, objectMapper);
        OpenApiSnapshotSupport.assertNoContractDrift(liveSpec, objectMapper);
    }
}
