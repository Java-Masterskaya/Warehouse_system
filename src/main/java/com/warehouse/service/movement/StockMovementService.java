package com.warehouse.service.movement;

import com.warehouse.dto.UserContext;
import com.warehouse.dto.request.movement.ChangeQuantityMovementRequest;
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
     * @param ctx пользователь, выполняющий операцию
     * @return ответ с информацией о движении товара
     */
    StockMovementResponse registerReceipt(ChangeQuantityMovementRequest request, UserContext ctx);

    /**
     * Регистрирует списание товара со склада.
     *
     * @param request данные запроса на списание товара
     * @param ctx пользователь, выполняющий операцию
     * @return ответ с информацией о движении товара
     */
    StockMovementResponse writeOffReceipt(ChangeQuantityMovementRequest request, UserContext ctx);
}