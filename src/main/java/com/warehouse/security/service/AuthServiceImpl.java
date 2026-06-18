package com.warehouse.security.service;

import com.warehouse.dto.request.security.LoginRequest;
import com.warehouse.dto.response.security.LoginResponse;
import com.warehouse.metric.MetricService;
import com.warehouse.security.JwtUtil;
import com.warehouse.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final AuthenticationManager authenticationManager;
    private final JwtUtil jwtUtil;
    private final MetricService metricService;

    @Override
    public LoginResponse login(LoginRequest request) {
        log.debug("Attempting login for user: {}", request.username());

        try {
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.username(), request.password())
            );

            UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();

            List<String> roles = authentication.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .toList();

            String token = jwtUtil.generateToken(principal.getUsername(), principal.getId(), roles);
            long expiresIn = jwtUtil.getExpirationMs();

            log.info("User '{}' successfully authenticated", request.username());
            metricService.increment("warehouse.auth.login.success.total");

            return new LoginResponse(token, expiresIn);
        } catch (AuthenticationException e) {
            metricService.increment("warehouse.auth.login.failure.total");
            throw e;
        }
    }
}