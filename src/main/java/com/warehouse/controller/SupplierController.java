package com.warehouse.controller;

import com.warehouse.dto.request.supplier.CreateSupplierRequest;
import com.warehouse.dto.request.supplier.UpdateSupplierRequest;
import com.warehouse.dto.response.supplier.SupplierResponse;
import com.warehouse.service.supplier.SupplierService;
import com.warehouse.web.ApiPaths;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping({ApiPaths.V1_API_ROOT + "/suppliers", ApiPaths.LEGACY_API_ROOT + "/suppliers"})
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Поставщики", description = "Управление поставщиками")
@SecurityRequirement(name = "bearerAuth")
public class SupplierController {

    private final SupplierService supplierService;

    @Operation(summary = "Список поставщиков")
    @GetMapping
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasRole('ADMIN')")
    public List<SupplierResponse> getSuppliers() {
        log.debug("Received get suppliers request");
        return supplierService.getSuppliers();
    }

    @Operation(summary = "Создать поставщика")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public SupplierResponse createSupplier(@Valid @RequestBody CreateSupplierRequest request) {
        log.debug("Received create supplier request: name={}", request.name());
        return supplierService.createSupplier(request);
    }

    @Operation(summary = "Редактировать поставщика")
    @PutMapping("/{supplierId}")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasRole('ADMIN')")
    public SupplierResponse updateSupplier(
            @PathVariable Long supplierId,
            @Valid @RequestBody UpdateSupplierRequest request) {
        log.debug("Received update supplier request: supplierId={}, name={}", supplierId, request.name());
        return supplierService.updateSupplier(supplierId, request);
    }

    @Operation(summary = "Карточка поставщика")
    @GetMapping("/{supplierId}")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasRole('ADMIN')")
    public SupplierResponse getSupplier(@PathVariable Long supplierId) {
        log.debug("Received get supplier request: supplierId={}", supplierId);
        return supplierService.getSupplier(supplierId);
    }

    @Operation(summary = "Удалить поставщика (soft delete)")
    @DeleteMapping("/{supplierId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void deactivateSupplier(@PathVariable Long supplierId) {
        log.debug("Received deactivate supplier request: supplierId={}", supplierId);
        supplierService.deactivateSupplier(supplierId);
    }
}
