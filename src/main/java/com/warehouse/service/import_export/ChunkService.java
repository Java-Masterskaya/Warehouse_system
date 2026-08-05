package com.warehouse.service.import_export;

import com.warehouse.dto.request.item.ItemImportRowDto;
import com.warehouse.dto.response.error.ItemImportErrorDto;
import com.warehouse.entity.Category;
import com.warehouse.entity.Warehouse;
import com.warehouse.exception.EntityNotFoundException;
import com.warehouse.repository.CategoryRepository;
import com.warehouse.repository.WarehouseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ChunkService {

    private final WarehouseRepository warehouseRepository;
    private final CategoryRepository  categoryRepository;
    private final JdbcTemplate        jdbcTemplate;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int saveInBatches(
            List<CsvItemParserService.ValidRowHolder> validRows,
            List<ItemImportErrorDto> chunkErrors,
            Map<String, Category> categoryMap
    ) {
        int executed = 0;

        if (validRows.isEmpty()) {
            return executed;
        }

        Warehouse defaultWarehouse = warehouseRepository.findByDefaultWarehouseTrue()
                                                        .orElseThrow(() -> new IllegalStateException(
                                                                "Default warehouse is not configured"));

        String insertItemsSql = """
                    INSERT INTO items (sku, name, category_id, price, cost)
                    VALUES (?, ?, ?, ?, ?)
                """;

        String insertStockSql = """
                    INSERT INTO stock (item_id, quantity, warehouse_id)
                    VALUES (?, 0, ?)
                """;

        try {
            executed = executeBatchSave(validRows, defaultWarehouse, insertItemsSql, insertStockSql, categoryMap);
        } catch (DataIntegrityViolationException e) {
            for (CsvItemParserService.ValidRowHolder holder : validRows) {
                ItemImportRowDto item = holder.dto();
                try {
                    Long categoryId = getCategoryId(item.category(), categoryMap); // вынесли отдельно

                    jdbcTemplate.update(
                            insertItemsSql,
                            item.sku(),
                            item.name(),
                            categoryId,
                            item.price(),
                            item.cost()
                    );

                    Long itemId = jdbcTemplate.queryForObject(
                            "SELECT id FROM items WHERE sku = ?",
                            Long.class,
                            item.sku()
                    );

                    if (itemId != null) {
                        jdbcTemplate.update(insertStockSql, itemId, defaultWarehouse.getId());
                    }

                } catch (DataIntegrityViolationException ex) {
                    chunkErrors.add(new ItemImportErrorDto(
                            holder.rowNumber(),
                            item.sku(),
                            "Товар с SKU '" + item.sku() + "' уже существует в базе данных (параллельный импорт)"
                    ));
                } catch (Exception ex) {
                    chunkErrors.add(new ItemImportErrorDto(
                            holder.rowNumber(),
                            item.sku(),
                            "Ошибка сохранения строки: " + ex.getMessage()
                    ));
                }
            }
        }
        return executed;
    }

    private int executeBatchSave(
            List<CsvItemParserService.ValidRowHolder> validRows,
            Warehouse defaultWarehouse,
            String insertItemsSql,
            String insertStockSql,
            Map<String, Category> categoryMap
    ) {
        List<Object[]> itemArgs = validRows.stream()
                                           .map(holder -> {
                                               ItemImportRowDto item = holder.dto();
                                               return new Object[]{
                                                       item.sku(),
                                                       item.name(),
                                                       getCategoryId(item.category(), categoryMap),
                                                       item.price(),
                                                       item.cost()
                                               };
                                           })
                                           .toList();

        int[] updateStatuses = jdbcTemplate.batchUpdate(insertItemsSql, itemArgs);

        List<String> skus = validRows.stream().map(h -> h.dto().sku()).toList();
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

        List<Object[]> stockArgs = skuToIdMap.values().stream()
                                             .map(itemId -> new Object[]{itemId, defaultWarehouse.getId()})
                                             .toList();

        if (!stockArgs.isEmpty()) {
            jdbcTemplate.batchUpdate(insertStockSql, stockArgs);
        }
        return Arrays.stream(Arrays.stream(updateStatuses).toArray()).filter((i) -> i > 0).toArray().length;
    }

    private Long getCategoryId(String cat, Map<String, Category> categoryMap) {
        if (cat == null) {
            throw new EntityNotFoundException("Category name cannot be null.");
        }
        Category category = categoryMap.get(cat.toLowerCase());
        if (category == null) {
            throw new EntityNotFoundException("Category with name " + cat + " was not found.");
        }
        return category.getId();
    }
}