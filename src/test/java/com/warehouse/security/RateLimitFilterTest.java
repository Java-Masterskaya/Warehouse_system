package com.warehouse.security;

import com.warehouse.web.ApiPaths;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for version-aware rate-limit route matching.
 */
class RateLimitFilterTest {

    @Test
    void recognizesLoginForLegacyAndV1Paths() {
        assertThat(RateLimitFilter.isLoginRequest(
                ApiPaths.LEGACY_API_ROOT + "/auth/login", "POST"
        )).isTrue();
        assertThat(RateLimitFilter.isLoginRequest(
                ApiPaths.V1_API_ROOT + "/auth/login", "post"
        )).isTrue();
    }

    @Test
    void rejectsSimilarLoginPathsAndNonPostRequests() {
        assertThat(RateLimitFilter.isLoginRequest("/api/auth/login/extra", "POST")).isFalse();
        assertThat(RateLimitFilter.isLoginRequest("/api/v1/auth/login-extra", "POST")).isFalse();
        assertThat(RateLimitFilter.isLoginRequest("/api/v11/auth/login", "POST")).isFalse();
        assertThat(RateLimitFilter.isLoginRequest("/api/v1/auth/login", "GET")).isFalse();
    }

    @Test
    void recognizesMovementWritesForLegacyAndV1Paths() {
        assertThat(RateLimitFilter.isMovementsWriteRequest(
                ApiPaths.LEGACY_API_ROOT + "/movements/receive", "POST"
        )).isTrue();
        assertThat(RateLimitFilter.isMovementsWriteRequest(
                ApiPaths.V1_API_ROOT + "/movements/write-off", "post"
        )).isTrue();
    }

    @Test
    void rejectsSimilarMovementPathsAndReadRequests() {
        assertThat(RateLimitFilter.isMovementsWriteRequest("/api/movements-extra/receive", "POST")).isFalse();
        assertThat(RateLimitFilter.isMovementsWriteRequest("/api/v1/movements2/receive", "POST")).isFalse();
        assertThat(RateLimitFilter.isMovementsWriteRequest("/api/v11/movements/receive", "POST")).isFalse();
        assertThat(RateLimitFilter.isMovementsWriteRequest("/api/v1/movements/receive", "GET")).isFalse();
    }
}
