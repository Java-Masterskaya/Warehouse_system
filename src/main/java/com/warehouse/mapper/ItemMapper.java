package com.warehouse.mapper;

import com.warehouse.dto.request.CreateItemRequest;
import com.warehouse.dto.responce.ItemResponse;
import com.warehouse.entity.Item;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface ItemMapper {
    Item toEntity(CreateItemRequest request);
    ItemResponse toResponse(Item item);
}
