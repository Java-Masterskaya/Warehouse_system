package com.warehouse.service.item;

import com.warehouse.dto.request.item.UpdateItemRequest;
import com.warehouse.dto.request.item.CreateItemRequest;
import com.warehouse.dto.response.item.ItemDetailsResponse;
import com.warehouse.dto.response.item.ItemResponse;
import com.warehouse.dto.request.item.UpdateItemRequest;
import com.warehouse.dto.response.PageResponse;
import com.warehouse.dto.response.item.ItemResponse;

/**
 * Сервис для управления товарами.
 * Предоставляет операции создания, обновления и фильтрации товаров.
 */
public interface ItemService {

    /**
     * Создаёт новый товар и инициализирует его остаток нулём.
     *
     * @param request данные нового товара
     * @return созданный товар
     * @throws com.warehouse.exception.DuplicateSkuException если товар с таким SKU уже существует
     */
    ItemResponse createItem(CreateItemRequest request);

    /**
     * Обновляет название, категорию и минимальный остаток активного товара.
     *
     * @param itemId  идентификатор товара
     * @param request новые значения полей
     * @return обновлённый товар
     * @throws org.springframework.web.server.ResponseStatusException 404 если товар не найден или неактивен
     */
    ItemResponse updateItem(Long itemId, UpdateItemRequest request);

    /**
     * Получает детальную информацию о товаре.
     *
     * @param itemId идентификатор товара
     * @return детальная информация о товаре в виде DTO
     */
    ItemDetailsResponse getItem(Long itemId);

    /**
     * Возвращает постраничный список активных товаров с поддержкой фильтрации и сортировки.
     *
     * @param sort     поле сортировки: {@code name} (по умолчанию) или {@code sku}
     * @param order    направление: {@code asc} или {@code desc}
     * @param category фильтр по категории (опционально)
     * @param search   поиск по подстроке в названии (опционально)
     * @param page     номер страницы (с 0)
     * @param size     размер страницы
     * @return страница товаров
     */
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