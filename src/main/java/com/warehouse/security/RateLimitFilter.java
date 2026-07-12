package com.warehouse.security;

import com.warehouse.security.config.RateLimitProperties;
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
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimitProperties properties;
    private final ProxyManager<byte[]> proxyManager;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getRequestURI();
        String method = request.getMethod();
        String ip = getClientIp(request);

        // Ссылка на запрос, которую мы сможем подменить на обёртку с кэшем
        HttpServletRequest requestToProcess = request;

        // Лимитирование POST /api/auth/login (JSON Body) -> Проверяем ТОЛЬКО IP на входе
        if ("/api/auth/login".equals(path) && "POST".equalsIgnoreCase(method)) {
            // Лимит per IP: 5 запросов в минуту
            RateLimitProperties.LimitConfig loginConfig = properties.login();

            long waitIp = getSecondsToWait("rl:login:ip:" + ip, loginConfig.ip());
            if (waitIp > 0) {
                renderError(response, "Too many login attempts from this IP.", waitIp);
                return;
            }

            // Передаём обёртку дальше в Spring Security
            requestToProcess = new CachedBodyHttpServletRequest(request);
        } else if (path.startsWith("/api/movements") && isWriteMethod(method)) {
            // Лимитирование write-эндпоинтов движений (работает по Principal из SecurityContext)
            RateLimitProperties.LimitConfig movementsConfig = properties.movements();

            long waitIp = getSecondsToWait("rl:movements:ip:" + ip, movementsConfig.ip());
            if (waitIp > 0) {
                renderError(response, "Rate limit exceeded for actions from this IP.", waitIp);
                return;
            }

            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getName())) {
                String username = auth.getName();
                long waitUser = getSecondsToWait("rl:movements:user:" + username, movementsConfig.username());
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
    private long getSecondsToWait(String key, RateLimitProperties.BandwidthConfig config) {
        BucketConfiguration bucketConfig = BucketConfiguration.builder()
                .addLimit(Bandwidth.classic(
                        config.capacity(),
                        Refill.greedy(config.refillTokens(), config.duration())
                ))
                .build();

        Bucket bucket = proxyManager.builder().build(key.getBytes(), bucketConfig);
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);

        if (probe.isConsumed()) {
            return -1; // Доступ разрешён
        }

        // Переводим наносекунды в секунды с округлением вверх
        long nanos = probe.getNanosToWaitForRefill();
        return (long) Math.ceil((double) nanos / 1_000_000_000L);
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
