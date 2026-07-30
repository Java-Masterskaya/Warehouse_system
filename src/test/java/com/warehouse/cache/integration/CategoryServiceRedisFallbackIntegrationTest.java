package com.warehouse.cache.integration;

import com.warehouse.AbstractIntegrationTest;
import com.warehouse.dto.response.category.CategoryResponse;
import com.warehouse.entity.Category;
import com.warehouse.repository.CategoryRepository;
import com.warehouse.service.category.CategoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest
@DirtiesContext
class CategoryServiceRedisFallbackIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private CategoryService categoryService;

    @Autowired
    private CategoryRepository categoryRepository;

    @MockitoBean
    private CacheManager cacheManager;

    @BeforeEach
    void setUp() {
        categoryRepository.deleteAll();
        categoryRepository.save(Category.builder().name("Books").build());
        categoryRepository.save(Category.builder().name("Electronics").build());

        Cache cache = mock(Cache.class);
        when(cacheManager.getCache("categories")).thenReturn(cache);

        doThrow(new RuntimeException("Redis is down")).when(cache).get(any(), any(Class.class));
        doThrow(new RuntimeException("Redis is down")).when(cache).put(any(), any());
        doThrow(new RuntimeException("Redis is down")).when(cache).evict(any());
        doThrow(new RuntimeException("Redis is down")).when(cache).clear();
    }

    @Test
    void cacheShouldBeUnavailable() {
        Cache cache = cacheManager.getCache("categories");
        assertThat(cache).isNotNull();

        assertThatThrownBy(() -> cache.get("anyKey", Object.class))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Redis is down");
    }

    @Test
    void shouldReturnCategoriesWhenRedisUnavailable() {
        for (int i = 0; i < 7; i++) {
            List<CategoryResponse> categories = categoryService.getCategories();
            assertThat(categories).isNotEmpty();
            assertThat(categories).extracting(CategoryResponse::name)
                    .containsExactlyInAnyOrder("Books", "Electronics");
        }
    }
}
