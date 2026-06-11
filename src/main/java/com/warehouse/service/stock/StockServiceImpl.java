package com.warehouse.service.stock;

import com.warehouse.entity.Stock;
import com.warehouse.exception.EntityNotFoundException;
import com.warehouse.repository.StockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
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
}