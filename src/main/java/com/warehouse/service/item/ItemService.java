package com.warehouse.service.item;

import com.warehouse.dto.request.item.UpdateItemRequest;
import com.warehouse.dto.request.item.CreateItemRequest;
import com.warehouse.dto.response.item.ItemDetailsResponse;
import com.warehouse.dto.response.item.ItemResponse;
import com.warehouse.dto.response.PageResponse;

public interface ItemService {
    ItemResponse createItem(CreateItemRequest request);

    ItemResponse updateItem(Long itemId, UpdateItemRequest request);

    ItemDetailsResponse getItem(Long itemId);

    PageResponse<ItemResponse> getItems(String sort, String order, String category, String search, int page, int size);
}