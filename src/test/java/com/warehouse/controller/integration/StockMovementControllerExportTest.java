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
public class StockMovementControllerExportTest extends AbstractIntegrationTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CsvExportService csvExportService;

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Экспорт движений доступен для ADMIN и возвращает файл movements.csv")
    void exportMovementsWhenAdminShouldReturnCsvStream() throws Exception {
        // Симулируем запись данных сервисом во Writer
        Mockito.doAnswer(invocation -> {
            Writer writer = invocation.getArgument(0);
            writer.write("\uFEFFItem_sku,Item_name,Warehouse,Movement_type,Quantity,Creator,Created_at,Transfer_id\n");
            writer.write("SKU-001,Товар 1,Основной,INCOMING,10,admin,2026-07-23T10:00,\n");
            return null;
        }).when(csvExportService).exportMovement(Mockito.any(Writer.class));

        // 1. Старт асинхронного запроса
        MvcResult mvcResult =
                mockMvc.perform(get("/api/movements/export")).andExpect(request().asyncStarted()).andReturn();

        // 2. Дожидаемся ответа и проверяем заголовки + тело
        mockMvc.perform(asyncDispatch(mvcResult)).andExpect(status().isOk())
               .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8"))
               .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"movements.csv\""))
               .andExpect(content().string(
                       "\uFEFFItem_sku,Item_name,Warehouse,Movement_type,Quantity,Creator,Created_at,Transfer_id\n"
                               + "SKU-001,Товар 1,Основной,INCOMING,10,admin,2026-07-23T10:00,\n"));
    }

    @Test
    @WithMockUser(roles = "USER")
    @DisplayName("Экспорт движений запрещен обычным пользователям — 403 Forbidden")
    void exportMovementsWhenUserShouldReturnForbidden() throws Exception {
        mockMvc.perform(get("/api/movements/export")).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Экспорт движений запрещен неавторизованным — 401 Unauthorized")
    void exportMovementsWhenAnonymousShouldReturnUnauthorized() throws Exception {
        mockMvc.perform(get("/api/movements/export")).andExpect(status().isUnauthorized());
    }
}
