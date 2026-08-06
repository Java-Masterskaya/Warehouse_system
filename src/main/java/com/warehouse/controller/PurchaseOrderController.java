package com.warehouse.controller;

import com.warehouse.dto.UserContext;
import com.warehouse.dto.request.purchaseorder.CreatePurchaseOrderRequest;
import com.warehouse.dto.request.purchaseorder.ReceivePurchaseOrderRequest;
import com.warehouse.dto.response.PageResponse;
import com.warehouse.dto.response.purchaseorder.PurchaseOrderResponse;
import com.warehouse.security.UserPrincipal;
import com.warehouse.service.purchaseorder.PurchaseOrderService;
import com.warehouse.web.ApiPaths;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({ApiPaths.V1_API_ROOT + "/purchase-orders", ApiPaths.LEGACY_API_ROOT + "/purchase-orders"})
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Заказы поставщикам", description = "Управление заказами поставки")
@SecurityRequirement(name = "bearerAuth")
public class PurchaseOrderController {

    private final PurchaseOrderService purchaseOrderService;

    @Operation(summary = "Создать заказ поставщику")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public PurchaseOrderResponse createPurchaseOrder(
            @Valid @RequestBody CreatePurchaseOrderRequest request) {

        log.debug("Received create purchase order request: supplierId={}, itemsCount={}",
                request.supplierId(), request.items().size());

        return purchaseOrderService.createPurchaseOrder(request);
    }

    @Operation(summary = "Разместить заказ у поставщика")
    @PostMapping("/{purchaseOrderId}/place")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasRole('ADMIN')")
    public PurchaseOrderResponse placePurchaseOrder(
            @PathVariable Long purchaseOrderId) {

        log.debug("Received place purchase order request: purchaseOrderId={}",
                purchaseOrderId);

        return purchaseOrderService.placePurchaseOrder(purchaseOrderId);
    }

    @Operation(summary = "Принять товар по заказу поставки")
    @PostMapping("/{purchaseOrderId}/receive")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasRole('ADMIN')")
    public PurchaseOrderResponse receivePurchaseOrder(
            @PathVariable Long purchaseOrderId,
            @Valid @RequestBody ReceivePurchaseOrderRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {

        log.debug("Received purchase order receipt request: purchaseOrderId={}, itemsCount={}",
                purchaseOrderId, request.items().size());

        UserContext context = new UserContext(
                currentUser.getId(),
                currentUser.getUsername()
        );

        return purchaseOrderService.receivePurchaseOrder(
                purchaseOrderId,
                request,
                context
        );
    }

    @Operation(summary = "Получить заказ поставщику")
    @GetMapping("/{purchaseOrderId}")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasRole('ADMIN')")
    public PurchaseOrderResponse getPurchaseOrder(
            @PathVariable Long purchaseOrderId) {

        log.debug("Received get purchase order request: purchaseOrderId={}",
                purchaseOrderId);

        return purchaseOrderService.getPurchaseOrder(purchaseOrderId);
    }

    @Operation(summary = "Список заказов поставщикам")
    @GetMapping
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasRole('ADMIN')")
    public PageResponse<PurchaseOrderResponse> getPurchaseOrders(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        log.debug(
                "Received get purchase orders request: page={}, size={}",
                page,
                size
        );

        return purchaseOrderService.getPurchaseOrders(page, size);
    }
}
