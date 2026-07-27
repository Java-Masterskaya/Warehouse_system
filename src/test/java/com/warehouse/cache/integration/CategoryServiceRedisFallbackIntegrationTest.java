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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.cache.type=redis",
        "spring.data.redis.host=invalid",
        "spring.data.redis.port=9999",
        "spring.data.redis.timeout=500ms",
        "resilience4j.circuitbreaker.instances.itemCache.minimum-number-of-calls=3",
        "resilience4j.circuitbreaker.instances.itemCache.sliding-window-size=5",
        "resilience4j.circuitbreaker.instances.itemCache.wait-duration-in-open-state=5s",
        "resilience4j.circuitbreaker.instances.itemCache.permitted-number-of-calls-in-half-open-state=2"
})
class CategoryServiceRedisFallbackIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private CategoryService categoryService;

    @Autowired
    private CategoryRepository categoryRepository;

    @BeforeEach
    void setUp() {
        categoryRepository.deleteAll();
        categoryRepository.save(Category.builder().name("Electronics").build());
        categoryRepository.save(Category.builder().name("Books").build());
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
