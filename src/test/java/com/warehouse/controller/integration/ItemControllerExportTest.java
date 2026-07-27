package com.warehouse.controller.integration;

import com.warehouse.AbstractIntegrationTest;
import com.warehouse.service.import_export.CsvExportService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.Writer;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class ItemControllerExportTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CsvExportService csvExportService;

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Экспорт доступен для ADMIN и возвращает асинхронный стрим CSV")
    void exportItemsWhenAdminShouldReturnCsvStream() throws Exception {
        // Настраиваем фейковый вывод в сервис
        Mockito.doAnswer(invocation -> {
            Writer writer = invocation.getArgument(0);
            writer.write("\uFEFFSKU,Name\nSKU-100,Товар 1");
            return null;
        }).when(csvExportService).exportItems(Mockito.any(Writer.class));

        // 1. Выполняем асинхронный запрос
        MvcResult mvcResult = mockMvc.perform(get("/api/items/export")).andExpect(
                                             request().asyncStarted()) // Проверяем, что запустился асинхронный стриминг
                                     .andReturn();

        // 2. Дожидаемся завершения асинхронного потока и проверяем результат
        mockMvc.perform(asyncDispatch(mvcResult)).andExpect(status().isOk())
               .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8"))
               .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"items.csv\""))
               .andExpect(content().string("\uFEFFSKU,Name\nSKU-100,Товар 1"));
    }

    @Test
    @WithMockUser(roles = "USER")
    @DisplayName("Экспорт запрещен для обычного пользователя (USER) — 403 Forbidden")
    void exportItemsWhenUserShouldReturnForbidden() throws Exception {
        mockMvc.perform(get("/api/items/export")).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Экспорт запрещен без аутентификации — 401 Unauthorized")
    void exportItemsWhenAnonymousShouldReturnUnauthorized() throws Exception {
        mockMvc.perform(get("/api/items/export")).andExpect(status().isUnauthorized());
    }
}
