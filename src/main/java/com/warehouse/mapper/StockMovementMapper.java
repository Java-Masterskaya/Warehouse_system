package com.warehouse.mapper;

import com.warehouse.dto.response.movement.StockMovementResponse;
import com.warehouse.entity.StockMovement;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Mappings;
import org.mapstruct.Named;

/**
 * Маппер для преобразования сущности движения товара в DTO-ответ.
 * Предоставляет метод для маппинга {@link StockMovement} в {@link StockMovementResponse}.
 */
@Mapper(componentModel = "spring")
public interface StockMovementMapper {

    /**
     * Преобразует сущность движения товара в ответ с информацией о движении.
     *
     * @param entity       сущность движения товара
     * @param stockAfter   остаток после операции
     * @param lowStockAlert true, если остаток опустился ниже минимального
     * @return ответ с информацией о движении товара
     */
    @Mappings({
        @Mapping(target = "itemId", source = "entity", qualifiedByName = "getItemId"),
        @Mapping(target = "movementId", source = "entity.id"),
        @Mapping(target = "lowStockAlert", source = "lowStockAlert")
    })
    StockMovementResponse toResponse(StockMovement entity, int stockAfter, boolean lowStockAlert);

    /**
     * Извлекает ID товара из сущности движения.
     * Безопасно обрабатывает случаи, когда товар может быть null.
     *
     * @param entity сущность движения товара
     * @return ID товара или null, если товар отсутствует
     */
    @Named("getItemId")
    @SuppressWarnings("unused")
    default Long getItemId(StockMovement entity) {
        if (entity.getItem() != null) {
            return entity.getItem().getId();
        }
        return null;
    }
}