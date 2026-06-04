package com.warehouse.service;

import com.warehouse.dto.response.ItemDetails;
import com.warehouse.dto.request.UpdateItemRequest;
import com.warehouse.dto.request.CreateItemRequest;
import com.warehouse.dto.response.ItemResponse;

public interface ItemService {
    ItemResponse createItem(CreateItemRequest request);

    ItemDetails updateItem(Long itemId, UpdateItemRequest request);
}