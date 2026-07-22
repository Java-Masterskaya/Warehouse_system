package com.warehouse.controller;

import com.warehouse.dto.UserContext;
import com.warehouse.dto.request.idempotency.IdempotentRequestContext;
import com.warehouse.dto.request.movement.ChangeQuantityMovementRequest;
import com.warehouse.dto.response.PageResponse;
import com.warehouse.dto.response.movement.StockMovementHistoryResponse;
import com.warehouse.dto.response.movement.StockMovementResponse;
import com.warehouse.entity.MovementType;
import com.warehouse.security.UserPrincipal;
import com.warehouse.service.idempotency.IdempotencyService;
import com.warehouse.service.movement.StockMovementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/movements")
@RequiredArgsConstructor
@Slf4j
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@Tag(name = "Движения товара", description = "Поступление и списание (только ADMIN)")
@SecurityRequirement(name = "bearerAuth")
@Validated
public class StockMovementController {

    private static final String RECEIVE_ENDPOINT = "/api/movements/receive";
    private static final String WRITE_OFF_ENDPOINT = "/api/movements/write-off";

    StockMovementService stockMovementService;
    IdempotencyService idempotencyService;

    @Operation(summary = "Зарегистрировать поступление")
    @PostMapping("/receive")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasRole('ADMIN')")
    public StockMovementResponse registerReceipt(
            @Valid @RequestBody ChangeQuantityMovementRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        log.debug("Received stock movement request: itemId={}, quantity={}, idempotencyKey={}",
                request.itemId(), request.quantity(), idempotencyKey);

        UserContext ctx = new UserContext(currentUser.getId(), currentUser.getUsername());
        IdempotentRequestContext context = new IdempotentRequestContext(
                idempotencyKey,
                RECEIVE_ENDPOINT,
                ctx
        );

        return idempotencyService.processIdempotentRequest(
                context,
                request,
                () -> stockMovementService.registerReceipt(request, ctx)
        );
    }

    @Operation(summary = "Списать товар")
    @PostMapping("/write-off")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasRole('ADMIN')")
    public StockMovementResponse writeOffReceipt(@Valid @RequestBody ChangeQuantityMovementRequest request,
                                                 @RequestHeader(value = "Idempotency-Key", required = false)
                                                 String idempotencyKey,
                                                 @AuthenticationPrincipal UserPrincipal currentUser) {
        log.debug("Received stock movement writeOff request: itemId={}, quantity={}, idempotencyKey={}",
                request.itemId(), request.quantity(), idempotencyKey);

        UserContext ctx = new UserContext(currentUser.getId(), currentUser.getUsername());
        IdempotentRequestContext context = new IdempotentRequestContext(
                idempotencyKey,
                WRITE_OFF_ENDPOINT,
                ctx
        );

        return idempotencyService.processIdempotentRequest(
                context,
                request,
                () -> stockMovementService.writeOffReceipt(request, ctx)
        );
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
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    public PageResponse<StockMovementHistoryResponse> getItemMovementHistory(
            @PathVariable Long itemId,
            @RequestParam(required = false) MovementType type,
            @RequestParam(defaultValue = "0")
            @Min(0)
            int page,

            @RequestParam(defaultValue = "20")
            @Min(1)
            @Max(100)
            int size) {
        return stockMovementService.getItemMovementHistory(itemId, type, page, size);
    }
}