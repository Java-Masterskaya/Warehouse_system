package com.warehouse.service.movement;

import com.warehouse.dto.request.movement.CreateStockMovementRequest;
import com.warehouse.dto.response.movement.StockMovementResponse;

/**
 * Сервис для управления движениями товаров на складе.
 * Предоставляет методы для регистрации прихода и списания товара.
 */
public interface StockMovementService {

    /**
     * Регистрирует приход товара на склад.
     *
     * @param request данные запроса на приход товара
     * @return ответ с информацией о движении товара
     */
    StockMovementResponse registerReceipt(CreateStockMovementRequest request);
}