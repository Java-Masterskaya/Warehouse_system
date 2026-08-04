package com.warehouse.service.import_export;

import com.warehouse.dto.request.item.ItemImportRowDto;
import com.warehouse.dto.response.error.ItemImportErrorDto;
import com.warehouse.dto.response.item.ItemImportResultDto;
import com.warehouse.entity.Category;
import com.warehouse.entity.Item;
import com.warehouse.repository.CategoryRepository;
import com.warehouse.repository.ItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CsvImportServiceImpl implements CsvImportService {

    private final CsvItemParserService csvItemParserService;
    private final ChunkService         chunkService;

    private final ItemRepository     itemRepository;
    private final CategoryRepository categoryRepository;

    @Override
    public ItemImportResultDto importItems(MultipartFile file) {

        validateFile(file);

        List<ItemImportErrorDto> allErrors = new ArrayList<>();
        int totalRows = 0;
        int totalImported = 0;

        try (InputStream inputStream = file.getInputStream()) {
            Iterable<CsvItemParserService.CsvChunk> chunks = csvItemParserService.parseInChunks(inputStream);

            for (CsvItemParserService.CsvChunk chunk : chunks) {
                totalRows += chunk.processedRowsCount();
                allErrors.addAll(chunk.errors());

                List<CsvItemParserService.ValidRowHolder> candidateRows = chunk.validRows();
                if (candidateRows.isEmpty()) {
                    continue;
                }

                Set<String> candidateSkus = candidateRows.stream()
                                                         .map(row -> row.dto().sku())
                                                         .collect(Collectors.toSet());
                Set<String> existingSkus = new HashSet<>(itemRepository.findAllSkusIn(candidateSkus));

                Set<String> categoryNames = candidateRows.stream()
                                                         .map(row -> row.dto().category())
                                                         .collect(Collectors.toSet());
                Map<String, Category> categoryMap = categoryRepository.findAllByNameIgnoreCaseIn(categoryNames)
                                                                      .stream()
                                                                      .collect(Collectors.toMap(
                                                                              cat -> cat.getName().toLowerCase(),
                                                                              cat -> cat,
                                                                              (existing, replacement) -> existing
                                                                      ));

                List<CsvItemParserService.ValidRowHolder> validHoldersToSave = new ArrayList<>();

                for (CsvItemParserService.ValidRowHolder holder : candidateRows) {
                    ItemImportRowDto dto = holder.dto();

                    if (existingSkus.contains(dto.sku())) {
                        allErrors.add(new ItemImportErrorDto(holder.rowNumber(), dto.sku(),
                                "Товар с SKU '" + dto.sku() + "' уже существует в базе данных"));
                        continue;
                    }

                    Category category = categoryMap.get(dto.category().toLowerCase());
                    if (category == null) {
                        allErrors.add(new ItemImportErrorDto(holder.rowNumber(), dto.sku(),
                                "Category with name " + dto.category() + " not found"));
                        continue;
                    }

                    validHoldersToSave.add(holder);
                }

                if (!validHoldersToSave.isEmpty()) {
                    chunkService.saveInBatches(validHoldersToSave, allErrors);
                    totalImported += validHoldersToSave.size();
                }
            }
            log.info("Успешно импортировано {} товаров из CSV", totalImported);
            return ItemImportResultDto.of(totalRows, totalImported, allErrors);

        } catch (IOException e) {
            throw new RuntimeException("Ошибка чтения файла при импорте", e);
        }
    }

    private Item mapDtoToEntity(ItemImportRowDto dto, Category category) {
        return Item.builder()
                   .sku(dto.sku())
                   .name(dto.name())
                   .category(category)
                   .price(dto.price())
                   .cost(dto.cost())
                   .build();
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Файл для импорта не может быть пустым");
        }

        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase().endsWith(".csv")) {
            throw new IllegalArgumentException("Разрешена загрузка только CSV файлов");
        }
    }
}
