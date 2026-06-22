package com.warehouse.service.movement;

import com.warehouse.dto.UserContext;
import com.warehouse.dto.request.movement.ChangeQuantityMovementRequest;
import com.warehouse.dto.response.PageResponse;
import com.warehouse.dto.response.movement.StockMovementHistoryResponse;
import com.warehouse.dto.response.movement.StockMovementResponse;
import com.warehouse.entity.MovementType;

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

    /**
     * Возвращает историю движений товара с возможностью фильтрации по типу движения
     * и постраничного вывода результатов.
     *
     * @param itemId идентификатор товара
     * @param type   необязательный фильтр по типу движения
     * @param page   номер страницы
     * @param size   количество записей на странице
     * @return страница с историей движений товара
     * @throws EntityNotFoundException если товар не найден
     */
    PageResponse<StockMovementHistoryResponse> getItemMovementHistory(Long itemId,
                                                                      MovementType type,
                                                                      int page,
                                                                      int size);

}