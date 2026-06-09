package com.warehouse.service;

import com.warehouse.dto.internal.StockMovementInternalRequest;
import com.warehouse.entity.Item;
import com.warehouse.entity.MovementType;
import com.warehouse.entity.Stock;
import com.warehouse.entity.StockMovement;
import com.warehouse.entity.User;
import com.warehouse.exception.InsufficientStockException;
import com.warehouse.repository.ItemRepository;
import com.warehouse.repository.StockMovementRepository;
import com.warehouse.repository.StockRepository;
import com.warehouse.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class StockServiceImpl implements StockService {

    private final StockRepository stockRepository;
    private final ItemRepository itemRepository;
    private final StockMovementRepository movementRepository;
    private final UserRepository userRepository;

    @Override
    @Transactional
    public void writeOff(StockMovementInternalRequest request) {
        Item item = findActiveItem(request.itemId());
        Stock stock = getOrCreateStock(item);

        if (stock.getQuantity() < request.quantity()) {
            throw InsufficientStockException.of(request.itemId(), stock.getQuantity(), request.quantity());
        }
        stock.setQuantity(stock.getQuantity() - request.quantity());  // Списание
        stockRepository.save(stock);

        saveMovement(item, request, MovementType.WRITE_OFF);
    }

    @Override
    @Transactional
    public void receive(StockMovementInternalRequest request) {
        Item item = findActiveItem(request.itemId());
        Stock stock = getOrCreateStock(item);

        stock.setQuantity(stock.getQuantity() + request.quantity()); // Приход
        stockRepository.save(stock);

        saveMovement(item, request, MovementType.RECEIVE);
    }
    // =========================================
    //    Вспомогательные методы
    // =========================================

    private Item findActiveItem(Long itemId) {
        Item item = itemRepository.findById(itemId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Товар не найден"));

        if (!item.isActive()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Товар неактивен");
        }
        return item;
    }

    private Stock getOrCreateStock(Item item) {
        return stockRepository.findByItemId(item.getId())
                .orElseGet(() -> {
                    Stock newStock = new Stock();
                    newStock.setItem(item);
                    newStock.setQuantity(0);
                    return newStock;
                });
    }

    private void saveMovement(Item item, StockMovementInternalRequest request, MovementType type) {
        User user = userRepository.findById(request.userId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Пользователь не найден"));

        StockMovement movement = new StockMovement();
        movement.setItem(item);
        movement.setUser(user);
        movement.setType(type);
        movement.setQuantity(request.quantity());

        movementRepository.save(movement);
    }
}