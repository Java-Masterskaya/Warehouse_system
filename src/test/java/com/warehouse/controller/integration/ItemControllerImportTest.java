package com.warehouse.controller.integration;

import com.warehouse.AbstractIntegrationTest;
import com.warehouse.dto.response.error.ItemImportErrorDto;
import com.warehouse.dto.response.item.ItemImportResultDto;
import com.warehouse.service.import_export.CsvImportService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ItemControllerImportTest extends AbstractIntegrationTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CsvImportService csvImportService;

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /api/items/import — Успешная передача MultipartFile и возврат результата 200 OK")
    void importItems_Success() throws Exception {
        String csvContent = "sku,name,category,price,cost\nSKU-001,Ноутбук,Электроника,1000.00,800.00";
        MockMultipartFile file = new MockMultipartFile("file", "items.csv", "text/csv", csvContent.getBytes());

        ItemImportResultDto expectedResponse = ItemImportResultDto.of(1, 1, List.of());
        when(csvImportService.importItems(any())).thenReturn(expectedResponse);

        // Act & Assert
        mockMvc.perform(
                       multipart("/api/items/import").file(file).contentType(MediaType.MULTIPART_FORM_DATA).with(csrf()))
               .andExpect(status().isOk()).andExpect(jsonPath("$.totalRows").value(1))
               .andExpect(jsonPath("$.imported").value(1)).andExpect(jsonPath("$.errors").isEmpty());

        verify(csvImportService).importItems(any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /api/items/import — Частичный импорт с ошибками дубликатов")
    void importItems_WithErrors_Returns200WithErrorsList() throws Exception {
        // Arrange
        MockMultipartFile file = new MockMultipartFile("file", "items.csv", "text/csv",
                "sku,name,category,price,cost\nSKU-001,Ноутбук,Электроника,1000.00,800.00".getBytes());

        ItemImportErrorDto error = new ItemImportErrorDto(2, "SKU-EXISTS", "Товар с SKU 'SKU-EXISTS' уже существует");
        ItemImportResultDto expectedResponse = ItemImportResultDto.of(2, 1, List.of(error));

        when(csvImportService.importItems(any())).thenReturn(expectedResponse);

        // Act & Assert
        mockMvc.perform(
                       multipart("/api/items/import").file(file).contentType(MediaType.MULTIPART_FORM_DATA).with(csrf()))
               .andExpect(status().isOk()).andExpect(jsonPath("$.totalRows").value(2))
               .andExpect(jsonPath("$.imported").value(1)).andExpect(jsonPath("$.errors[0].rowNumber").value(2))
               .andExpect(jsonPath("$.errors[0].sku").value("SKU-EXISTS"))
               .andExpect(jsonPath("$.errors[0].errorMessage").value("Товар с SKU 'SKU-EXISTS' уже существует"));
    }
}