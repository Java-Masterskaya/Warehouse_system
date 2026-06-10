package com.warehouse.service.item;

import com.warehouse.dto.request.item.CreateItemRequest;
import com.warehouse.dto.request.item.UpdateItemRequest;
import com.warehouse.dto.response.PageResponse;
import com.warehouse.dto.response.item.ItemResponse;
import com.warehouse.exception.EntityNotFoundException;

public interface ItemService {
    ItemResponse createItem(CreateItemRequest request);

    ItemResponse updateItem(Long itemId, UpdateItemRequest request);

    PageResponse<ItemResponse> getItems(String sort, String order, String category, String search, int page, int size);

    /**
     * Удаляет товар по указанному идентификатору.
     * Перед удалением товара удаляются связанные записи об остатках на складе.
     *
     * @param itemId идентификатор товара
     * @throws EntityNotFoundException если товар с указанным идентификатором не найден
     */
    void deleteItem(Long itemId);
}