package com.warehouse.controller;

import com.warehouse.dto.UserContext;
import com.warehouse.dto.request.movement.StocktakeRequest;
import com.warehouse.dto.response.movement.StockMovementResponse;
import com.warehouse.security.UserPrincipal;
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
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/inventory")
@RequiredArgsConstructor
@Slf4j
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@Tag(name = "Инвентаризация", description = "Корректировка остатков (только ADMIN)")
@SecurityRequirement(name = "bearerAuth")
public class InventoryController {

    StockMovementService stockMovementService;

    @Operation(summary = "Провести инвентаризацию")
    @PostMapping("/stocktake")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasRole('ADMIN')")
    public StockMovementResponse stocktake(
            @Valid @RequestBody StocktakeRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        log.debug("Stocktake request: itemId={}, counted={}", request.itemId(), request.countedQuantity());
        return stockMovementService.stocktake(request, new UserContext(currentUser.getId(), currentUser.getUsername()));
    }
}