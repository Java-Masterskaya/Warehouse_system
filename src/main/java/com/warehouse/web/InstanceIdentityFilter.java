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
 * Exposes the application instance that handled an HTTP request.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class InstanceIdentityFilter extends OncePerRequestFilter {

    public static final String INSTANCE_HEADER = "X-Warehouse-Instance";

    private final String instanceId;

    public InstanceIdentityFilter(
            @Value("${app.instance-id:${INSTANCE_ID:${HOSTNAME:local}}}") String instanceId) {
        this.instanceId = instanceId;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        response.setHeader(INSTANCE_HEADER, instanceId);
        filterChain.doFilter(request, response);
    }
}
