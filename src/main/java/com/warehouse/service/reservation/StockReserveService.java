package com.warehouse.service.reservation;

import com.warehouse.dto.UserContext;
import com.warehouse.dto.request.reservation.ReservationActionRequest;
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

    /**
     * Регистрирует бронирование остатков.
     *
     * @param itemId  идентификатор зарезервированного товара
     * @param request запрос с id резервирования которое нужно снять
     * @param ctx     пользователь проводящий операцию
     */
    void release(Long itemId, ReservationActionRequest request, UserContext ctx);

    /**
     * Регистрирует бронирование остатков.
     *
     * @param itemId  идентификатор зарезервированного товара
     * @param request запрос с id резервирования которое нужно снять
     * @param ctx     пользователь проводящий операцию
     */
    void writeOff(Long itemId, ReservationActionRequest request, UserContext ctx);
}
