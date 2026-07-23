package com.warehouse.service.movement;

import com.warehouse.dto.UserContext;
import com.warehouse.dto.request.movement.ChangeQuantityMovementRequest;
import com.warehouse.dto.request.movement.StocktakeRequest;
import com.warehouse.dto.request.movement.TransferStockRequest;
import com.warehouse.dto.response.PageResponse;
import com.warehouse.dto.response.movement.StockMovementHistoryResponse;
import com.warehouse.dto.response.movement.StockMovementResponse;
import com.warehouse.dto.response.movement.StockTransferResponse;
import com.warehouse.entity.Item;
import com.warehouse.entity.MovementType;
import com.warehouse.entity.StockMovement;
import com.warehouse.entity.Warehouse;

/**
 * Сервис для управления движениями товаров на складе.
 * Предоставляет методы для регистрации прихода и списания товара.
 */
public interface StockMovementService {

    /**
     * Регистрирует приход товара на склад.
     *
     * @param request данные запроса на приход товара
     * @param ctx     пользователь, выполняющий операцию
     * @return ответ с информацией о движении товара
     */
    StockMovementResponse registerReceipt(ChangeQuantityMovementRequest request, UserContext ctx);

    /**
     * Регистрирует списание товара со склада.
     *
     * @param request данные запроса на списание товара
     * @param ctx     пользователь, выполняющий операцию
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
    PageResponse<StockMovementHistoryResponse> getItemMovementHistory(Long itemId, MovementType type, int page,
            int size);

    /**
     * Выполняет инвентаризацию товара: сверяет фактический остаток
     * с учётным и при расхождении создаёт корректирующее движение.
     *
     * @param request данные переучёта: ID товара и фактически подсчитанное количество
     * @param ctx     пользователь, выполняющий операцию
     * @return ответ о созданном движении товара; если дельта равна нулю —
     * возвращает текущее состояние без движения
     */
    StockMovementResponse stocktake(StocktakeRequest request, UserContext ctx);

    /**
     * Atomically transfers an item between warehouses.
     *
     * @param request transfer parameters
     * @param ctx user performing the transfer
     * @return transfer result and both resulting balances
     */
    StockTransferResponse transfer(TransferStockRequest request, UserContext ctx);

    /**
     * Выполняет сохранение нового движение товаров на складе.
     *
     * @param item     перемещаемый товар
     * @param quantity количество перемещаемых товаров
     * @param ctx      пользователь, выполняющий операцию
     * @param type     тип выполняемой операции
     * @return ответ о созданном движении товара
     */
    StockMovement newStockMovement(Item item, int quantity, UserContext ctx, MovementType type);

    /**
     * Persists a stock movement for a specific warehouse.
     *
     * @param item moved item
     * @param warehouse movement warehouse
     * @param quantity movement quantity
     * @param ctx user performing the operation
     * @param type movement type
     * @return persisted movement
     */
    StockMovement newStockMovement(
            Item item,
            Warehouse warehouse,
            int quantity,
            UserContext ctx,
            MovementType type
    );
}
