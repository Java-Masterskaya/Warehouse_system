package com.warehouse.service;

import com.warehouse.dto.request.CreateItemRequest;
import com.warehouse.dto.responce.ItemResponse;

public interface ItemService {
    ItemResponse createItem(CreateItemRequest request);
}

        item = itemMapper.updateItemFromRequest(request, item);

        Item savedItem = itemRepository.save(item);
        ItemDetails details = itemMapper.toItemDetails(savedItem);

        stockRepository.findByItemId(savedItem.getId())
                .ifPresent(stock -> {
                    details.setCurrentStock(stock.getQuantity());
                });
        if (details.getCurrentStock() == null) details.setCurrentStock(0);
        return details;
    }
}