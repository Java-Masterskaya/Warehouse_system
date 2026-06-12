package com.warehouse.service.item;

import com.warehouse.dto.request.item.CreateItemRequest;
import com.warehouse.dto.request.item.UpdateItemRequest;
import com.warehouse.dto.response.PageResponse;
import com.warehouse.dto.response.item.ItemResponse;

public interface ItemService {
    ItemResponse createItem(CreateItemRequest request);

    ItemResponse updateItem(Long itemId, UpdateItemRequest request);

    PageResponse<ItemResponse> getItems(String sort, String order, String category, String search, int page, int size);

    /**
     * Скрывает товар из выдачи по его идентификатору.
     * <p>
     * Доступен только пользователям с ролью ADMIN.
     *
     * @param itemId идентификатор скрываемого товара
     * @throws EntityNotFoundException если товар не найден
     */
    void softDeleteItem(Long itemId);
}