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

    /**
     * Производит списание товара на складе.
     *
     * @param itemId   ID товара
     * @param quantity количество единиц для списания
     * @return новый остаток после списания
     */
    int writeOffStock(Long itemId, int quantity);


}