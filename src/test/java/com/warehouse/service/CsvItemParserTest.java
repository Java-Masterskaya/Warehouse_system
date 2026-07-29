package com.warehouse.service;

import com.warehouse.AbstractIntegrationTest;
import com.warehouse.dto.response.error.ItemImportErrorDto;
import com.warehouse.service.import_export.CsvItemParser;
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
public class CsvItemParserTest extends AbstractIntegrationTest {

    @Autowired
    private CsvItemParser csvItemParser;

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

        Iterable<CsvItemParser.CsvChunk> chunks = csvItemParser.parseInChunks(inputStream);

        List<CsvItemParser.ValidRowHolder> allValidRows = new ArrayList<>();
        List<ItemImportErrorDto> allErrors = new ArrayList<>();

        for (CsvItemParser.CsvChunk chunk : chunks) {
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
