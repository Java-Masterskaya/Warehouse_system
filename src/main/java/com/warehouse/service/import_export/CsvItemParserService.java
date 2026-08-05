package com.warehouse.service.import_export;

import com.warehouse.dto.request.item.ItemImportRowDto;
import com.warehouse.dto.response.error.ItemImportErrorDto;
import com.warehouse.exception.ImportException;
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
import java.util.Iterator;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class CsvItemParserService {

    private final        Validator validator;
    private static final int       CHUNK_SIZE = 500;

    public Iterable<CsvChunk> parseInChunks(InputStream inputStream) {
        return () -> new CsvChunkIterator(inputStream, validator);
    }

    private class CsvChunkIterator implements Iterator<CsvChunk> {
        private final BOMInputStream      bomInputStream;
        private final InputStreamReader   reader;
        private final CSVParser           csvParser;
        private final Iterator<CSVRecord> recordIterator;
        private final Validator           validator;

        private final Set<String> seenSkusInFile = new HashSet<>();
        private       boolean     hasMore        = true;

        CsvChunkIterator(InputStream inputStream, Validator validator) {
            this.validator = validator;
            try {
                this.bomInputStream = BOMInputStream.builder()
                                                    .setInputStream(inputStream)
                                                    .get();
                this.reader         = new InputStreamReader(bomInputStream, StandardCharsets.UTF_8);

                CSVFormat format = CSVFormat.DEFAULT.builder()
                                                    .setHeader()
                                                    .setSkipHeaderRecord(true)
                                                    .setIgnoreSurroundingSpaces(true)
                                                    .setTrim(true)
                                                    .setIgnoreHeaderCase(true)
                                                    .build();

                this.csvParser = new CSVParser(reader, format);
                validateHeaders(csvParser.getHeaderNames());
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

                processRecord(record, fileRowNumber, validRows, chunkErrors);
            }

            if (!recordIterator.hasNext()) {
                hasMore = false;
            }

            return new CsvChunk(validRows, chunkErrors, currentChunkRows);
        }

        private void processRecord(
                CSVRecord record,
                int fileRowNumber,
                List<ValidRowHolder> validRows,
                List<ItemImportErrorDto> chunkErrors
        ) {
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
                    return;
                }

                if (dto.sku() != null && !seenSkusInFile.add(dto.sku())) {
                    chunkErrors.add(new ItemImportErrorDto(fileRowNumber,
                            dto.sku(),
                            "Дубликат SKU '" + dto.sku() + "' внутри импортируемого файла"));
                    return;
                }

                validRows.add(new ValidRowHolder(fileRowNumber, dto));
            } catch (Exception e) {
                chunkErrors.add(new ItemImportErrorDto(fileRowNumber, rawSku,
                        "Некорректный формат данных: " + getReadableErrorMessage(e)));
            }
        }

        private void closeResources() {
            try {
                csvParser.close();
                reader.close();
                bomInputStream.close();
            } catch (Exception ignored) {
            }
        }
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

    private void validateHeaders(List<String> headers) {
        List<String> lowerCaseHeaders = headers.stream()
                                               .map(String::toLowerCase)
                                               .toList();

        boolean hasAllRequired = lowerCaseHeaders.contains("sku")
                && lowerCaseHeaders.contains("name")
                && lowerCaseHeaders.contains("category")
                && lowerCaseHeaders.contains("price")
                && lowerCaseHeaders.contains("cost");

        if (!hasAllRequired) {
            throw ImportException.ofHeaders();
        }
    }

    // Вспомогательные классы-обёртки
    public record ValidRowHolder(int rowNumber, ItemImportRowDto dto) {
    }
}
