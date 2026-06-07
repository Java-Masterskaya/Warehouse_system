package com.warehouse.mapper;

import com.warehouse.dto.response.StockMovementResponse;
import com.warehouse.entity.StockMovement;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface StockMovementMapper {
    @Mapping(target = "movementId", source = "entity.id")
    StockMovementResponse toResponse(Long itemId, StockMovement entity, int stockAfter);
}