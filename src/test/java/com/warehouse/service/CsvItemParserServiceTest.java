package com.warehouse.service;

import com.warehouse.AbstractIntegrationTest;
import com.warehouse.dto.response.error.ItemImportErrorDto;
import com.warehouse.exception.ImportHeadersException;
import com.warehouse.service.import_export.CsvItemParserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
public class CsvItemParserServiceTest extends AbstractIntegrationTest {

    @Autowired
    private CsvItemParserService csvItemParserService;

    @Test
    @DisplayName("Строка с ошибкой валидации не блокирует последующий валидный дубликат SKU")
    void parseShouldNotBlockValidDuplicateWhenFirstRowIsInvalid() {
        // Строка 1: SKU-001, но Имя пустое (ошибка Bean Validation)
        // Строка 2: SKU-001, и все поля валидные (дубликат по SKU, но сама строка корректна)
        String csvContent = """
                SKU,Name,Category,Price,Cost
                SKU-001,,Электроника,100.00,80.00
                SKU-001,Ноутбук,Электроника,1000.00,800.00
                """;

        InputStream inputStream = new ByteArrayInputStream(csvContent.getBytes(StandardCharsets.UTF_8));

        Iterable<CsvItemParserService.CsvChunk> chunks = csvItemParserService.parseInChunks(inputStream);

        List<CsvItemParserService.ValidRowHolder> allValidRows = new ArrayList<>();
        List<ItemImportErrorDto> allErrors = new ArrayList<>();

        for (CsvItemParserService.CsvChunk chunk : chunks) {
            allValidRows.addAll(chunk.validRows());
            allErrors.addAll(chunk.errors());
        }

        assertThat(allValidRows).hasSize(1);
        assertThat(allValidRows.get(0).dto().name()).isEqualTo("Ноутбук");
        assertThat(allValidRows.get(0).rowNumber()).isEqualTo(3);

        assertThat(allErrors).hasSize(1);
        assertThat(allErrors.get(0).rowNumber()).isEqualTo(2);
    }

    @Test
    @DisplayName("Должен бросать ImportException, если в CSV отсутствуют обязательные заголовки")
    void shouldThrowExceptionWhenHeadersAreInvalid() {
        String invalidCsvContent = "WrongHeader1,WrongHeader2,Category\nval1,val2,val3";
        InputStream inputStream = new ByteArrayInputStream(invalidCsvContent.getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> csvItemParserService.parseInChunks(inputStream).iterator().hasNext())
                .isInstanceOf(ImportHeadersException.class);
    }

    @Test
    @DisplayName("Должен успешно проходить валидацию заголовков, если все обязательные колонки на месте")
    void shouldPassWhenHeadersAreValid() {
        String validCsvContent = "Sku,Name,Category,Price,Cost\nSKU-1,TestItem,Cat1,100.00,50.00";
        InputStream inputStream = new ByteArrayInputStream(validCsvContent.getBytes(StandardCharsets.UTF_8));

        var chunksIterable = csvItemParserService.parseInChunks(inputStream);
        assertThat(chunksIterable).isNotNull();

        var iterator = chunksIterable.iterator();
        assertThat(iterator).isNotNull();
        assertThat(iterator.hasNext()).isTrue();
    }

    @Test
    @DisplayName("Неполный набор обязательных колонок (отсутствует Cost) вызывает ошибку валидации заголовков")
    void shouldThrowExceptionWhenSomeRequiredHeadersAreMissing() {
        // Отсутствует обязательная колонка Cost
        String missingCostHeaderCsv = """
                SKU,Name,Category,Price
                SKU-100,Товар 1,Электроника,150.00
                """;

        InputStream inputStream = new ByteArrayInputStream(missingCostHeaderCsv.getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> csvItemParserService.parseInChunks(inputStream).iterator().hasNext())
                .satisfies(throwable -> {
                    assertThat(throwable)
                            .describedAs("Ожидалось исключение ImportHeadersException(напрямую или в cause)")
                            .isInstanceOfAny(ImportHeadersException.class,
                                    ImportHeadersException.class,
                                    RuntimeException.class);

                    boolean isCauseMatching = throwable.getCause() instanceof ImportHeadersException
                            || throwable.getCause() instanceof ImportHeadersException;
                    boolean isDirectMatching = throwable instanceof ImportHeadersException
                            || throwable instanceof ImportHeadersException;

                    assertThat(isCauseMatching || isDirectMatching).isTrue();
                });
    }
}