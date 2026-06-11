package com.warehouse.security.service;

import com.warehouse.dto.request.security.LoginRequest;
import com.warehouse.dto.response.security.LoginResponse;
import com.warehouse.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final AuthenticationManager authenticationManager;
    private final JwtUtil jwtUtil;

    @Override
    public LoginResponse login(LoginRequest request) {
        log.debug("Attempting login for user: {}", request.username());
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.username(), request.password())
        );
        log.info("User '{}' successfully authenticated", request.username());

        List<String> roles = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList();

        String token = jwtUtil.generateToken(request.username(), roles);
        long expiresIn = jwtUtil.getExpirationMs();

        return new LoginResponse(token, expiresIn);
    }
}
