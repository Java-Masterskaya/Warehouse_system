package com.warehouse.mapper;

import com.warehouse.dto.response.movement.StockMovementResponse;
import com.warehouse.entity.StockMovement;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface StockMovementMapper {
    @Mapping(target = "itemId", source = "entity.item.id")
    @Mapping(target = "movementId", source = "entity.id")
    StockMovementResponse toResponse(StockMovement entity, int stockAfter);
}