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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class CsvItemParser {

    private final        Validator   validator;
    private static final int         CHUNK_SIZE       = 500;

    public Iterable<CsvChunk> parseInChunks(InputStream inputStream) {
        return () -> new Iterator<>() {
//         todo 'BOMInputStream(java.io.InputStream)' is deprecated
            private final BOMInputStream      bomInputStream = new BOMInputStream(inputStream);
            private final InputStreamReader   reader         = new InputStreamReader(bomInputStream);
            private final CSVParser           csvParser;
            private final Iterator<CSVRecord> recordIterator;

            private final Set<String> seenSkusInFile     = new HashSet<>();
            private       boolean     hasMore            = true;

            {
                try {
                    CSVFormat format = CSVFormat.DEFAULT.builder()
                                                        .setHeader()
                                                        .setSkipHeaderRecord(true)
                                                        .setIgnoreSurroundingSpaces(true)
                                                        .setTrim(true)
                                                        .setIgnoreHeaderCase(true)
                                                        .build();

                    this.csvParser      = new CSVParser(reader, format);
                    this.recordIterator = csvParser.iterator();
                } catch (Exception e) {
                    throw new IllegalArgumentException("Ошибка инициализации чтения CSV файла", e);
                }
            }

            @Override
            public boolean hasNext() {
                if (!hasMore) {
                    closeResources();
                }
                return hasMore;
            }

            @Override
            public CsvChunk next() {
                List<ValidRowHolder> validRows = new ArrayList<>();
                List<ItemImportErrorDto> chunkErrors = new ArrayList<>();
                int currentChunkRows = 0;

                while (recordIterator.hasNext() && currentChunkRows < CHUNK_SIZE) {
                    CSVRecord record = recordIterator.next();
                    int fileRowNumber = (int) record.getRecordNumber() + 1;
                    currentChunkRows++;

                    String rawSku = safeGetField(record, "SKU");

                    try {
                        ItemImportRowDto dto = mapRecordToDto(record);

                        Set<ConstraintViolation<ItemImportRowDto>> violations = validator.validate(dto);

                        if (!violations.isEmpty()) {
                            String errorMessage = violations.stream()
                                                            .map(ConstraintViolation::getMessage)
                                                            .reduce((m1, m2) -> m1 + "; " + m2)
                                                            .orElse("Ошибка валидации");
                            chunkErrors.add(new ItemImportErrorDto(fileRowNumber, dto.sku(), errorMessage));
                            continue;
                        }

                        if (dto.sku() != null && !seenSkusInFile.add(dto.sku())) {
                            chunkErrors.add(new ItemImportErrorDto(fileRowNumber,
                                    dto.sku(),
                                    "Дубликат SKU '" + dto.sku() + "' внутри импортируемого файла"));
                            continue;
                        }

                        validRows.add(new ValidRowHolder(fileRowNumber, dto));
                    } catch (Exception e) {
                        chunkErrors.add(new ItemImportErrorDto(fileRowNumber, rawSku,
                                "Некорректный формат данных: " + getReadableErrorMessage(e)));
                    }
                }

                if (!recordIterator.hasNext()) {
                    hasMore = false;
                }

                return new CsvChunk(validRows, chunkErrors, currentChunkRows);
            }

            private void closeResources() {
                try {
                    csvParser.close();
                    reader.close();
                    bomInputStream.close();
                } catch (Exception ignored) {
                }
            }
        };
    }

    public record CsvChunk(List<ValidRowHolder> validRows, List<ItemImportErrorDto> errors, int processedRowsCount) {
    }

    private ItemImportRowDto mapRecordToDto(CSVRecord record) {
        return new ItemImportRowDto(record.get("SKU"), record.get("Name"), record.get("Category"),
                parseBigDecimal(record.get("Price")),
                parseBigDecimal(record.get("Cost")));
    }

    private BigDecimal parseBigDecimal(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return new BigDecimal(value.trim());
    }

    private String getReadableErrorMessage(Exception e) {
        if (e instanceof NumberFormatException) {
            return "Неверный числовой формат (ожидалось число)";
        }
        if (e.getMessage() != null) {
            return e.getMessage();
        } else {
            return e.getClass().getSimpleName();
        }
    }

    private String safeGetField(CSVRecord record, String header) {
        if (record.isMapped(header)) {
            try {
                String val = record.get(header);
                if (val != null && !val.isBlank()) {
                    return val.trim();
                } else {
                    return null;
                }
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    // Вспомогательные классы-обёртки
    public record ValidRowHolder(int rowNumber, ItemImportRowDto dto) {
    }
}
