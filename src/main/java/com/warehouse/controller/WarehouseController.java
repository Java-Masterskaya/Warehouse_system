package com.warehouse.controller;

import com.warehouse.dto.request.warehouse.CreateWarehouseRequest;
import com.warehouse.dto.response.warehouse.WarehouseResponse;
import com.warehouse.service.warehouse.WarehouseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/warehouses")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Склады", description = "Управление складами")
@SecurityRequirement(name = "bearerAuth")
public class WarehouseController {

    private final WarehouseService warehouseService;

    @Operation(summary = "Создать склад")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public WarehouseResponse createWarehouse(@Valid @RequestBody CreateWarehouseRequest request) {
        log.debug("Received create warehouse request: name={}", request.name());
        return warehouseService.createWarehouse(request);
    }

    @Operation(summary = "Список складов")
    @GetMapping
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    public List<WarehouseResponse> getWarehouses() {
        log.debug("Received get warehouses request");
        return warehouseService.getWarehouses();
    }
}
