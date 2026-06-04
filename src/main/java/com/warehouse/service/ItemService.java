package com.warehouse.service;

import com.warehouse.dto.request.CreateItemRequest;
import com.warehouse.dto.responce.ItemResponse;

public interface ItemService {
    ItemResponse createItem(CreateItemRequest request);
}
