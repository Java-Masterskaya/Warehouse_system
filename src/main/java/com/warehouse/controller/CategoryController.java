package com.warehouse.controller;

import com.warehouse.dto.request.category.CreateCategoryRequest;
import com.warehouse.dto.request.category.UpdateCategoryRequest;
import com.warehouse.dto.response.category.CategoryResponse;
import com.warehouse.service.category.CategoryService;
import com.warehouse.web.ApiPaths;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({ApiPaths.V1_API_ROOT + "/categories", ApiPaths.LEGACY_API_ROOT + "/categories"})
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Категории", description = "Управление категориями товаров")
@SecurityRequirement(name = "bearerAuth")
public class CategoryController {

    private final CategoryService categoryService;

    @Operation(summary = "Создать категорию")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public CategoryResponse createCategory(
            @Valid @RequestBody CreateCategoryRequest request) {

        log.debug("Received create category request: name={}", request.name());

        return categoryService.createCategory(request);
    }

    @Operation(summary = "Получить категорию")
    @GetMapping("/{categoryId}")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasRole('ADMIN')")
    public CategoryResponse getCategory(
            @PathVariable Long categoryId) {

        log.debug("Received get category request: categoryId={}", categoryId);

        return categoryService.getCategory(categoryId);
    }

    @Operation(summary = "Обновить категорию")
    @PutMapping("/{categoryId}")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasRole('ADMIN')")
    public CategoryResponse updateCategory(
            @PathVariable Long categoryId,
            @Valid @RequestBody UpdateCategoryRequest request) {

        log.debug("Received update category request: categoryId={}, name={}",
                categoryId, request.name());

        return categoryService.updateCategory(categoryId, request);
    }

    @Operation(summary = "Удалить категорию")
    @DeleteMapping("/{categoryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void deleteCategory(@PathVariable Long categoryId) {

        log.debug("Received delete category request: categoryId={}", categoryId);

        categoryService.deleteCategory(categoryId);
    }
}
