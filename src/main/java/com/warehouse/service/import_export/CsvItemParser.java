package com.warehouse.service.import_export;

import com.warehouse.dto.request.item.ItemImportRowDto;
import com.warehouse.dto.response.error.ItemImportErrorDto;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.io.input.BOMInputStream;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class CsvItemParser {

    private final Validator validator;

    public ParsedCsvResult parseAndValidate(InputStream inputStream) {
        List<ValidRowHolder> validRows = new ArrayList<>();
        List<ItemImportErrorDto> errors = new ArrayList<>();
        Set<String> seenSkusInFile = new HashSet<>();

        CSVFormat format = CSVFormat.DEFAULT.builder().setHeader("SKU", "Name", "Category", "Quantity", "Price")
                                            .setSkipHeaderRecord(true).setIgnoreSurroundingSpaces(true).setTrim(true)
                                            .setIgnoreHeaderCase(true) // Игнорируем регистр заголовков (sku == SKU)
                                            .build();

        // BOMInputStream срезает невидимую метку \uFEFF в начале UTF-8 файлов из Excel
        try (BOMInputStream bomStream = new BOMInputStream(inputStream);
             InputStreamReader reader = new InputStreamReader(bomStream, StandardCharsets.UTF_8);
             CSVParser csvParser = new CSVParser(reader, format)) {

            int totalRows = 0;

            for (CSVRecord record : csvParser) {
                // record.getRecordNumber() корректно считает номер записи без заголовка
                int fileRowNumber = (int) record.getRecordNumber() + 1;
                totalRows++;

                String rawSku = safeGetField(record, "SKU");

                try {
                    ItemImportRowDto dto = mapRecordToDto(record);

                    // 1. Проверяем дубликаты SKU прямо внутри файла
                    if (dto.sku() != null && !seenSkusInFile.add(dto.sku())) {
                        errors.add(new ItemImportErrorDto(fileRowNumber, dto.sku(),
                                "Дубликат SKU '" + dto.sku() + "' внутри импортируемого файла"));
                        continue;
                    }

                    // 2. Bean Validation (@NotBlank, @Positive)
                    Set<ConstraintViolation<ItemImportRowDto>> violations = validator.validate(dto);

                    if (!violations.isEmpty()) {
                        String errorMessage = violations.stream().map(ConstraintViolation::getMessage)
                                                        .reduce((m1, m2) -> m1 + "; " + m2).orElse("Ошибка валидации");
                        errors.add(new ItemImportErrorDto(fileRowNumber, dto.sku(), errorMessage));
                    } else {
                        validRows.add(new ValidRowHolder(fileRowNumber, dto));
                    }

                } catch (Exception e) {
                    errors.add(new ItemImportErrorDto(fileRowNumber, rawSku,
                            "Некорректный формат данных: " + getReadableErrorMessage(e)));
                }
            }

            return new ParsedCsvResult(totalRows, validRows, errors);

        } catch (Exception e) {
            throw new IllegalArgumentException("Ошибка чтения CSV файла. Проверьте структуру и заголовки", e);
        }
    }

    private ItemImportRowDto mapRecordToDto(CSVRecord record) {
        return new ItemImportRowDto(record.get("SKU"), record.get("Name"), record.get("Category"),
                parseInteger(record.get("Quantity")), parseBigDecimal(record.get("Price")));
    }

    private Integer parseInteger(String value) {
        if (value == null || value.isBlank()) return null;
        return Integer.parseInt(value.trim());
    }

    private BigDecimal parseBigDecimal(String value) {
        if (value == null || value.isBlank()) return null;
        return new BigDecimal(value.trim());
    }

    private String getReadableErrorMessage(Exception e) {
        if (e instanceof NumberFormatException) {
            return "Неверный числовой формат (ожидалось число)";
        }
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    private String safeGetField(CSVRecord record, String header) {
        if (record.isMapped(header)) {
            try {
                String val = record.get(header);
                return (val != null && !val.isBlank()) ? val.trim() : null;
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    // Вспомогательные классы-обёртки
    public record ValidRowHolder(int rowNumber, ItemImportRowDto dto) {
    }

    public record ParsedCsvResult(int totalRows, List<ValidRowHolder> validRows, List<ItemImportErrorDto> errors) {
    }
}
