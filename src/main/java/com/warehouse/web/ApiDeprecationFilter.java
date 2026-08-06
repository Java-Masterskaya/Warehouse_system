package com.warehouse.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Advertises the planned retirement of temporary, unversioned API aliases.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class ApiDeprecationFilter extends OncePerRequestFilter {

    public static final String DEPRECATION_HEADER = "Deprecation";
    public static final String SUNSET_HEADER = "Sunset";

    private static final String DEPRECATION_VALUE = "true";

    private final String sunset;

    public ApiDeprecationFilter(@Value("${app.api.deprecation.sunset}") String sunset) {
        this.sunset = sunset;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (ApiPaths.isLegacyPath(pathWithinApplication(request))) {
            response.setHeader(DEPRECATION_HEADER, DEPRECATION_VALUE);
            response.setHeader(SUNSET_HEADER, sunset);
        }

        filterChain.doFilter(request, response);
    }

    private String pathWithinApplication(HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (!contextPath.isEmpty() && requestUri.startsWith(contextPath)) {
            return requestUri.substring(contextPath.length());
        }
        return requestUri;
    }
}
