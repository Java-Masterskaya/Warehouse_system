package com.warehouse.service.import_export;

import com.warehouse.dto.request.item.ItemImportRowDto;
import com.warehouse.dto.response.error.ItemImportErrorDto;
import com.warehouse.dto.response.item.ItemImportResultDto;
import com.warehouse.entity.Category;
import com.warehouse.entity.Item;
import com.warehouse.entity.Warehouse;
import com.warehouse.repository.CategoryRepository;
import com.warehouse.repository.ItemRepository;
import com.warehouse.repository.WarehouseRepository;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
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
    private final JdbcTemplate  jdbcTemplate;

    private final ItemRepository     itemRepository;
    private final CategoryRepository categoryRepository;
    private final WarehouseRepository warehouseRepository;

    private static final int BATCH_SIZE = 500;

    private final EntityManager entityManager;

    @Transactional
    public ItemImportResultDto importItems(MultipartFile file) {

        validateFile(file);

        List<ItemImportErrorDto> allErrors = new ArrayList<>();
        int totalRows = 0;
        int totalImported = 0;

        try (InputStream inputStream = file.getInputStream()) {
            Iterable<CsvItemParser.CsvChunk> chunks = csvItemParser.parseInChunks(inputStream);

            for (CsvItemParser.CsvChunk chunk : chunks) {
                totalRows += chunk.processedRowsCount();
                allErrors.addAll(chunk.errors());

                List<CsvItemParser.ValidRowHolder> candidateRows = chunk.validRows();
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

                List<Item> itemsToSave = new ArrayList<>();

                for (CsvItemParser.ValidRowHolder holder : candidateRows) {
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

                    Item item = mapDtoToEntity(dto, category);
                    itemsToSave.add(item);
                }

                if (!itemsToSave.isEmpty()) {
                    saveInBatches(itemsToSave);
                    totalImported += itemsToSave.size();
                }
            }
            log.info("Успешно импортировано {} товаров из CSV", totalImported);
            return ItemImportResultDto.of(totalRows, totalImported, allErrors);

        } catch (IOException e) {
            throw new RuntimeException("Ошибка чтения файла при импорте", e);
        }
    }

    private void saveInBatches(List<Item> items) {
        if (items.isEmpty()) {
            return;
        }

        String insertItemsSql = """
                    INSERT INTO items (sku, name, category_id, price, cost)
                    VALUES (?, ?, ?, ?, ?)
                """;

        // Превращаем список сущностей Item в массив параметров для batchUpdate
        List<Object[]> itemArgs = items.stream()
                                       .map(item -> new Object[]{
                                               item.getSku(),
                                               item.getName(),
                                               item.getCategory().getId(),
                                               item.getPrice(),
                                               item.getCost()
                                       })
                                       .toList();

        jdbcTemplate.batchUpdate(insertItemsSql, itemArgs);

        List<String> skus = items.stream().map(Item::getSku).toList();
        String placeholders = String.join(",", skus.stream().map(s -> "?").toList());
        String selectIdsSql = "SELECT id, sku FROM items WHERE sku IN (" + placeholders + ")";

        Map<String, Long> skuToIdMap = jdbcTemplate.query(
                selectIdsSql,
                ps -> {
                    for (int i = 0; i < skus.size(); i++) {
                        ps.setString(i + 1, skus.get(i));
                    }
                },
                rs -> {
                    Map<String, Long> map = new HashMap<>();
                    while (rs.next()) {
                        map.put(rs.getString("sku"), rs.getLong("id"));
                    }
                    return map;
                }
        );

        String insertStockSql = """
                    INSERT INTO stock (item_id, quantity, warehouse_id)
                    VALUES (?, 0, ?)
                """;

        Warehouse defaultWarehouse = warehouseRepository.findByDefaultWarehouseTrue()
                                                       .orElseThrow(() -> new IllegalStateException("Default warehouse is not configured"));

        List<Object[]> stockArgs = skuToIdMap.values().stream()
                                             .map(itemId -> new Object[]{itemId, defaultWarehouse.getId()})
                                             .toList();

        if (!stockArgs.isEmpty()) {
            jdbcTemplate.batchUpdate(insertStockSql, stockArgs);
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
