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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisClusterConnection;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisSentinelConnection;
import org.springframework.test.annotation.DirtiesContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@DirtiesContext
class CategoryServiceRedisFallbackIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private CategoryService categoryService;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private RedisConnectionFactory redisConnectionFactory;

    @BeforeEach
    void setUp() {
        categoryRepository.deleteAll();
        categoryRepository.save(Category.builder().name("Books").build());
        categoryRepository.save(Category.builder().name("Electronics").build());
    }

    @Test
    void redisShouldBeUnavailable() {
        assertThatThrownBy(() -> redisConnectionFactory.getConnection())
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

    @TestConfiguration
    static class RedisUnavailableConfig {

        @Bean
        @Primary
        public RedisConnectionFactory stubRedisConnectionFactory() {
            return new RedisConnectionFactory() {
                @Override
                public RedisConnection getConnection() {
                    throw new RuntimeException("Redis is down");
                }

                @Override
                public RedisClusterConnection getClusterConnection() {
                    throw new RuntimeException("Redis is down");
                }

                @Override
                public RedisSentinelConnection getSentinelConnection() {
                    throw new RuntimeException("Redis is down");
                }

                @Override
                public boolean getConvertPipelineAndTxResults() {
                    return false;
                }

                @Override
                public DataAccessException translateExceptionIfPossible(RuntimeException ex) {
                    return null;
                }
            };
        }
    }
}
