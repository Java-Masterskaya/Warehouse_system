package com.warehouse.service;

import com.warehouse.dto.request.UpdateItemRequest;
import com.warehouse.dto.request.CreateItemRequest;
import com.warehouse.dto.response.ItemResponse;
import com.warehouse.dto.response.PageResponse;

public interface ItemService {
    ItemResponse createItem(CreateItemRequest request);

    ItemResponse updateItem(Long itemId, UpdateItemRequest request);

    PageResponse<ItemResponse> getItems(String sort, String order, String category, String search, int page, int size);
}