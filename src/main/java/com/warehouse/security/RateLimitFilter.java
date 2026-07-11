package com.warehouse.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.warehouse.dto.request.security.LoginRequest;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.Refill;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
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
            long waitIp = getSecondsToWait("rl:login:ip:" + ip, 5, Duration.ofMinutes(1));
            if (waitIp > 0) {
                renderError(response, "Too many login attempts from this IP.", waitIp);
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
                long waitUser = getSecondsToWait("rl:login:user:" + username, 3, Duration.ofMinutes(1));
                if (waitUser > 0) {
                    renderError(response, "Too many login attempts for this user.", waitUser);
                    return;
                }
            }
        } else if (path.startsWith("/api/movements") && isWriteMethod(method)) {
            // Лимитирование write-эндпоинтов движений (работает по Principal из SecurityContext)
            long waitIp = getSecondsToWait("rl:movements:ip:" + ip, 60, Duration.ofMinutes(1));
            if (waitIp > 0) {
                renderError(response, "Rate limit exceeded for actions from this IP.", waitIp);
                return;
            }

            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getName())) {
                String username = auth.getName();
                long waitUser = getSecondsToWait("rl:movements:user:" + username, 30, Duration.ofMinutes(1));
                if (waitUser > 0) {
                    renderError(response, "Rate limit exceeded for this user.", waitUser);
                    return;
                }
            }
        }

        // Важно: передаём requestToProcess (он может быть обёрнут в CachedBody)
        filterChain.doFilter(requestToProcess, response);
    }

    // Метод проверки лимита
    private long getSecondsToWait(String key, long capacity, Duration period) {
        BucketConfiguration config = BucketConfiguration.builder()
                .addLimit(Bandwidth.classic(capacity, Refill.greedy(capacity, period)))
                .build();

        Bucket bucket = proxyManager.builder().build(key.getBytes(), config);
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);

        if (probe.isConsumed()) {
            return -1; // Доступ разрешён
        }

        // Переводим наносекунды в секунды с округлением вверх
        long nanos = probe.getNanosToWaitForRefill();
        return (long) Math.ceil((double) nanos / 1_000_000_000_000L);
    }

    // Метод проверяет, имеем ли мы дело с write-эндпоинтом
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

    // Метод рендерит предупреждение о превышении лимита
    private void renderError(HttpServletResponse response, String message, long secondsToWait) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType("application/json");

        // Установка стандартного заголовка Retry-After в секундах
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(secondsToWait));

        // Для большей информативности дублируем информацию в само тело JSON
        String jsonResponse = String.format(
                "{\"error\": \"%s\", \"retry_after_seconds\": %d}",
                message,
                secondsToWait
        );

        response.getWriter().write(jsonResponse);
    }
}
