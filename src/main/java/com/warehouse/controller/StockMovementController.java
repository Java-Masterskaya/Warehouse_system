package com.warehouse.controller;

import com.warehouse.dto.UserContext;
import com.warehouse.dto.request.movement.ChangeQuantityMovementRequest;
import com.warehouse.dto.response.movement.StockMovementResponse;
import com.warehouse.security.UserPrincipal;
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

@RestController
@RequestMapping("/api/movements")
@RequiredArgsConstructor
@Slf4j
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class StockMovementController {

    StockMovementService stockMovementService;

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

    @GetMapping("/{itemId}/history")
    @PreAuthorize("hasAnyRole('ADMIN','USER')")
    public void getItemHistory() {
    }
}