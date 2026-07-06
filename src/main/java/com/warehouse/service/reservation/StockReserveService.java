package com.warehouse.service.reservation;

import com.warehouse.dto.UserContext;
import com.warehouse.dto.request.reservation.ReserveRequest;

/**
 * Сервис для управления резервированием остатков.
 * Предоставляет методы для резервирования.
 */
public interface StockReserveService {

    /**
     * Регистрирует бронирование остатков.
     *
     * @param itemId  идентификатор бронируемого товара
     * @param request запрос на бронирование
     * @param ctx     пользователь проводящий операцию
     */
    void reserve(Long itemId, ReserveRequest request, UserContext ctx);
}
