package com.warehouse.controller.integration;

import com.warehouse.AbstractIntegrationTest;
import com.warehouse.entity.Category;
import com.warehouse.repository.CategoryRepository;
import com.warehouse.service.import_export.CsvExportService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.Writer;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ItemControllerExportTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private CsvExportService csvExportService;

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Экспорт доступен для ADMIN и возвращает асинхронный стрим CSV")
    void exportItemsWhenAdminShouldReturnCsvStream() throws Exception {
        fillDb(1);
        // 1. Выполняем асинхронный запрос
        MvcResult mvcResult = mockMvc.perform(get("/api/items/export")).andExpect(
                                             request().asyncStarted()) // Проверяем, что запустился асинхронный стриминг
                                     .andReturn();

        // 2. Дожидаемся завершения асинхронного потока и проверяем результат
        MvcResult dispatched = mockMvc.perform(asyncDispatch(mvcResult))
               .andExpect(status().isOk())
               .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8"))
               .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"items.csv\""))
                .andReturn();
        String actualContent = dispatched.getResponse().getContentAsString(StandardCharsets.UTF_8);
        String expected = "\uFEFFSKU,Name,Category,Quantity,Price\nSKU-1,Товар 1,Категория,0,100.00\n";
        assertThat(actualContent).isEqualToIgnoringWhitespace(expected);
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

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Экспорт большого журнала отрабатывает в асинхронном режиме без обрыва")
    void shouldStreamLargeCsvExportSuccessfully() throws Exception {
        fillDb(10000);
        MvcResult mvcResult = mockMvc.perform(get("/api/items/export"))
                                     .andExpect(status().isOk())
                                     .andExpect(request().asyncStarted())
                                     .andReturn();

        // Дожидаемся завершения асинхронного потока
        mockMvc.perform(asyncDispatch(mvcResult))
               .andExpect(status().isOk())
               .andExpect(content().contentType("text/csv;charset=UTF-8"))
               .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.isEmptyString())));
    }

    private void fillDb(int items)
            throws Exception {

        Category category = new Category();
        category.setName("Категория");
        categoryRepository.saveAndFlush(category);

        StringBuilder csvBuilder = new StringBuilder("SKU,Name,Category,Price,Cost\n");

        for (int i = 1; i <= items; i++) {
            csvBuilder.append("SKU-").append(i)
                      .append(",Товар ").append(i)
                      .append(",Категория,100.00,50.00\n");
        }
        MockMultipartFile csvFile = new MockMultipartFile(
                "file",
                "items.csv",
                "text/csv",
                csvBuilder.toString().getBytes(StandardCharsets.UTF_8)
        );

        mockMvc.perform(multipart("/api/items/import").file(csvFile)).andExpect(status().isOk());
    }
}
