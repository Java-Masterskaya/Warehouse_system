package com.warehouse.service.stock;

public interface StockService {
    /**
     * Пополняет остаток товара на складе.
     *
     * @param itemId   ID товара
     * @param quantity количество единиц для пополнения
     * @return новый остаток после пополнения
     */
    int receiveStock(Long itemId, int quantity);
}