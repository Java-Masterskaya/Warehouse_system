package com.warehouse.service.stock;

import com.warehouse.entity.Stock;
import com.warehouse.exception.EntityNotFoundException;
import com.warehouse.exception.InsufficientStockException;
import com.warehouse.repository.StockRepository;
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

    @Override
    public int receiveStock(Long itemId, int quantity) {
        Stock stock = stockRepository.findByItemId(itemId)
                .orElseThrow(() -> {
                    log.warn("Stock not found for item: itemId={}", itemId);
                    return new EntityNotFoundException("Stock not found for item: " + itemId);
                });
        int newQuantity = stock.getQuantity() + quantity;
        stock.setQuantity(newQuantity);
        stockRepository.save(stock);
        return newQuantity;
    }

    @Override
    public int writeOffStock(Long itemId, int quantity) {
        Stock stock = stockRepository.findByItemId(itemId)
                .orElseThrow(() -> {
                    log.warn("Stock not found for item: itemId={}", itemId);
                    return new EntityNotFoundException("Stock not found for item: " + itemId);
                });

        int current = stock.getQuantity();
        log.debug("Write-off: itemId={}, quantity={}", itemId, quantity);

        if (current < quantity) {
            log.warn("Insufficient stock for itemId={}: requested {}, available {}",
                    itemId, quantity, current);
            throw InsufficientStockException.of(itemId, quantity, current);
        }

        int newQuantity = current - quantity;
        stock.setQuantity(newQuantity);
        stockRepository.save(stock);
        log.info("Write-off completed: itemId={}, quantity={}, new stock={}",
                itemId, quantity, newQuantity);
        return newQuantity;
    }
}