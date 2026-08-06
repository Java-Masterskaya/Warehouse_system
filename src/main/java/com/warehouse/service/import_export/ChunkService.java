package com.warehouse.service.import_export;

import com.warehouse.dto.request.item.ItemImportRowDto;
import com.warehouse.dto.response.error.ImportErrorAccumulator;
import com.warehouse.dto.response.error.ItemImportErrorDto;
import com.warehouse.entity.Category;
import com.warehouse.entity.Warehouse;
import com.warehouse.exception.EntityNotFoundException;
import com.warehouse.repository.WarehouseRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ChunkService {

    private final WarehouseRepository warehouseRepository;
    private final JdbcTemplate        jdbcTemplate;
    private final TransactionTemplate requiresNewTxTemplate;

    public ChunkService(
            WarehouseRepository warehouseRepository,
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager
    ) {
        this.warehouseRepository   = warehouseRepository;
        this.jdbcTemplate          = jdbcTemplate;
        this.requiresNewTxTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTxTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    private static final String INSERT_ITEM_SQL = """
                INSERT INTO items (sku, name, category_id, price, cost)
                VALUES (?, ?, ?, ?, ?)
            """;

    private static final String INSERT_STOCK_SQL = """
                INSERT INTO stock (item_id, quantity, warehouse_id)
                VALUES (?, 0, ?)
            """;

    public int saveChunk(
            List<CsvItemParserService.ValidRowHolder> chunk,
            ImportErrorAccumulator accumulator,
            Map<String, Category> categoryMap
    ) {
        Warehouse defaultWarehouse;
        try {
            defaultWarehouse = warehouseRepository.findByDefaultWarehouseTrue()
                                                  .orElseThrow(() -> new EntityNotFoundException(
                                                          "Default warehouse is not configured"));
        } catch (EntityNotFoundException e) {
            accumulator.add(new ItemImportErrorDto(chunk.getFirst().rowNumber(), "", e.getMessage()));
            return 0;
        }

        try {
            int[] results = requiresNewTxTemplate.execute(status -> executeBatch(chunk,
                    defaultWarehouse,
                    categoryMap));
            return countSuccesses(results);
        } catch (Exception e) {
            return saveOneByOne(chunk, accumulator, categoryMap, defaultWarehouse);
        }
    }

    private int saveOneByOne(
            List<CsvItemParserService.ValidRowHolder> chunk,
            ImportErrorAccumulator accumulator,
            Map<String, Category> categoryMap,
            Warehouse defaultWarehouse
    ) {
        int imported = 0;
        for (CsvItemParserService.ValidRowHolder row : chunk) {
            try {
                requiresNewTxTemplate.executeWithoutResult(status -> {
                    saveSingleItemWithStock(row.dto(), categoryMap, defaultWarehouse);
                });
                imported++;
            } catch (Exception e) {
                accumulator.add(new ItemImportErrorDto(row.rowNumber(), row.dto().sku(), e.getMessage()));
            }
        }
        return imported;
    }

    private int[] executeBatch(
            List<CsvItemParserService.ValidRowHolder> validRows,
            Warehouse defaultWarehouse,
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

        int[] updateStatuses = jdbcTemplate.batchUpdate(INSERT_ITEM_SQL, itemArgs);

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
            jdbcTemplate.batchUpdate(INSERT_STOCK_SQL, stockArgs);
        }
        return updateStatuses;
    }

    private void saveSingleItemWithStock(
            ItemImportRowDto dto,
            Map<String, Category> categoryMap,
            Warehouse defaultWarehouse
    ) {
        Long categoryId = getCategoryId(dto.category(), categoryMap);

        Long itemId = jdbcTemplate.queryForObject(
                INSERT_ITEM_SQL + " RETURNING id",
                Long.class,
                dto.sku(), dto.name(), categoryId, dto.price(), dto.cost()
        );

        jdbcTemplate.update(
                INSERT_STOCK_SQL,
                itemId, defaultWarehouse.getId()
        );
    }

    private int countSuccesses(int[] result) {
        if (result == null) {
            return 0;
        }
        return (int) Arrays.stream(result)
                           .filter(i -> i > 0 || i == java.sql.Statement.SUCCESS_NO_INFO)
                           .count();
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