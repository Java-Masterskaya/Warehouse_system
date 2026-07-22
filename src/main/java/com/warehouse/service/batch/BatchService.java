package com.warehouse.service.batch;

import com.warehouse.entity.Batch;
import com.warehouse.entity.Item;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface BatchService {

    /**
     * Создать новую партию товара.
     *
     * @param item       товар
     * @param quantity   количество единиц
     * @param expiryDate срок годности
     * @return созданная партия
     */
    Batch createBatch(Item item, int quantity, LocalDateTime expiryDate);

    /**
     * Найти все партии товара, отсортированные по возрастанию срока годности (FEFO).
     *
     * @param itemId ID товара
     * @return список партий
     */
    List<Batch> findByItemIdOrderByExpiryDate(Long itemId);

    /**
     * Найти партию по ID.
     *
     * @param id ID партии
     * @return опциональная партия
     */
    Optional<Batch> findById(Long id);

    /**
     * Найти все партии товара с подгрузкой item.
     *
     * @param itemId ID товара
     * @return список партий товара
     */
    List<Batch> findAllWithItemByItemId(Long itemId);
}
