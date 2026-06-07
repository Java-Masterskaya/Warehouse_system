package com.warehouse.mapper;

import com.warehouse.dto.request.CreateItemRequest;
import com.warehouse.dto.request.UpdateItemRequest;
import com.warehouse.dto.response.ItemDetailsResponse;
import com.warehouse.dto.response.ItemResponse;
import com.warehouse.entity.Item;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

@Mapper(componentModel = "spring")
public interface ItemMapper {
    Item toEntity(CreateItemRequest request);

    ItemResponse toResponse(Item item);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "sku", ignore = true)
    @Mapping(target = "active", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    void updateItemFromRequest(UpdateItemRequest request, @MappingTarget Item item);
}