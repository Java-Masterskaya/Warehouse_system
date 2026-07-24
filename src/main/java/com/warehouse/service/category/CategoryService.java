package com.warehouse.service.category;

import com.warehouse.dto.request.category.CreateCategoryRequest;
import com.warehouse.dto.request.category.UpdateCategoryRequest;
import com.warehouse.dto.response.category.CategoryResponse;

import java.util.List;

public interface CategoryService {

    CategoryResponse createCategory(CreateCategoryRequest request);

    List<CategoryResponse> getCategories();

    CategoryResponse getCategory(Long categoryId);

    CategoryResponse updateCategory(Long categoryId, UpdateCategoryRequest request);

    void deleteCategory(Long categoryId);
}
