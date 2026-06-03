package com.warehouse.mapper;

import com.warehouse.dto.request.CreateItemRequest;
import com.warehouse.dto.responce.ItemResponse;
import com.warehouse.dto.ItemDetails;
import com.warehouse.dto.ItemUpdateRequest;
import com.warehouse.entity.Item;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

@Mapper(componentModel = "spring")
public interface ItemMapper {
    Item toEntity(CreateItemRequest request);
    ItemResponse toResponse(Item item);

    @Mapping(target = "currentStock", ignore = true)
        // игнорируем, если нет в Item
    ItemDetails toItemDetails(Item item);

    Item updateItemFromRequest(ItemUpdateRequest request, @MappingTarget Item item);
}
