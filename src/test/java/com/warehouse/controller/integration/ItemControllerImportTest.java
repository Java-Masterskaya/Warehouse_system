package com.warehouse.controller.integration;

import com.warehouse.AbstractIntegrationTest;
import com.warehouse.dto.response.error.ImportErrorAccumulator;
import com.warehouse.dto.response.error.ItemImportErrorDto;
import com.warehouse.dto.response.item.ItemImportResultDto;
import com.warehouse.service.import_export.CsvImportService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Slf4j
@SpringBootTest
class ItemControllerImportTest extends AbstractIntegrationTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CsvImportService csvImportService;

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /api/items/import — Успешная передача MultipartFile и возврат результата 200 OK")
    void importItemsSuccess()
            throws Exception {
        String csvContent = "sku,name,category,price,cost\nSKU-001,Ноутбук,Электроника,1000.00,800.00";
        MockMultipartFile file = new MockMultipartFile("file", "items.csv", "text/csv", csvContent.getBytes());

        ItemImportResultDto expectedResponse = ItemImportResultDto.of(1, 1, new ImportErrorAccumulator());
        when(csvImportService.importItems(any())).thenReturn(expectedResponse);

        mockMvc.perform(
                       multipart("/api/items/import").file(file)
                                                     .contentType(MediaType.MULTIPART_FORM_DATA).with(csrf()))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.totalRows").value(1))
               .andExpect(jsonPath("$.imported").value(1))
               .andExpect(jsonPath("$.errors").isEmpty());

        verify(csvImportService).importItems(any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /api/items/import — Частичный импорт с ошибками дубликатов")
    void importItemsWithErrorsReturns200WithErrorsList()
            throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "items.csv", "text/csv",
                "sku,name,category,price,cost\nSKU-001,Ноутбук,Электроника,1000.00,800.00".getBytes());
        ImportErrorAccumulator accumulator = new ImportErrorAccumulator();
        ItemImportErrorDto error = new ItemImportErrorDto(2, "SKU-EXISTS", "Товар с SKU 'SKU-EXISTS' уже существует");
        accumulator.add(error);
        ItemImportResultDto expectedResponse = ItemImportResultDto.of(2, 1, accumulator);

        when(csvImportService.importItems(any())).thenReturn(expectedResponse);

        mockMvc.perform(
                       multipart("/api/items/import").file(file)
                                                     .contentType(MediaType.MULTIPART_FORM_DATA).with(csrf()))
               .andExpect(status().isOk()).andExpect(jsonPath("$.totalRows").value(2))
               .andExpect(jsonPath("$.imported").value(1))
               .andExpect(jsonPath("$.errors[0].rowNumber").value(2))
               .andExpect(jsonPath("$.errors[0].sku").value("SKU-EXISTS"))
               .andExpect(jsonPath("$.errors[0].errorMessage")
                       .value("Товар с SKU 'SKU-EXISTS' уже существует"));
    }

    @WithMockUser(roles = "ADMIN")
    @Test
    void shouldAcceptLargeCsvFile()
            throws Exception {
        // Генерируем CSV-контент размером больше 1 MB (например, ~2 MB)
        StringBuilder csvContent = new StringBuilder("sku,name,category_id,price,cost\n");

        // Сделаем 70 000 строк (~2.5 MB)
        for (int i = 1; i <= 70_000; i++) {
            csvContent.append("SKU-").append(i)
                      .append(",TestItem").append(i)
                      .append(",1,100.00,50.00\n");
        }

        byte[] fileBytes = csvContent.toString().getBytes(StandardCharsets.UTF_8);

        // Создаем MockMultipartFile с весом больше 1 MB
        MockMultipartFile file = new MockMultipartFile(
                "file",         // имя параметра в контроллере (@RequestParam("file") MultipartFile file)
                "large-items.csv",    // имя файла
                "text/csv",           // content type
                fileBytes             // массив байтов (> 1 MB)
        );
        // Логируем размер в килобайтах или мегабайтах
        double fileSizeMb = (double) file.getSize() / (1024 * 1024);
        System.out.println(String.format("=== TEST: Uploading CSV file, size: %.2f MB (%d bytes) ===",
                fileSizeMb,
                file.getSize()));
        // Выполняем запрос на твой эндпоинт импорта (путь укажи свой, например /api/items/import)
        mockMvc.perform(multipart("/api/items/import").file(file))
               .andExpect(status().isOk()); // Ожидаем, что сервер принял файл и не отклонил по размеру
    }
}