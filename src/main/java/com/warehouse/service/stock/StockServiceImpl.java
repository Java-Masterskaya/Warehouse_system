package com.warehouse.service.stock;

import com.warehouse.entity.Stock;
import com.warehouse.exception.EntityNotFoundException;
import com.warehouse.exception.InsufficientStockException;
import com.warehouse.repository.StockRepository;
import com.warehouse.service.reservation.StockAvailabilityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class StockServiceImpl implements StockService {

    private final StockRepository stockRepository;
    private final StockAvailabilityService availabilityService;

    @Override
    public int receiveStock(Long itemId, int quantity) {
        int updatedRows = stockRepository.increaseQuantity(itemId, quantity);
        if (updatedRows == 0) {
            throw stockNotFound(itemId);
        }

        int newQuantity = getCurrentQuantity(itemId);
        log.info("Stock receipt completed: itemId={}, quantity={}, new stock={}", itemId, quantity, newQuantity);
        return newQuantity;
    }

    @Override
    public int writeOffStock(Long itemId, int quantity) {
        log.debug("Write-off: itemId={}, quantity={}", itemId, quantity);

        Stock stock = stockRepository.findByItemIdForUpdate(itemId)
                .orElseThrow(() -> EntityNotFoundException.forId("Stock by item", itemId));
        int available = availabilityService.getAvailable(itemId);
        if (available < quantity) {
            log.warn("Can not write-off {} because available {}", quantity, available);
            throw InsufficientStockException.of(itemId, quantity, available);
        }

        int updatedRows = stockRepository.decreaseQuantityIfEnough(itemId, quantity);
        if (updatedRows == 0) {
            int current = stockRepository.findQuantityByItemId(itemId).orElseThrow(() -> stockNotFound(itemId));
            log.warn("Insufficient stock for itemId={}: requested {}, available {}", itemId, quantity, current);
            throw InsufficientStockException.of(itemId, quantity, current);
        }

        int newQuantity = getCurrentQuantity(itemId);
        log.info("Write-off completed: itemId={}, quantity={}, new stock={}", itemId, quantity, newQuantity);
        return newQuantity;
    }

    private int getCurrentQuantity(Long itemId) {
        return stockRepository.findQuantityByItemId(itemId).orElseThrow(() -> stockNotFound(itemId));
    }

    private EntityNotFoundException stockNotFound(Long itemId) {
        log.warn("Stock not found for item: itemId={}", itemId);
        return new EntityNotFoundException("Stock not found for item: " + itemId);
    }
}
