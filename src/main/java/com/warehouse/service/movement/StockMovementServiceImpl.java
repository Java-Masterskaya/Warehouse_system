package com.warehouse.service.movement;

import com.warehouse.dto.UserContext;
import com.warehouse.dto.request.movement.ChangeQuantityMovementRequest;
import com.warehouse.dto.response.movement.StockMovementResponse;
import com.warehouse.entity.Item;
import com.warehouse.entity.MovementType;
import com.warehouse.entity.StockMovement;
import com.warehouse.entity.User;
import com.warehouse.exception.EntityNotFoundException;
import com.warehouse.exception.InsufficientStockException;
import com.warehouse.metric.MetricService;
import com.warehouse.mapper.StockMovementMapper;
import com.warehouse.repository.ItemRepository;
import com.warehouse.repository.StockMovementRepository;
import com.warehouse.repository.UserRepository;
import com.warehouse.service.stock.StockService;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Реализация сервиса для управления движениями товаров на складе.
 * Обрабатывает операции прихода товара и сохраняет записи о движениях.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class StockMovementServiceImpl implements StockMovementService {

    StockMovementMapper mapper;
    StockService stockService;
    ItemRepository itemRepository;
    StockMovementRepository stockMovementRepository;
    UserRepository userRepository;
    MetricService metricService;

    /**
     * Регистрирует приход товара на склад.
     * Выполняет валидацию товара, обновляет остаток и сохраняет запись о движении.
     *
     * @param request данные запроса на приход товара
     * @param ctx     record UserContext, содержит ID и username пользователя, выполняющего операцию
     * @return ответ с информацией о движении товара
     * @throws EntityNotFoundException если товар не найден или неактивен
     */
    @Override
    @Transactional
    public StockMovementResponse registerReceipt(ChangeQuantityMovementRequest request, UserContext ctx) {
        int quantity = request.quantity();
        Long itemId = request.itemId();

        if (quantity <= 0) {
            log.warn("Invalid quantity for stock receipt: itemId={}, quantity={}", itemId, quantity);
            throw new IllegalArgumentException("Quantity must be greater than 0");
        }

        Item item = itemCheckForExist(itemId);
        itemCheckForActive(item);

        log.debug("Processing stock receipt for itemId={}, quantity={}, userId={}",
                itemId, quantity, ctx.userId());

        int stockAfter = stockService.receiveStock(itemId, quantity);

        User userRef = userRepository.getReferenceById(ctx.userId());

        StockMovement stockMovement = StockMovement.builder()
                .item(item)
                .user(userRef)
                .type(MovementType.RECEIVE)
                .quantity(quantity)
                .build();
        stockMovementRepository.save(stockMovement);

        log.info("Stock receipt registered: itemId={}, quantity={}, newTotal={}, userId={}, movementId={}",
                itemId, quantity, stockAfter, ctx.userId(), stockMovement.getId());

        metricService.increment("warehouse.movements.receive.total");

        return mapper.toResponse(stockMovement, stockAfter);
    }

    @Override
    @Transactional
    public StockMovementResponse writeOffReceipt(ChangeQuantityMovementRequest request, UserContext ctx) {
        int quantity = request.quantity();
        Long itemId = request.itemId();

        Item item = itemCheckForExist(itemId);
        itemCheckForActive(item);

        log.debug("Processing stock write-off for itemId={}, quantity={}, userId={}",
                itemId, quantity, ctx.userId());

        try {
            int stockAfter = stockService.writeOffStock(itemId, quantity);

            User userRef = userRepository.getReferenceById(ctx.userId());

            StockMovement stockMovement = StockMovement.builder()
                    .item(item)
                    .user(userRef)
                    .type(MovementType.WRITE_OFF)
                    .quantity(quantity)
                    .build();
            stockMovementRepository.save(stockMovement);

            log.info("Stock write-off completed: itemId={}, quantity={}, newTotal={}, userId={}, movementId={}",
                    itemId, quantity, stockAfter, ctx.userId(), stockMovement.getId());

            metricService.increment("warehouse.movements.writeoff.total");

            return mapper.toResponse(stockMovement, stockAfter);
        } catch (InsufficientStockException e) {
            metricService.increment("warehouse.movements.writeoff.rejected.total");
            throw e;
        }
    }

    private void itemCheckForActive(Item item) {
        if (!item.isActive()) {
            log.warn("Attempt to receive inactive item: itemId={}", item.getId());
            throw EntityNotFoundException.forId("Item", item.getId());
        }
    }

    private Item itemCheckForExist(Long itemId) {
        return itemRepository.findById(itemId)
                .orElseThrow(() -> {
                    log.warn("Item not found: itemId={}", itemId);
                    return EntityNotFoundException.forId("Item", itemId);
                });
    }
}