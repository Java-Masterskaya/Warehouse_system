package com.warehouse.mapper;

import com.warehouse.dto.event.LowStockAlertEvent;
import com.warehouse.entity.Item;
import com.warehouse.entity.StockAlert;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * Маппер для преобразования события низкого остатка в сущность алерта.
 */
@Mapper(componentModel = "spring")
public interface StockAlertMapper {

    /**
     * Создаёт сущность {@link StockAlert} из события {@link LowStockAlertEvent} и прокси-ссылки на товар.
     *
     * @param event событие низкого остатка
     * @param item  ссылка на товар
     * @return новая сущность алерта
     */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "item", source = "item")
    @Mapping(target = "currentStock", source = "event.currentStock")
    @Mapping(target = "minStock", source = "event.minStock")
    @Mapping(target = "triggeredBy", source = "event.triggeredBy")
    @Mapping(target = "createdAt", source = "event.triggeredAt")
    StockAlert toEntity(LowStockAlertEvent event, Item item);
}
