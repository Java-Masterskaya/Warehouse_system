package com.warehouse.service.import_export;

import com.warehouse.dto.request.item.ItemImportRowDto;
import com.warehouse.dto.response.error.ItemImportErrorDto;
import com.warehouse.dto.response.item.ItemImportResultDto;
import com.warehouse.entity.Category;
import com.warehouse.entity.Item;
import com.warehouse.repository.CategoryRepository;
import com.warehouse.repository.ItemRepository;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
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
public class CsvImportService {

    private final CsvItemParser csvItemParser;

    private final ItemRepository     itemRepository;
    private final CategoryRepository categoryRepository;

    private static final int BATCH_SIZE = 500;

    private final EntityManager entityManager;

    @Transactional
    public ItemImportResultDto importItems(MultipartFile file) {
        validateFile(file);
        try (InputStream inputStream = file.getInputStream()) {
            CsvItemParser.ParsedCsvResult parseResult = csvItemParser.parseAndValidate(inputStream);

            List<ItemImportErrorDto> allErrors = new ArrayList<>(parseResult.errors());
            List<CsvItemParser.ValidRowHolder> candidateRows = parseResult.validRows();

            if (candidateRows.isEmpty()) {
                return ItemImportResultDto.of(parseResult.totalRows(), 0, allErrors);
            }

            Set<String> candidateSkus = candidateRows.stream().map(row -> row.dto().sku()).collect(Collectors.toSet());
            Set<String> categoryNames =
                    candidateRows.stream().map(row -> row.dto().category()).collect(Collectors.toSet());

            Set<String> existingSkus = new HashSet<>(itemRepository.findAllSkusIn(candidateSkus));
            Map<String, Category> categoryMap = categoryRepository.findAllByNameIgnoreCaseIn(categoryNames)
                                                                  .stream()
                                                                  .collect(Collectors.toMap(
                                                                          cat -> cat.getName().toLowerCase(),
                                                                          cat -> cat,
                                                                          (existing, replacement) -> existing));

            List<Item> itemsToSave = new ArrayList<>();

            for (CsvItemParser.ValidRowHolder holder : candidateRows) {
                ItemImportRowDto dto = holder.dto();

                if (existingSkus.contains(dto.sku())) {
                    allErrors.add(new ItemImportErrorDto(holder.rowNumber(), dto.sku(),
                            "Товар с SKU '" + dto.sku() + "' уже существует в базе данных"));
                } else {
                    Category category = categoryMap.get(dto.category().toLowerCase());
                    if (category == null) {
                        allErrors.add(new ItemImportErrorDto(holder.rowNumber(), dto.sku(),
                                "Category with name " + dto.category() + " not found"));
                        continue;
                    }

                    Item item = mapDtoToEntity(dto, category);
                    itemsToSave.add(item);
                }
            }

            if (!itemsToSave.isEmpty()) {
                saveInBatches(itemsToSave);
                log.info("Успешно импортировано {} товаров из CSV", itemsToSave.size());
            }

            return ItemImportResultDto.of(parseResult.totalRows(), itemsToSave.size(), allErrors);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void saveInBatches(List<Item> items) {
        for (int i = 0; i < items.size(); i += BATCH_SIZE) {
            int end = Math.min(i + BATCH_SIZE, items.size());
            itemRepository.saveAll(items.subList(i, end));
            itemRepository.flush();
            entityManager.clear();
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
