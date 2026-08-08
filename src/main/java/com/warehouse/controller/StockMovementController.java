package com.warehouse.controller;

import com.warehouse.dto.UserContext;
import com.warehouse.dto.request.idempotency.IdempotentRequestContext;
import com.warehouse.dto.request.movement.ReceiveStockRequest;
import com.warehouse.dto.request.movement.TransferStockRequest;
import com.warehouse.dto.request.movement.WriteOffStockRequest;
import com.warehouse.dto.response.movement.StockMovementHistoryPaginationResponse;
import com.warehouse.dto.response.movement.StockMovementResponse;
import com.warehouse.dto.response.movement.StockTransferResponse;
import com.warehouse.entity.MovementType;
import com.warehouse.exception.InvalidCursorException;
import com.warehouse.security.UserPrincipal;
import com.warehouse.service.idempotency.IdempotencyService;
import com.warehouse.service.import_export.CsvExportService;
import com.warehouse.service.movement.StockMovementService;
import com.warehouse.web.ApiPaths;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
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
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping({ApiPaths.V1_API_ROOT + "/movements", ApiPaths.LEGACY_API_ROOT + "/movements"})
@RequiredArgsConstructor
@Slf4j
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@Tag(name = "Движения товара", description = "Поступление, списание и переводы (только ADMIN)")
@SecurityRequirement(name = "bearerAuth")
@Validated
public class StockMovementController {

    private static final String RECEIVE_ENDPOINT   = "/api/movements/receive";
    private static final String WRITE_OFF_ENDPOINT = "/api/movements/write-off";

    StockMovementService stockMovementService;
    CsvExportService     csvExportService;
    IdempotencyService   idempotencyService;

    @Operation(summary = "Зарегистрировать поступление")
    @PostMapping("/receive")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasRole('ADMIN')")
    public StockMovementResponse registerReceipt(
            @Valid @RequestBody ReceiveStockRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal UserPrincipal currentUser
    ) {
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
    public StockMovementResponse writeOffReceipt(
            @Valid @RequestBody WriteOffStockRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false)
            String idempotencyKey,
            @AuthenticationPrincipal UserPrincipal currentUser
    ) {
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

    @Operation(summary = "Перевести товар между складами")
    @PostMapping("/transfer")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasRole('ADMIN')")
    public StockTransferResponse transfer(
            @Valid @RequestBody TransferStockRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser
    ) {
        log.debug(
                "Received stock transfer request: itemId={}, fromWarehouseId={}, toWarehouseId={}, quantity={}",
                request.itemId(),
                request.fromWarehouseId(),
                request.toWarehouseId(),
                request.quantity()
        );
        return stockMovementService.transfer(
                request,
                new UserContext(currentUser.getId(), currentUser.getUsername())
        );
    }

    /**
     * Показывает историю движения указанного товара.
     * Поддерживает фильтрацию по типу движения и пагинацию результатов.
     *
     * @param itemId идентификатор товара
     * @param type   необязательный фильтр по типу движения
     * @param page   номер страницы (начиная с 0)
     * @param size   количество записей на странице
     * @param cursor opaque keyset cursor, or an empty value for the first cursor page
     * @return история движений товара в виде страницы результатов
     */
    @GetMapping("/{itemId}/history")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    public StockMovementHistoryPaginationResponse getItemMovementHistory(
            @PathVariable Long itemId,
            @RequestParam(required = false) MovementType type,
            @Parameter(schema = @Schema(
                    type = "integer",
                    format = "int32",
                    defaultValue = "0",
                    minimum = "0"
            ))
            @RequestParam(required = false)
            @Min(0)
            Integer page,

            @RequestParam(defaultValue = "20")
            @Min(1)
            @Max(100)
            int size,
            @Parameter(
                    description = "Opaque keyset cursor. Supply an empty value to start cursor pagination.",
                    allowEmptyValue = true
            )
            @RequestParam(required = false) String cursor) {
        if (cursor != null) {
            if (page != null) {
                throw new InvalidCursorException();
            }
            return StockMovementHistoryPaginationResponse.from(
                    stockMovementService.getItemMovementHistoryByCursor(itemId, type, cursor, size)
            );
        }

        int requestedPage = 0;
        if (page != null) {
            requestedPage = page;
        }
        return StockMovementHistoryPaginationResponse.from(
                stockMovementService.getItemMovementHistory(itemId, type, requestedPage, size)
        );
    }

    @Operation(summary = "Экспорт журнала движения товаров")
    @GetMapping("/export")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<StreamingResponseBody> exportMovements(Authentication authentication) {
        SecurityContext context = SecurityContextHolder.getContext();
        StreamingResponseBody responseBody = outputStream -> {
            SecurityContextHolder.setContext(context);
            try (OutputStreamWriter writer = new OutputStreamWriter(outputStream, StandardCharsets.UTF_8)) {
                csvExportService.exportMovement(writer);
            } catch (UncheckedIOException e) {
                // Клиент отменил загрузку или обвалилась сеть — это нормальное поведение для стриминга
                log.warn("Экспорт CSV был прерван клиентом: {}", e.getMessage());
            } finally {
                // 2. ОБЯЗАТЕЛЬНО очищаем контекст безопасности после завершения потока!
                SecurityContextHolder.clearContext();
            }
        };

        return ResponseEntity.ok()
                             .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
                             .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"movements.csv\"")
                             .body(responseBody);
    }
}
