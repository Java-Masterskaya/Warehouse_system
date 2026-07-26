package com.warehouse.cache.integration;

import com.warehouse.AbstractIntegrationTest;
import com.warehouse.dto.response.category.CategoryResponse;
import com.warehouse.entity.Category;
import com.warehouse.repository.CategoryRepository;
import com.warehouse.repository.ItemRepository;
import com.warehouse.repository.StockMovementRepository;
import com.warehouse.repository.StockRepository;
import com.warehouse.service.category.CategoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Интеграционный тест для проверки кэширования категорий товаров.
 */
@SpringBootTest
class CategoryCacheTest extends AbstractIntegrationTest {

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private StockRepository stockRepository;

    @Autowired
    private StockMovementRepository stockMovementRepository;

    @Autowired
    private CategoryService categoryService;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private CacheManager cacheManager;

    @BeforeEach
    void setUp() {
        stockMovementRepository.deleteAllInBatch();
        stockRepository.deleteAllInBatch();
        itemRepository.deleteAllInBatch();
        categoryRepository.deleteAllInBatch();

        categoryRepository.save(
                Category.builder()
                        .name("Электроника")
                        .build()
        );

        categoryRepository.save(
                Category.builder()
                        .name("Мебель")
                        .build()
        );
    }

    /**
     * getCategories возвращает список всех категорий.
     */
    @Test
    void getCategoriesShouldReturnAllCategories() {
        List<CategoryResponse> categories = categoryService.getCategories();

        assertThat(categories)
                .extracting(CategoryResponse::name)
                .containsExactly("Мебель", "Электроника");
    }

    /**
     * getCategories возвращает данные из кэша.
     */
    @Test
    void getCategoriesShouldBeCached() {
        List<CategoryResponse> firstCall = categoryService.getCategories();

        categoryRepository.save(
                Category.builder()
                        .name("Компьютеры")
                        .build()
        );

        List<CategoryResponse> secondCall = categoryService.getCategories();

        assertThat(secondCall).isEqualTo(firstCall);

        assertThat(secondCall)
                .extracting(CategoryResponse::name)
                .containsExactly("Мебель", "Электроника");
    }
}