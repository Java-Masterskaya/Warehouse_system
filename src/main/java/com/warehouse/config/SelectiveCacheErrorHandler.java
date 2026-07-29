package com.warehouse.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.lang.NonNull;

import java.util.Set;

@Slf4j
public class SelectiveCacheErrorHandler implements CacheErrorHandler {

    private static final Set<String> PROPAGATE_CACHES = Set.of("item", "categories");

    @Override
    public void handleCacheGetError(@NonNull RuntimeException exception, @NonNull Cache cache, @NonNull Object key) {
        if (PROPAGATE_CACHES.contains(cache.getName())) {
            throw exception;
        }
        log.warn("Cache GET error on cache '{}' for key '{}': {}", cache.getName(), key, exception.getMessage());
    }

    @Override
    public void handleCachePutError(@NonNull RuntimeException exception, @NonNull Cache cache, @NonNull Object key, Object value) {
        log.warn("Cache PUT error on cache '{}' for key '{}': {}", cache.getName(), key, exception.getMessage());
    }

    @Override
    public void handleCacheEvictError(@NonNull RuntimeException exception, @NonNull Cache cache, @NonNull Object key) {
        log.warn("Cache EVICT error on cache '{}' for key '{}': {}", cache.getName(), key, exception.getMessage());
    }

    @Override
    public void handleCacheClearError(@NonNull RuntimeException exception, @NonNull Cache cache) {
        log.warn("Cache CLEAR error on cache '{}': {}", cache.getName(), exception.getMessage());
    }
}
