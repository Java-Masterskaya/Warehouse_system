package com.warehouse.service.category;

import com.warehouse.dto.request.category.CreateCategoryRequest;
import com.warehouse.dto.request.category.UpdateCategoryRequest;
import com.warehouse.dto.response.category.CategoryResponse;
import com.warehouse.entity.Category;
import com.warehouse.exception.CategoryInUseException;
import com.warehouse.exception.DuplicateCategoryException;
import com.warehouse.exception.EntityNotFoundException;
import com.warehouse.mapper.CategoryMapper;
import com.warehouse.repository.CategoryRepository;
import com.warehouse.repository.ItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class CategoryServiceImpl implements CategoryService {

    private final CategoryRepository categoryRepository;
    private final ItemRepository itemRepository;
    private final CategoryMapper categoryMapper;

    @Override
    @Transactional
    @CacheEvict(value = "categories", allEntries = true)
    public CategoryResponse createCategory(CreateCategoryRequest request) {
        log.debug("Creating category with name='{}'", request.name());

        if (categoryRepository.existsByName(request.name())) {
            log.warn("Category with name='{}' already exists", request.name());
            throw DuplicateCategoryException.forName(request.name());
        }

        Category category = categoryMapper.toEntity(request);
        Category savedCategory = categoryRepository.save(category);

        log.info("Category created: id={}, name='{}'",
                savedCategory.getId(), savedCategory.getName());

        return categoryMapper.toResponse(savedCategory);
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(value = "categories")
    public List<CategoryResponse> getCategories() {
        log.debug("Getting all categories");

        List<CategoryResponse> categories =
                categoryRepository.findAllByOrderByNameAsc()
                        .stream()
                        .map(categoryMapper::toResponse)
                        .toList();

        log.info("Found {} categories", categories.size());

        return categories;
    }

    @Override
    @Transactional(readOnly = true)
    public CategoryResponse getCategory(Long categoryId) {
        log.debug("Getting category with id={}", categoryId);

        Category category = findCategory(categoryId);

        return categoryMapper.toResponse(category);
    }

    @Override
    @Transactional
    @CacheEvict(value = "categories", allEntries = true)
    public CategoryResponse updateCategory(
            Long categoryId,
            UpdateCategoryRequest request) {

        log.debug("Updating category with id={}", categoryId);

        Category category = findCategory(categoryId);

        if (categoryRepository.existsByNameAndIdNot(
                request.name(), categoryId)) {

            log.warn("Category with name='{}' already exists", request.name());
            throw DuplicateCategoryException.forName(request.name());
        }

        categoryMapper.updateEntity(request, category);

        Category savedCategory = categoryRepository.save(category);

        log.info("Category updated: id={}, name='{}'",
                savedCategory.getId(), savedCategory.getName());

        return categoryMapper.toResponse(savedCategory);
    }

    @Override
    @Transactional
    @CacheEvict(value = "categories", allEntries = true)
    public void deleteCategory(Long categoryId) {
        log.debug("Deleting category with id={}", categoryId);

        Category category = findCategory(categoryId);

        if (itemRepository.existsByCategoryId(categoryId)) {
            throw CategoryInUseException.forId(categoryId);
        }

        categoryRepository.delete(category);

        log.info("Category deleted: id={}, name='{}'",
                category.getId(), category.getName());
    }

    private Category findCategory(Long categoryId) {
        return categoryRepository.findById(categoryId)
                .orElseThrow(() -> {
                    log.warn("Category with id={} not found", categoryId);
                    return EntityNotFoundException.forId("Category", categoryId);
                });
    }
}
