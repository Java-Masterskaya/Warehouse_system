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
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.dao.DataIntegrityViolationException;
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

        String name = request.name().trim();
        if (categoryRepository.existsByNameIgnoreCase(name)) {
            log.warn("Category with name='{}' already exists", name);
            throw DuplicateCategoryException.forName(name);
        }

        Category category = categoryMapper.toEntity(request);
        category.setName(name);
        try {
            Category savedCategory = categoryRepository.saveAndFlush(category);

            log.info("Category created: id={}, name='{}'",
                    savedCategory.getId(), savedCategory.getName());

            return categoryMapper.toResponse(savedCategory);
        } catch (DataIntegrityViolationException ex) {
            log.warn("Category with name='{}' already exists", name, ex);
            throw DuplicateCategoryException.forName(name);
        }
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(value = "categories")
    @CircuitBreaker(name = "itemCache", fallbackMethod = "getCategoriesFallback")
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

    @SuppressWarnings("unused")
    public List<CategoryResponse> getCategoriesFallback(Throwable t) {
        log.warn("CircuitBreaker itemCache is open for categories, fallback to DB");
        return categoryRepository.findAllByOrderByNameAsc()
                .stream()
                .map(categoryMapper::toResponse)
                .toList();
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
    @Caching(evict = {
        @CacheEvict(value = "categories", allEntries = true),
        @CacheEvict(value = "item", allEntries = true)
    })
    public CategoryResponse updateCategory(
            Long categoryId,
            UpdateCategoryRequest request) {

        log.debug("Updating category with id={}", categoryId);

        Category category = findCategory(categoryId);

        String name = request.name().trim();

        if (categoryRepository.existsByNameIgnoreCaseAndIdNot(
                name, categoryId)) {

            log.warn("Category with name='{}' already exists", name);
            throw DuplicateCategoryException.forName(name);
        }

        category.setName(name);

        try {
            Category savedCategory = categoryRepository.saveAndFlush(category);

            log.info("Category updated: id={}, name='{}'",
                    savedCategory.getId(), savedCategory.getName());

            return categoryMapper.toResponse(savedCategory);
        } catch (DataIntegrityViolationException ex) {
            log.warn("Category with name='{}' already exists", name, ex);
            throw DuplicateCategoryException.forName(name);
        }
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
