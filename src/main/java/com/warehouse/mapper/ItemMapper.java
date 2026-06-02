package com.warehouse.mapper;

import com.warehouse.dto.CreateItemRequest;
import com.warehouse.dto.ItemResponse;
import com.warehouse.entity.Item;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface ItemMapper {
    Item toEntity(CreateItemRequest request);
    ItemResponse toResponse(Item item);
}
