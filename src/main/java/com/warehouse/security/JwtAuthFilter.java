package com.warehouse.security;

import com.warehouse.security.service.TokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Фильтр для аутентификации запросов по JWT токену.
 * <p>
 * Выполняет:
 * <ul>
 *   <li>Извлечение токена из заголовка Authorization</li>
 *   <li>Проверку токена в blacklist (мгновенный отзыв)</li>
 *   <li>Валидацию JWT и извлечение данных пользователя</li>
 *   <li>Установку аутентификации в SecurityContext</li>
 * </ul>
 * </p>
 *
 * <p>
 * <b>Производительность:</b> Проверка blacklist выполняется через Redis lookup (~1-2ms),
 * что обеспечивает мгновенный отзыв токенов без нагрузки на БД.
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final int BEARER_PREFIX_LENGTH = BEARER_PREFIX.length();

    private final JwtUtil jwtUtil;
    private final TokenService tokenService;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {

        String token = extractToken(request);

        if (token == null) {
            filterChain.doFilter(request, response);
            return;
        }

        if (tokenService.isAccessTokenBlacklisted(token)) {
            log.warn("Blacklisted token used");
            sendUnauthorized(response, "Token has been revoked");
            return;
        }

        var payloadOpt = jwtUtil.parseAccessToken(token);
        if (payloadOpt.isEmpty()) {
            log.warn("Invalid or non-access JWT token");
            sendUnauthorized(response, "Invalid token");
            return;
        }

        var payload = payloadOpt.get();
        UserPrincipal userPrincipal = new UserPrincipal(
                payload.userId(),
                payload.username(),
                null,
                true,
                payload.roles().stream()
                        .map(SimpleGrantedAuthority::new)
                        .toList()
        );

        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        userPrincipal,
                        null,
                        userPrincipal.getAuthorities()
                );
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);

        log.debug("Authenticated user '{}' from JWT", payload.username());

        filterChain.doFilter(request, response);
    }

    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (StringUtils.hasText(header) && header.startsWith(BEARER_PREFIX)) {
            return header.substring(BEARER_PREFIX_LENGTH);
        }
        return null;
    }

    private void sendUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(String.format(
                "{\"error\":\"UNAUTHORIZED\",\"message\":\"%s\"}",
                message
        ));
        SecurityContextHolder.clearContext();
    }
}
