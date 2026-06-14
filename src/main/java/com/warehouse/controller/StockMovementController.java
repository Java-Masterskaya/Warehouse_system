package com.warehouse.controller;

import com.warehouse.dto.request.movement.CreateStockMovementRequest;
import com.warehouse.dto.response.movement.StockMovementResponse;
import com.warehouse.security.AppUserDetails;
import com.warehouse.service.movement.StockMovementService;
import jakarta.validation.Valid;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Эндпоинты для управления движениями товаров на складе.
 * Предоставляет операции для регистрации прихода товара.
 */
@RestController
@RequestMapping("/api/movements")
@RequiredArgsConstructor
@Slf4j
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class StockMovementController {

    StockMovementService stockMovementService;

    /**
     * Регистрирует приход товара на склад.
     * Доступно только пользователям с ролью ADMIN.
     *
     * @param request     запрос на создание движения товара
     * @param currentUser текущий аутентифицированный пользователь (из @AuthenticationPrincipal)
     * @return ответ с информацией о движении товара
     */
    @PostMapping("/receive")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasRole('ADMIN')")
    public StockMovementResponse registerReceipt(
            @Valid @RequestBody CreateStockMovementRequest request,
            @AuthenticationPrincipal AppUserDetails currentUser) {
        log.debug("Received stock movement request: itemId={}, quantity={}", request.itemId(), request.quantity());
        return stockMovementService.registerReceipt(request, currentUser.getId(), currentUser.getUsername());
    }
}
