package com.warehouse.mapper;

import com.warehouse.dto.request.category.CreateCategoryRequest;
import com.warehouse.dto.request.category.UpdateCategoryRequest;
import com.warehouse.dto.response.category.CategoryResponse;
import com.warehouse.entity.Category;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

@Mapper(componentModel = "spring")
public interface CategoryMapper {

    @Mapping(target = "id", ignore = true)
    Category toEntity(CreateCategoryRequest request);

    CategoryResponse toResponse(Category category);

    @Mapping(target = "id", ignore = true)
    void updateEntity(UpdateCategoryRequest request, @MappingTarget Category category);
}
