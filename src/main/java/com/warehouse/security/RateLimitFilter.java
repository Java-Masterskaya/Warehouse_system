package com.warehouse.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.warehouse.dto.request.security.LoginRequest;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.Refill;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;

@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private final ProxyManager<byte[]> proxyManager;
    private final ObjectMapper objectMapper;

    public RateLimitFilter(ProxyManager<byte[]> proxyManager, ObjectMapper objectMapper) {
        this.proxyManager = proxyManager;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getRequestURI();
        String method = request.getMethod();
        String ip = getClientIp(request);

        // Ссылка на запрос, которую мы сможем подменить на обёртку с кэшем
        HttpServletRequest requestToProcess = request;

        // Лимитирование POST /api/auth/login (JSON Body)
        if ("/api/auth/login".equals(path) && "POST".equalsIgnoreCase(method)) {
            // Лимит per IP: 5 запросов в минуту
            if (!isAllowed("rl:login:ip:" + ip, 5, Duration.ofMinutes(1))) {
                renderError(response, "Too many login attempts from this IP.");
                return;
            }

            // Оборачиваем запрос для безопасного чтения JSON
            CachedBodyHttpServletRequest cachedRequest = new CachedBodyHttpServletRequest(request);
            requestToProcess = new CachedBodyHttpServletRequest(request); // Передаём обёртку дальше в Spring Security

            String username = null;
            try {
                // Извлекаем username из кэшированного тела
                LoginRequest loginBody = objectMapper.readValue(cachedRequest.getCachedBody(), LoginRequest.class);
                if (loginBody != null) {
                    username = loginBody.username();
                }
            } catch (Exception e) {
                // Если JSON невалидный, даём Spring Security / контроллеру обработать ошибку штатно
            }

            // Лимит per Username: 3 запроса в минуту
            if (username != null && !username.isBlank()) {
                if (!isAllowed("rl:login:user:" + username, 3, Duration.ofMinutes(1))) {
                    renderError(response, "Too many login attempts for this user.");
                    return;
                }
            }
        } else if (path.startsWith("/api/movements") && isWriteMethod(method)) {
            // Лимитирование write-эндпоинтов движений (работает по Principal из SecurityContext)
            if (!isAllowed("rl:movements:ip:" + ip, 60, Duration.ofMinutes(1))) {
                renderError(response, "Rate limit exceeded for actions from this IP.");
                return;
            }

            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getName())) {
                String username = auth.getName();
                if (!isAllowed("rl:movements:user:" + username, 30, Duration.ofMinutes(1))) {
                    renderError(response, "Rate limit exceeded for this user.");
                    return;
                }
            }
        }

        // Важно: передаём requestToProcess (он может быть обёрнут в CachedBody)
        filterChain.doFilter(requestToProcess, response);
    }

    private boolean isAllowed(String key, long capacity, Duration period) {
        BucketConfiguration config = BucketConfiguration.builder()
                .addLimit(Bandwidth.classic(capacity, Refill.greedy(capacity, period)))
                .build();
        Bucket bucket = proxyManager.builder().build(key.getBytes(), config);
        return bucket.tryConsume(1);
    }

    private boolean isWriteMethod(String method) {
        return "POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method)
                || "PATCH".equalsIgnoreCase(method) || "DELETE".equalsIgnoreCase(method);
    }

    private String getClientIp(HttpServletRequest request) {
        String xfHeader = request.getHeader("X-Forwarded-For");
        if (xfHeader != null && !xfHeader.isBlank()) {
            return xfHeader.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private void renderError(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType("application/json");
        response.getWriter().write("{\"error\": \"" + message + "\"}");
    }
}
