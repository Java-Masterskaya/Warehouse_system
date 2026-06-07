package com.warehouse.service;

import com.warehouse.dto.request.UpdateItemRequest;
import com.warehouse.dto.request.CreateItemRequest;
import com.warehouse.dto.response.ItemResponse;
import com.warehouse.entity.Item;
import com.warehouse.entity.Stock;
import com.warehouse.exception.DuplicateSkuException;
import com.warehouse.exception.EntityNotFoundException;
import com.warehouse.mapper.ItemMapper;
import com.warehouse.repository.ItemRepository;
import com.warehouse.repository.StockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ItemServiceImpl implements ItemService {

    private final ItemRepository itemRepository;
    private final StockRepository stockRepository;
    private final ItemMapper itemMapper;

    @Transactional
    @Override
    public ItemResponse createItem(CreateItemRequest request) {
        log.debug("Creating item with SKU '{}'", request.sku());

        if (itemRepository.existsBySku(request.sku())) {
            log.warn("Duplicate SKU '{}' — item already exists", request.sku());
            throw new DuplicateSkuException(request.sku());
        }

        Item item = itemMapper.toEntity(request);
        itemRepository.save(item);

        Stock stock = new Stock();
        stock.setItem(item);
        stock.setQuantity(0);
        stockRepository.save(stock);

        log.info("Item created: id={}, SKU='{}'", item.getId(), item.getSku());
        return itemMapper.toResponse(item);
    }

    @Transactional
    @Override
    public ItemResponse updateItem(Long itemId, UpdateItemRequest request) {
        Item item = itemRepository.findById(itemId)
                .orElseThrow(() -> EntityNotFoundException.forId("Item", itemId));
        if (!item.isActive()) {
            throw EntityNotFoundException.forId("Item", itemId);
        }

        item.setName(request.name());
        item.setCategory(request.category());
        item.setMinStock(request.minStock());

        Item savedItem = itemRepository.save(item);
        return itemMapper.toResponse(savedItem);
    }
}