package com.warehouse.service.import_export;

import com.warehouse.dto.request.item.ItemImportRowDto;
import com.warehouse.dto.response.error.ItemImportErrorDto;
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
    public void saveInBatches(
            List<CsvItemParser.ValidRowHolder> validRows,
            List<ItemImportErrorDto> chunkErrors
    ) {
        if (validRows.isEmpty()) {
            return;
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

        // Пробуем сохранить весь чанк пачкой (быстрый путь)
        try {
            executeBatchSave(validRows, defaultWarehouse, insertItemsSql, insertStockSql);
        } catch (DataIntegrityViolationException e) {
            // Если произошла гонка (дубликат SKU в базе), сохраняем поштучно,
            // чтобы отловить конфликтные строки и записать их в ошибки, сохранив остальные
            for (CsvItemParser.ValidRowHolder holder : validRows) {
                ItemImportRowDto item =
                        holder.dto(); // Предполагается, что в ValidRowHolder есть готовый Item или метод маппинга

                try {
                    jdbcTemplate.update(
                            insertItemsSql,
                            item.sku(),
                            item.name(),
                            item.category(),
                            item.price(),
                            item.cost()
                    );

                    // Сразу получаем сгенерированный ID для стока
                    Long itemId = jdbcTemplate.queryForObject(
                            "SELECT id FROM items WHERE sku = ?",
                            Long.class,
                            item.sku()
                    );

                    if (itemId != null) {
                        jdbcTemplate.update(insertStockSql, itemId, defaultWarehouse.getId());
                    }

                } catch (DataIntegrityViolationException ex) {
                    // Фиксируем ошибку гонки в общий список ошибок чанка с реальным номером строки из файла
                    chunkErrors.add(new ItemImportErrorDto(
                            holder.rowNumber(),
                            item.sku(),
                            "Товар с SKU '" + item.sku() + "' уже существует в базе данных (параллельный импорт)"
                    ));
                }
            }
        }
    }

    private void executeBatchSave(
            List<CsvItemParser.ValidRowHolder> validRows,
            Warehouse defaultWarehouse,
            String insertItemsSql,
            String insertStockSql
    ) {
        List<Object[]> itemArgs = validRows.stream()
                                           .map(holder -> {
                                               ItemImportRowDto item = holder.dto();
                                               return new Object[]{
                                                       item.sku(),
                                                       item.name(),
                                                       getCategoryId(item.category()),
                                                       item.price(),
                                                       item.cost()
                                               };
                                           })
                                           .toList();

        jdbcTemplate.batchUpdate(insertItemsSql, itemArgs);

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
    }

    private Long getCategoryId(String cat) {
        return categoryRepository.findByNameIgnoreCase(cat)
                                 .orElseThrow(() -> new EntityNotFoundException(
                                         "Category with name " + cat + " was not found.")).getId();
    }
}