package com.warehouse.service.movement;

import com.warehouse.dto.request.movement.CreateStockMovementRequest;
import com.warehouse.dto.response.movement.StockMovementResponse;
import com.warehouse.entity.Item;
import com.warehouse.entity.MovementType;
import com.warehouse.entity.StockMovement;
import com.warehouse.entity.User;
import com.warehouse.exception.EntityNotFoundException;
import com.warehouse.mapper.StockMovementMapper;
import com.warehouse.repository.ItemRepository;
import com.warehouse.repository.StockMovementRepository;
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

    /**
     * Регистрирует приход товара на склад.
     * Выполняет валидацию товара, обновляет остаток и сохраняет запись о движении.
     *
     * @param request данные запроса на приход товара
     * @param user    пользователь, выполняющий операцию
     * @return ответ с информацией о движении товара
     * @throws EntityNotFoundException если товар не найден или неактивен
     */
    @Override
    @Transactional
    public StockMovementResponse registerReceipt(CreateStockMovementRequest request, User user) {
        int quantity = request.quantity();
        Long itemId = request.itemId();

        if (quantity <= 0) {
            log.warn("Invalid quantity for stock receipt: itemId={}, quantity={}", itemId, quantity);
            throw new IllegalArgumentException("Quantity must be greater than 0");
        }

        // Сначала проверяем товар
        Item item = itemRepository.findById(itemId)
            .orElseThrow(() -> {
                log.warn("Item not found: itemId={}", itemId);
                return EntityNotFoundException.forId("Item", itemId);
            });

        if (!item.isActive()) {
            log.warn("Attempt to receive inactive item: itemId={}", itemId);
            throw EntityNotFoundException.forId("Item", itemId);
        }

        log.debug("Processing stock receipt for itemId={}, quantity={}, userId={}",
            itemId, quantity, user.getId());

        int stockAfter = stockService.receiveStock(itemId, quantity);

        StockMovement stockMovement = StockMovement.builder()
            .item(item)
            .user(user)
            .type(MovementType.RECEIVE)
            .quantity(quantity)
            .build();

        stockMovementRepository.save(stockMovement);

        log.info("Stock receipt registered: itemId={}, quantity={}, newTotal={}, userId={}, movementId={}",
                itemId, quantity, stockAfter, user.getId(), stockMovement.getId());

        return mapper.toResponse(stockMovement, stockAfter);
    }
}