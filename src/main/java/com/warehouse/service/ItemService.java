package com.warehouse.service;

import com.warehouse.dto.request.UpdateItemRequest;
import com.warehouse.dto.request.CreateItemRequest;
import com.warehouse.dto.response.ItemDetailsResponse;
import com.warehouse.dto.response.ItemResponse;

public interface ItemService {
    ItemResponse createItem(CreateItemRequest request);

    ItemResponse updateItem(Long itemId, UpdateItemRequest request);

    ItemDetailsResponse getItem(Long itemId);
}