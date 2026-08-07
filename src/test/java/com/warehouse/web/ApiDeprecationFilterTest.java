package com.warehouse.web;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class ApiDeprecationFilterTest {

    private static final String SUNSET = "Thu, 05 Aug 2027 00:00:00 GMT";

    private final ApiDeprecationFilter filter = new ApiDeprecationFilter(SUNSET);

    @ParameterizedTest
    @ValueSource(strings = {
        "/api",
        "/api/",
        "/api/items",
        "/admin/backfill",
        "/admin/backfill/",
        "/admin/backfill/barcode"
    })
    void shouldAddDeprecationHeadersToLegacyPaths(String requestUri) throws Exception {
        MockHttpServletResponse response = performRequest(requestUri);

        assertDeprecationHeaders(response);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/api/v1",
        "/api/v1/",
        "/api/v1/items",
        "/api/v2/items",
        "/api/v10/items",
        "/swagger-ui/index.html",
        "/v3/api-docs",
        "/actuator/health"
    })
    void shouldNotAddDeprecationHeadersToCurrentOrInfrastructurePaths(String requestUri) throws Exception {
        MockHttpServletResponse response = performRequest(requestUri);

        assertThat(response.getHeader(ApiDeprecationFilter.DEPRECATION_HEADER)).isNull();
        assertThat(response.getHeader(ApiDeprecationFilter.SUNSET_HEADER)).isNull();
    }

    @Test
    void shouldEvaluatePathWithoutContextPath() throws Exception {
        MockHttpServletRequest request = request("/warehouse/api/items");
        request.setContextPath("/warehouse");
        MockHttpServletResponse response = performRequest(request);

        assertDeprecationHeaders(response);
    }

    @Test
    void shouldNotMarkVersionedPathWithinContextPathAsDeprecated() throws Exception {
        MockHttpServletRequest request = request("/warehouse/api/v1/items");
        request.setContextPath("/warehouse");
        MockHttpServletResponse response = performRequest(request);

        assertThat(response.getHeader(ApiDeprecationFilter.DEPRECATION_HEADER)).isNull();
        assertThat(response.getHeader(ApiDeprecationFilter.SUNSET_HEADER)).isNull();
    }

    @Test
    void shouldPreserveDeprecationHeadersWhenDownstreamReturnsError() throws Exception {
        MockHttpServletRequest request = request("/api/items");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) ->
                ((HttpServletResponse) servletResponse).sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR));

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        assertDeprecationHeaders(response);
    }

    private MockHttpServletResponse performRequest(String requestUri) throws Exception {
        return performRequest(request(requestUri));
    }

    private MockHttpServletResponse performRequest(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    private MockHttpServletRequest request(String requestUri) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI(requestUri);
        return request;
    }

    private void assertDeprecationHeaders(MockHttpServletResponse response) {
        assertThat(response.getHeader(ApiDeprecationFilter.DEPRECATION_HEADER)).isEqualTo("true");
        assertThat(response.getHeader(ApiDeprecationFilter.SUNSET_HEADER)).isEqualTo(SUNSET);
    }
}
