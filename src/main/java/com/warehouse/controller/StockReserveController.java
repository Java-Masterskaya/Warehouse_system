package com.warehouse.controller;

import com.warehouse.dto.UserContext;
import com.warehouse.dto.request.reservation.ReservationActionRequest;
import com.warehouse.dto.request.reservation.ReserveRequest;
import com.warehouse.security.UserPrincipal;
import com.warehouse.service.reservation.StockReserveService;
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
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;

@RestController
@RequestMapping("/api/stock")
@RequiredArgsConstructor
@Slf4j
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@Tag(name = "Резервирование остатков", description = "Регистрация и отмена брони (только ADMIN)")
@SecurityRequirement(name = "bearerAuth")
@Validated
public class StockReserveController {

    StockReserveService stockReserveService;

    @Operation(summary = "Резервирование остатков")
    @PostMapping("/{itemId}/reserve")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasRole('ADMIN')")
    public void reserve(@PathVariable Long itemId, @Valid @RequestBody ReserveRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        log.debug("Reserve item request: itemId={}, quantity={}", itemId, request.quantity());
        stockReserveService.reserve(itemId, request, new UserContext(currentUser.getId(), currentUser.getUsername()));
    }

    @Operation(summary = "Отмена резервирования")
    @PostMapping("/{itemId}/release")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasRole('ADMIN')")
    public void release(@PathVariable Long itemId, @Valid @RequestBody ReservationActionRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        log.debug("Release item request: itemId={}, reservationId={}", itemId, request.reservationId());
        stockReserveService.release(itemId, request, new UserContext(currentUser.getId(), currentUser.getUsername()));
    }

    @Operation(summary = "Выкуп резерва")
    @PostMapping("/{itemId}/write-off")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasRole('ADMIN')")
    public void writeOff(@PathVariable Long itemId, @Valid @RequestBody ReservationActionRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        log.debug("Write-off item request: itemId={}, reservationId={}", itemId, request.reservationId());
        //stockReserveService.release(itemId, request, new UserContext(currentUser.getId(), currentUser.getUsername()));
    }
}
