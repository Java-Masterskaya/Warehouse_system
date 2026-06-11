package com.warehouse.controller;

import com.warehouse.dto.request.movement.CreateStockMovementRequest;
import com.warehouse.dto.response.movement.StockMovementResponse;
import com.warehouse.entity.User;
import com.warehouse.repository.UserRepository;
import com.warehouse.service.movement.StockMovementService;
import jakarta.validation.Valid;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Эндпоинты для управления движениями товаров на складе.
 * Предоставляет операции для регистрации прихода товара.
 */
@RestController
@RequestMapping("api/movements")
@RequiredArgsConstructor
@Slf4j
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class StockMovementController {

    StockMovementService stockMovementService;
    UserRepository userRepository;

    /**
     * Регистрирует приход товара на склад.
     * Доступно только пользователям с ролью ADMIN.
     *
     * @param authentication текущая аутентификация
     * @param request        запрос на создание движения товара
     * @return ответ с информацией о движении товара
     */
    @PostMapping("/receive")
    @PreAuthorize("hasRole('ADMIN')")
    public StockMovementResponse registerReceipt(
        Authentication authentication,
        @Valid @RequestBody CreateStockMovementRequest request
    ) {
        log.debug("Received stock movement request: itemId={}, quantity={}", request.itemId(), request.quantity());
        User user = userRepository.findByUsername(authentication.getName())
            .orElseThrow(() -> {
                log.warn("User not found: username={}", authentication.getName());
                return new RuntimeException("User not found");
            });
        return stockMovementService.registerReceipt(user, request);
    }
}