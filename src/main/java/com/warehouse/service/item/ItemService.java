package com.warehouse.service.item;

import com.warehouse.dto.request.item.UpdateItemRequest;
import com.warehouse.dto.request.item.CreateItemRequest;
import com.warehouse.dto.response.item.ItemResponse;

public interface ItemService {
    ItemResponse createItem(CreateItemRequest request);

    ItemResponse updateItem(Long itemId, UpdateItemRequest request);
}