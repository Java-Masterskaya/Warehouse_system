package com.warehouse.controller;

import com.warehouse.dto.request.CreateStockMovementRequest;
import com.warehouse.dto.response.StockMovementResponse;
import com.warehouse.entity.User;
import com.warehouse.service.StockMovementService;
import jakarta.validation.Valid;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Эндпоинты для управления движениями товаров на складе. */
@RestController
@RequestMapping("api/movements")
@RequiredArgsConstructor
@Slf4j
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class StockMovementController {

    StockMovementService stockMovementService;

    /** Регистрирует приход товара.
     *
     * @param user      текущий авторизованный пользователь
     * @param request   запрос на создание движения товара
     * @return ответ с информацией о движении товара
     */
    @PostMapping("/receive")
    @PreAuthorize("hasRole('ADMIN')")
    public StockMovementResponse receiveStock(
        @AuthenticationPrincipal User user,
        @Valid @RequestBody CreateStockMovementRequest request
    ) {
        log.debug("Received stock movement request: itemId={}, quantity={}", request.itemId(), request.quantity());
        return stockMovementService.receiveStock(user, request);
    }
}