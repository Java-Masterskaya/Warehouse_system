package com.warehouse.service;

import com.warehouse.dto.request.CreateStockMovementRequest;
import com.warehouse.dto.response.StockMovementResponse;
import com.warehouse.entity.Item;
import com.warehouse.entity.MovementType;
import com.warehouse.entity.Stock;
import com.warehouse.entity.StockMovement;
import com.warehouse.entity.User;
import com.warehouse.exception.EntityNotFoundException;
import com.warehouse.mapper.StockMovementMapper;
import com.warehouse.repository.ItemRepository;
import com.warehouse.repository.StockMovementRepository;
import com.warehouse.repository.StockRepository;
import jakarta.transaction.Transactional;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Реализация сервиса для управления движением товара на складе.
 * Реализует операцию пополнения остатков товара.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class StockMovementServiceImpl implements StockMovementService {

    StockMovementMapper mapper;
    ItemRepository itemRepository;
    StockRepository stockRepository;
    StockMovementRepository stockMovementRepository;

    /**
     * Пополняет остатки товара на складе.
     * Проверяет существование товара и его активность, затем увеличивает количество.
     *
     * @param user      пользователь, выполняющий операцию
     * @param request   запрос на пополнение (itemId, quantity)
     * @return response с информацией о движении и обновлённым остатком
     */
    @Override
    @Transactional
    public StockMovementResponse receiveStock(User user, CreateStockMovementRequest request) {
        int quantity = request.quantity();
        Long itemId = request.itemId();

        Item item = itemRepository.findById(itemId)
            .orElseThrow(() -> {
                log.warn("Item not found: itemId={}, userId={}", itemId, user.getId());
                return EntityNotFoundException.forId("Item", itemId);
            });

        if (!item.isActive()) {
            log.warn("Attempt to replenish inactive item: itemId={}, userId={}", itemId, user.getId());
            throw EntityNotFoundException.forId("Item", itemId);
        }

        Stock stock = stockRepository.findByItemId(itemId)
            .orElseThrow(() -> {
                log.warn("Stock not found for item: itemId={}, userId={}", itemId, user.getId());
                return EntityNotFoundException.forId("Stock", itemId);
            });

        StockMovement stockMovement = StockMovement.builder()
            .item(item)
            .user(user)
            .type(MovementType.RECEIVE)
            .quantity(quantity)
            .build();

        stock.setQuantity(stock.getQuantity() + quantity);

        stockMovementRepository.save(stockMovement);
        stockRepository.save(stock);

        log.info("Stock replenishment: itemId={}, quantity={}, newTotal={}, userId={}",
            itemId, quantity, stock.getQuantity(), user.getId());

        return mapper.toResponse(itemId, stockMovement, stock.getQuantity());
    }
}