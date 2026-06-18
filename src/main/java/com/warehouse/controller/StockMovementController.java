package com.warehouse.controller;

import com.warehouse.dto.request.movement.ChangeQuantityMovementRequest;
import com.warehouse.dto.response.PageResponse;
import com.warehouse.dto.response.movement.StockMovementHistoryResponse;
import com.warehouse.dto.response.movement.StockMovementResponse;
import com.warehouse.entity.MovementType;
import com.warehouse.entity.User;
import com.warehouse.service.movement.StockMovementService;
import jakarta.validation.Valid;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;

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
            @Valid @RequestBody ChangeQuantityMovementRequest request,
            @AuthenticationPrincipal User currentUser) {
        log.debug("Received stock movement register request: itemId={}, quantity={}", request.itemId(),
                request.quantity());
        return stockMovementService.registerReceipt(request, currentUser);
    }

    @PostMapping("/write-off")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasRole('ADMIN')")
    public StockMovementResponse writeOffReceipt(@Valid @RequestBody ChangeQuantityMovementRequest request,
                                                 @AuthenticationPrincipal User currentUser) {
        log.debug("Received stock movement writeOff request: itemId={}, quantity={}", request.itemId(),
                request.quantity());
        return stockMovementService.writeOffReceipt(request, currentUser);
    }

    /**
     * Показывает историю движения указанного товара.
     * Поддерживает фильтрацию по типу движения и пагинацию результатов.
     *
     * @param itemId идентификатор товара
     * @param type   необязательный фильтр по типу движения (RECEIVE или WRITE_OFF)
     * @param page   номер страницы (начиная с 0)
     * @param size   количество записей на странице
     * @return история движений товара в виде страницы результатов
     */
    @GetMapping("/{itemId}/history")
    @ResponseStatus(HttpStatus.OK)
    public PageResponse<StockMovementHistoryResponse> getItemMovementHistory(@PathVariable Long itemId,
                                                      @RequestParam(required = false) MovementType type,
                                                      @RequestParam(defaultValue = "0") int page,
                                                      @RequestParam(defaultValue = "20") int size) {
        return stockMovementService.getItemMovementHistory(itemId, type, page, size);
    }

}
