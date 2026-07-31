package com.warehouse.service;

import com.warehouse.dto.request.item.ItemImportRowDto;
import com.warehouse.dto.response.error.ItemImportErrorDto;
import com.warehouse.dto.response.item.ItemImportResultDto;
import com.warehouse.entity.Category;
import com.warehouse.repository.CategoryRepository;
import com.warehouse.repository.ItemRepository;
import com.warehouse.service.import_export.CsvImportService;
import com.warehouse.service.import_export.CsvItemParser;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private CategoryRepository categoryRepository;

    @InjectMocks
    private CsvImportService csvImportService;

    @Test
    @DisplayName("Успешный импорт номенклатуры с нулевым остатком из MultipartFile (на моках)")
    void importItemsSuccess() {
        String categoryName1 = "Электроника";
        String categoryName2 = "Периферия";
        Category category1 = new Category();
        category1.setId(1L);
        category1.setName(categoryName1);
        Category category2 = new Category();
        category2.setId(2L);
        category2.setName(categoryName2);

        MockMultipartFile multipartFile = new MockMultipartFile("file",
                "items.csv",
                "text/csv",
                "SKU,Name,Category,Price,Cost\nSKU-001,Ноутбук,Электроника,1000.00,800.00" .getBytes());

        CsvItemParser.ValidRowHolder row1 =
                createValidRowHolder(1, "SKU-001", "Ноутбук", categoryName1, "1000.00", "800.00");
        CsvItemParser.ValidRowHolder row2 =
                createValidRowHolder(2, "SKU-002", "Мышь", categoryName2, "20.00", "10.00");

        CsvItemParser.CsvChunk chunk =
                new CsvItemParser.CsvChunk(List.of(row1, row2), Collections.emptyList(), 2);

        when(csvItemParser.parseInChunks(any(InputStream.class))).thenReturn(List.of(chunk));
        when(itemRepository.findAllSkusIn(Set.of("SKU-001", "SKU-002"))).thenReturn(Collections.emptyList());
        when(categoryRepository.findAllByNameIgnoreCaseIn(Set.of(categoryName1, categoryName2))).thenReturn(List.of(
                category1,
                category2));

        // Настраиваем мок JdbcTemplate для запроса выборки ID по SKU (возвращаем маппинг)
        when(jdbcTemplate.query(anyString(),
                any(org.springframework.jdbc.core.PreparedStatementSetter.class),
                any(org.springframework.jdbc.core.ResultSetExtractor.class)))
                .thenAnswer(invocation -> {
                    var rse = (org.springframework.jdbc.core.ResultSetExtractor<?>) invocation.getArgument(2);
                    return java.util.Map.of("SKU-001", 10L, "SKU-002", 11L);
                });

        ItemImportResultDto result = csvImportService.importItems(multipartFile);

        assertThat(result.totalRows()).isEqualTo(2);
        assertThat(result.imported()).isEqualTo(2);
        assertThat(result.errors()).isEmpty();

        verify(jdbcTemplate).batchUpdate(
                org.mockito.ArgumentMatchers.contains("INSERT INTO items"),
                ArgumentMatchers.<List<Object[]>>any()
        );

        verify(jdbcTemplate).batchUpdate(
                org.mockito.ArgumentMatchers.contains("INSERT INTO stocks"),
                ArgumentMatchers.<List<Object[]>>any()
        );
    }

    @Test
    @DisplayName("Ошибка валидации, если передан пустой файл")
    void importItemsEmptyFileThrowsException() {
        MockMultipartFile emptyFile = new MockMultipartFile("file", "empty.csv", "text/csv", new byte[0]);

        assertThatThrownBy(() -> csvImportService.importItems(emptyFile)).isInstanceOf(IllegalArgumentException.class)
                                                                         .hasMessageContaining("не может быть пустым");

        verify(csvItemParser, never()).parseInChunks(any());
        verify(itemRepository, never()).saveAll(any());
        verify(jdbcTemplate, never()).batchUpdate(anyString(), ArgumentMatchers.<List<Object[]>>any());
    }

    @Test
    @DisplayName("Ошибка валидации, если расширение файла не .csv")
    void importItemsInvalidExtensionThrowsException() {
        MockMultipartFile txtFile =
                new MockMultipartFile("file", "items.txt", "text/plain", "some content" .getBytes());

        assertThatThrownBy(() -> csvImportService.importItems(txtFile)).isInstanceOf(IllegalArgumentException.class)
                                                                       .hasMessageContaining("CSV");

        verify(csvItemParser, never()).parseInChunks(any());
        verify(jdbcTemplate, never()).batchUpdate(anyString(), ArgumentMatchers.<List<Object[]>>any());
    }

    @Test
    @DisplayName("Импорт с ошибками: дубликаты SKU и несуществующие категории корректно попадают в отчет")
    void importItemsWithErrorsReport() {
        MockMultipartFile file = new MockMultipartFile("file", "items.csv", "text/csv", "content" .getBytes());

        Category existingCategory = new Category();
        existingCategory.setId(1L);
        existingCategory.setName("Электроника");

        CsvItemParser.ValidRowHolder row1 =
                createValidRowHolder(1, "SKU-NEW", "Ноутбук", "Электроника", "1000.00", "800.00");
        CsvItemParser.ValidRowHolder row2 =
                createValidRowHolder(2, "SKU-EXISTS", "Мышь", "Электроника", "20.00", "10.00");
        CsvItemParser.ValidRowHolder row3 =
                createValidRowHolder(3, "SKU-ANOTHER", "Стол", "Неизвестная", "150.00", "100.00");

        CsvItemParser.CsvChunk chunk =
                new CsvItemParser.CsvChunk(List.of(row1, row2, row3), Collections.emptyList(), 3);

        when(csvItemParser.parseInChunks(any(InputStream.class))).thenReturn(List.of(chunk));
        when(itemRepository.findAllSkusIn(any())).thenReturn(List.of("SKU-EXISTS"));
        when(categoryRepository.findAllByNameIgnoreCaseIn(any())).thenReturn(List.of(existingCategory));

        when(jdbcTemplate.query(anyString(),
                any(org.springframework.jdbc.core.PreparedStatementSetter.class),
                any(org.springframework.jdbc.core.ResultSetExtractor.class)))
                .thenReturn(java.util.Map.of("SKU-NEW", 1L));

        ItemImportResultDto result = csvImportService.importItems(file);

        assertThat(result.totalRows()).isEqualTo(3);
        assertThat(result.imported()).isEqualTo(1);
        assertThat(result.errors()).hasSize(2);

        ItemImportErrorDto skuError =
                result.errors().stream().filter(e -> e.rowNumber() == 2).findFirst().orElseThrow();
        assertThat(skuError.sku()).isEqualTo("SKU-EXISTS");
        assertThat(skuError.errorMessage()).contains("уже существует");

        ItemImportErrorDto categoryError =
                result.errors().stream().filter(e -> e.rowNumber() == 3).findFirst().orElseThrow();
        assertThat(categoryError.sku()).isEqualTo("SKU-ANOTHER");
        assertThat(categoryError.errorMessage()).contains("Category with name Неизвестная not found");

        verify(jdbcTemplate).batchUpdate(
                org.mockito.ArgumentMatchers.contains("INSERT INTO items"),
                ArgumentMatchers.<List<Object[]>>any()
        );

        verify(jdbcTemplate).batchUpdate(
                org.mockito.ArgumentMatchers.contains("INSERT INTO stocks"),
                ArgumentMatchers.<List<Object[]>>any()
        );
    }

    private CsvItemParser.ValidRowHolder createValidRowHolder(
            int rowNum,
            String sku,
            String name,
            String category,
            String price,
            String cost
    ) {
        ItemImportRowDto dto = new ItemImportRowDto(sku, name, category, new BigDecimal(price), new BigDecimal(cost));
        return new CsvItemParser.ValidRowHolder(rowNum, dto);
    }
}