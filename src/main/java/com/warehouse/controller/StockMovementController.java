package com.warehouse.controller;

import com.warehouse.dto.UserContext;
import com.warehouse.dto.request.movement.ChangeQuantityMovementRequest;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/movements")
@RequiredArgsConstructor
@Slf4j
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@Tag(name = "Движения товара", description = "Поступление и списание (только ADMIN)")
@SecurityRequirement(name = "bearerAuth")
public class StockMovementController {

    StockMovementService stockMovementService;

    @Operation(summary = "Зарегистрировать поступление")
    @PostMapping("/receive")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasRole('ADMIN')")
    public StockMovementResponse registerReceipt(
            @Valid @RequestBody ChangeQuantityMovementRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        log.debug("Received stock movement request: itemId={}, quantity={}", request.itemId(), request.quantity());
        return stockMovementService.registerReceipt(
                request, new UserContext(currentUser.getId(), currentUser.getUsername()));
    }

    @Operation(summary = "Списать товар")
    @PostMapping("/write-off")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasRole('ADMIN')")
    public StockMovementResponse writeOffReceipt(@Valid @RequestBody ChangeQuantityMovementRequest request,
                                                 @AuthenticationPrincipal UserPrincipal currentUser) {
        log.debug("Received stock movement writeOff request: itemId={}, quantity={}", request.itemId(),
                request.quantity());
        return stockMovementService.writeOffReceipt(
                request, new UserContext(currentUser.getId(), currentUser.getUsername()));
    }
}