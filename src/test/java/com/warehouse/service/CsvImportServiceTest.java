package com.warehouse.service;

import com.warehouse.dto.request.item.ItemImportRowDto;
import com.warehouse.dto.response.item.ItemImportResultDto;
import com.warehouse.entity.Item;
import com.warehouse.repository.ItemRepository;
import com.warehouse.service.import_export.CsvImportService;
import com.warehouse.service.import_export.CsvItemParser;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CsvImportServiceTest {

    @Mock
    private CsvItemParser csvItemParser;

    @Mock
    private ItemRepository itemRepository;

    @Mock
    private EntityManager entityManager;

    @InjectMocks
    private CsvImportService csvImportService;

    @Test
    @DisplayName("Успешный импорт номенклатуры с нулевым остатком из MultipartFile")
    void importItems_Success() {
        MockMultipartFile multipartFile = new MockMultipartFile("file", "items.csv", "text/csv",
                "SKU,Name,Category,Price,Cost\nSKU-001,Ноутбук,Электроника,1000.00,800.00".getBytes());

        CsvItemParser.ValidRowHolder row1 =
                createValidRowHolder(1, "SKU-001", "Ноутбук", "Электроника", "1000.00", "800.00");
        CsvItemParser.ValidRowHolder row2 = createValidRowHolder(2, "SKU-002", "Мышь", "Периферия", "20.00", "10.00");

        CsvItemParser.ParsedCsvResult parseResult =
                new CsvItemParser.ParsedCsvResult(2, List.of(row1, row2), Collections.emptyList());

        when(csvItemParser.parseAndValidate(any(InputStream.class))).thenReturn(parseResult);
        when(itemRepository.findAllSkusIn(Set.of("SKU-001", "SKU-002"))).thenReturn(Collections.emptyList());

        ItemImportResultDto result = csvImportService.importItems(multipartFile);

        assertThat(result.totalRows()).isEqualTo(2);
        assertThat(result.imported()).isEqualTo(2);
        assertThat(result.errors()).isEmpty();

        @SuppressWarnings("unchecked") ArgumentCaptor<List<Item>> itemCaptor = ArgumentCaptor.forClass(List.class);
        verify(itemRepository).saveAll(itemCaptor.capture());

        List<Item> savedItems = itemCaptor.getValue();
        assertThat(savedItems).hasSize(2);

        assertThat(savedItems.get(0).getSku()).isEqualTo("SKU-001");
    }

    @Test
    @DisplayName("Ошибка валидации, если передан пустой файл")
    void importItems_EmptyFile_ThrowsException() {
        // Arrange
        MockMultipartFile emptyFile = new MockMultipartFile("file", "empty.csv", "text/csv", new byte[0]);

        assertThatThrownBy(() -> csvImportService.importItems(emptyFile)).isInstanceOf(IllegalArgumentException.class)
                                                                         .hasMessageContaining("не может быть пустым");

        verify(csvItemParser, never()).parseAndValidate(any());
        verify(itemRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("Ошибка валидации, если расширение файла не .csv")
    void importItems_InvalidExtension_ThrowsException() {
        MockMultipartFile txtFile =
                new MockMultipartFile("file", "items.txt", "text/plain", "some content".getBytes());

        assertThatThrownBy(() -> csvImportService.importItems(txtFile)).isInstanceOf(IllegalArgumentException.class)
                                                                       .hasMessageContaining("CSV");

        verify(csvItemParser, never()).parseAndValidate(any());
    }

    private CsvItemParser.ValidRowHolder createValidRowHolder(
            int rowNum, String sku, String name, String category, String price, String cost) {
        ItemImportRowDto dto = new ItemImportRowDto(sku, name, category, new BigDecimal(price), new BigDecimal(cost));
        return new CsvItemParser.ValidRowHolder(rowNum, dto);
    }
}