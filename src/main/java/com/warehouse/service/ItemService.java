package com.warehouse.service;

import com.warehouse.dto.CreateItemRequest;
import com.warehouse.dto.ItemResponse;

public interface ItemService {
    ItemResponse createItem(CreateItemRequest request);
}
