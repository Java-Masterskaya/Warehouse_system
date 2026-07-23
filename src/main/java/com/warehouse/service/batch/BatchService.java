package com.warehouse.service.batch;

import com.warehouse.entity.Batch;
import com.warehouse.entity.Item;
import com.warehouse.exception.InsufficientStockException;

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
     * Создать новую партию товара и обновить stock.quantity для default warehouse.
     * Атомарная операция для сохранения консистентности.
     *
     * @param item       товар
     * @param quantity   количество единиц
     * @param expiryDate срок годности
     * @return созданная партия
     */
    Batch createBatchAndIncreaseStock(Item item, int quantity, LocalDateTime expiryDate);

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

    /**
     * Списание товара по алгоритму FEFO (First-Expire-First-Out).
     * Гасим из партии с ближайшим сроком, при нехватке — добираем из следующих.
     * Одно списание может затронуть несколько партий.
     *
     * @param itemId      ID товара
     * @param quantity    количество для списания
     * @param now         текущее время (для проверки срока годности)
     * @return количество списанных единиц (может быть меньше запрошенного при нехватке)
     * @throws InsufficientStockException если недостаточно товара во всех неистекших партиях
     */
    int writeOffByFEFO(Long itemId, int quantity, LocalDateTime now) throws InsufficientStockException;

    /**
     * Очистить протухшие партии (списать их количество в Stock).
     * Атомарная операция: очищает партии и уменьшает stock.quantity.
     * Использует pessimistic locking для безопасности.
     *
     * @param now текущее время
     * @return количество очищенных партий
     */
    int clearExpiredBatches(LocalDateTime now);
}
