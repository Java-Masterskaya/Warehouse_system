package com.warehouse.service.import_export;

import com.warehouse.entity.Item;
import com.warehouse.entity.Warehouse;
import com.warehouse.repository.WarehouseRepository;
import lombok.RequiredArgsConstructor;
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

    private final JdbcTemplate jdbcTemplate;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveInBatches(List<Item> items) {
        if (items.isEmpty()) {
            return;
        }

        String insertItemsSql = """
                    INSERT INTO items (sku, name, category_id, price, cost)
                    VALUES (?, ?, ?, ?, ?)
                """;

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
                                                        .orElseThrow(() -> new IllegalStateException(
                                                                "Default warehouse is not configured"));

        List<Object[]> stockArgs = skuToIdMap.values().stream()
                                             .map(itemId -> new Object[]{itemId, defaultWarehouse.getId()})
                                             .toList();

        if (!stockArgs.isEmpty()) {
            jdbcTemplate.batchUpdate(insertStockSql, stockArgs);
        }
    }

}
