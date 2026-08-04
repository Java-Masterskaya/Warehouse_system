package com.warehouse.service;

import com.warehouse.AbstractIntegrationTest;
import com.warehouse.dto.response.error.ItemImportErrorDto;
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
                SKU,Name,Category,Price,Cost,Warehouse
                SKU-001,,Электроника,100.00,80.00,Склад-1
                SKU-001,Ноутбук,Электроника,1000.00,800.00,Склад-1
                """;

        InputStream inputStream = new ByteArrayInputStream(csvContent.getBytes(StandardCharsets.UTF_8));

        Iterable<CsvItemParserService.CsvChunk> chunks = csvItemParserService.parseInChunks(inputStream);

        List<CsvItemParserService.ValidRowHolder> allValidRows = new ArrayList<>();
        List<ItemImportErrorDto> allErrors = new ArrayList<>();

        for (CsvItemParserService.CsvChunk chunk : chunks) {
            allValidRows.addAll(chunk.validRows());
            allErrors.addAll(chunk.errors());
        }

        // Ожидаемый результат:
        // 1. Первая строка должна упасть с ошибкой валидации (пустое имя)
        // 2. Вторая строка должна успешно пройти валидацию и попасть в validRows
        // (так как первая строка была невалидной и не должна была занять SKU в seenSkusInFile)

        assertThat(allValidRows).hasSize(1);
        assertThat(allValidRows.get(0).dto().name()).isEqualTo("Ноутбук");
        assertThat(allValidRows.get(0).rowNumber()).isEqualTo(3); // Вторая строка

        assertThat(allErrors).hasSize(1);
        assertThat(allErrors.get(0).rowNumber()).isEqualTo(2); // Ошибка на первой строке
    }
}
