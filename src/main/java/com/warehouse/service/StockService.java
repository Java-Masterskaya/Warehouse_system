package com.warehouse.service;

import com.warehouse.entity.Item;
import com.warehouse.entity.MovementType;
import com.warehouse.entity.Stock;
import com.warehouse.entity.StockMovement;
import com.warehouse.entity.User;
import com.warehouse.exception.InsufficientStockException;
import com.warehouse.repository.ItemRepository;
import com.warehouse.repository.StockMovementRepository;
import com.warehouse.repository.StockRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class StockService {

    private final StockRepository stockRepository;
    private final ItemRepository itemRepository;
    private final StockMovementRepository movementRepository;

    @Transactional
    public void writeOff(Long itemId, Integer amount, String comment, User currentUser) {
        if (amount == null || amount <= 0) {
            throw new IllegalArgumentException("Сумма списания должна быть положительной");
        }
        Item item = itemRepository.findById(itemId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Товар не найден"));

        if (!item.isActive()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Товар неактивен");
        }

        // Получаем или создаем остатки
        Stock stock = stockRepository.findByItemId(itemId)
                .orElseGet(() -> {
                    Stock newStock = new Stock();
                    newStock.setItem(item);
                    newStock.setQuantity(0);
                    return newStock;
                });

        if (stock.getQuantity() < amount) {
            throw InsufficientStockException.of(itemId, stock.getQuantity(), amount);
        }

        // Списание
        stock.setQuantity(stock.getQuantity() - amount);
        stockRepository.save(stock);

        StockMovement movement = new StockMovement();
        movement.setItem(item);
        movement.setUser(currentUser);
        movement.setType(MovementType.WRITE_OFF);
        movement.setQuantity(amount);
        movement.setComment(comment);
        movementRepository.save(movement);
    }

    @Transactional
    public void receive(Long itemId, Integer amount, String comment, User currentUser) {
        if (amount == null || amount <= 0) {
            throw new IllegalArgumentException("Сумма прихода должна быть положительной");
        }
        Item item = itemRepository.findById(itemId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Товар не найден"));

        if (!item.isActive()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Товар неактивен");
        }

        Stock stock = stockRepository.findByItemId(itemId)
                .orElseGet(() -> {
                    Stock newStock = new Stock();
                    newStock.setItem(item);
                    newStock.setQuantity(0);
                    return newStock;
                });

        stock.setQuantity(stock.getQuantity() + amount);
        stockRepository.save(stock);

        StockMovement movement = new StockMovement();
        movement.setItem(item);
        movement.setUser(currentUser);
        movement.setType(MovementType.RECEIVE);
        movement.setQuantity(amount);
        movement.setComment(comment);
        movementRepository.save(movement);
    }
}