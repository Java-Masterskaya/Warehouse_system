package com.warehouse.mapper;

import com.warehouse.dto.ItemDetails;
import com.warehouse.dto.ItemUpdateRequest;
import com.warehouse.entity.Item;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

@Mapper(componentModel = "spring")
public interface ItemMapper {

    @Mapping(target = "currentStock", ignore = true) // игнорируем, если нет в Item
    ItemDetails toItemDetails(Item item);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "sku", ignore = true)
    @Mapping(target = "active", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    void updateItemFromRequest(ItemUpdateRequest request, @MappingTarget Item item);
}