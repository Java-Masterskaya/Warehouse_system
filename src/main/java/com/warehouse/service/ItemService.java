package com.warehouse.service;

import com.warehouse.dto.request.UpdateItemRequest;
import com.warehouse.dto.request.CreateItemRequest;
import com.warehouse.dto.response.ItemResponse;
import com.warehouse.dto.response.PageResponse;

/**
 * Сервис для управления товарами.
 * Предоставляет операции создания, обновления и фильтрации товаров.
 */
public interface ItemService {
    /**
     * Создаёт новый товар.
     *
     * @param request данные для создания товара
     * @return DTO с информацией о созданном товаре
     */
    ItemResponse createItem(CreateItemRequest request);

    /**
     * Обновляет данные товара.
     *
     * @param itemId  идентификатор товара
     * @param request новые данные товара
     * @return DTO с обновлённой информацией о товаре
     */
    ItemResponse updateItem(Long itemId, UpdateItemRequest request);

    /**
     * Получает страницу товаров с фильтрацией и сортировкой.
     *
     * @param sort    поле сортировки (sku или name)
     * @param order   порядок сортировки (asc или desc)
     * @param category фильтр по категории
     * @param search  поиск по названию
     * @param page    номер страницы
     * @param size    размер страницы
     * @return страница товаров в виде DTO
     */
    PageResponse<ItemResponse> getItems(String sort, String order, String category, String search, int page, int size);
}