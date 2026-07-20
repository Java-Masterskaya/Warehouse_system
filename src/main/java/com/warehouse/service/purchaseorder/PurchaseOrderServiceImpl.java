package com.warehouse.service.purchaseorder;

import com.warehouse.dto.UserContext;
import com.warehouse.dto.request.movement.ChangeQuantityMovementRequest;
import com.warehouse.dto.request.purchaseorder.CreatePurchaseOrderItemRequest;
import com.warehouse.dto.request.purchaseorder.CreatePurchaseOrderRequest;
import com.warehouse.dto.request.purchaseorder.ReceivePurchaseOrderItemRequest;
import com.warehouse.dto.request.purchaseorder.ReceivePurchaseOrderRequest;
import com.warehouse.dto.response.PageResponse;
import com.warehouse.dto.response.purchaseorder.PurchaseOrderResponse;
import com.warehouse.entity.Item;
import com.warehouse.entity.PurchaseOrder;
import com.warehouse.entity.PurchaseOrderItem;
import com.warehouse.entity.PurchaseOrderStatus;
import com.warehouse.entity.Supplier;
import com.warehouse.exception.EntityNotFoundException;
import com.warehouse.exception.InvalidPurchaseOrderStatusException;
import com.warehouse.exception.PurchaseOrderOverReceiptException;
import com.warehouse.mapper.PurchaseOrderMapper;
import com.warehouse.repository.ItemRepository;
import com.warehouse.repository.PurchaseOrderItemRepository;
import com.warehouse.repository.PurchaseOrderRepository;
import com.warehouse.repository.SupplierRepository;
import com.warehouse.service.movement.StockMovementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class PurchaseOrderServiceImpl implements PurchaseOrderService {

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderItemRepository purchaseOrderItemRepository;
    private final SupplierRepository supplierRepository;
    private final ItemRepository itemRepository;
    private final PurchaseOrderMapper purchaseOrderMapper;
    private final StockMovementService stockMovementService;

    @Override
    @Transactional
    public PurchaseOrderResponse createPurchaseOrder(CreatePurchaseOrderRequest request) {
        log.debug("Creating purchase order for supplierId={}", request.supplierId());

        Supplier supplier = getActiveSupplierOrThrow(request.supplierId());

        PurchaseOrder purchaseOrder = PurchaseOrder.builder()
                .supplier(supplier)
                .status(PurchaseOrderStatus.DRAFT)
                .build();

        PurchaseOrder savedPurchaseOrder = purchaseOrderRepository.save(purchaseOrder);

        List<PurchaseOrderItem> orderItems = request.items().stream()
                .map(itemRequest -> createPurchaseOrderItem(savedPurchaseOrder, itemRequest))
                .toList();

        List<PurchaseOrderItem> savedItems = purchaseOrderItemRepository.saveAll(orderItems);
        savedPurchaseOrder.setItems(savedItems);

        log.info("Purchase order created: id={}, supplierId={}, itemsCount={}",
                savedPurchaseOrder.getId(), supplier.getId(), savedItems.size());

        return purchaseOrderMapper.toResponse(savedPurchaseOrder);
    }

    @Override
    @Transactional
    public PurchaseOrderResponse placePurchaseOrder(Long purchaseOrderId) {
        log.debug("Placing purchase order with id={}", purchaseOrderId);

        PurchaseOrder purchaseOrder = getPurchaseOrderOrThrow(purchaseOrderId);

        validatePurchaseOrderCanBePlaced(purchaseOrder);

        purchaseOrder.setStatus(PurchaseOrderStatus.PLACED);

        log.info("Purchase order placed: id={}", purchaseOrderId);

        return purchaseOrderMapper.toResponse(purchaseOrder);
    }

    @Override
    @Transactional
    public PurchaseOrderResponse receivePurchaseOrder(
            Long purchaseOrderId,
            ReceivePurchaseOrderRequest request,
            UserContext context) {

        log.debug("Receiving purchase order with id={}", purchaseOrderId);

        PurchaseOrder purchaseOrder =
                getPurchaseOrderForReceiveOrThrow(purchaseOrderId);

        validatePurchaseOrderCanBeReceived(purchaseOrder);

        for (ReceivePurchaseOrderItemRequest itemRequest : request.items()) {
            receivePurchaseOrderItem(purchaseOrder, itemRequest, context);
        }

        updatePurchaseOrderStatus(purchaseOrder);

        log.info("Purchase order received: id={}, status={}",
                purchaseOrderId, purchaseOrder.getStatus());

        return purchaseOrderMapper.toResponse(purchaseOrder);
    }

    @Override
    @Transactional(readOnly = true)
    public PurchaseOrderResponse getPurchaseOrder(Long purchaseOrderId) {
        log.debug("Getting purchase order with id={}", purchaseOrderId);

        PurchaseOrder purchaseOrder = getPurchaseOrderOrThrow(purchaseOrderId);

        return purchaseOrderMapper.toResponse(purchaseOrder);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<PurchaseOrderResponse> getPurchaseOrders(int page, int size) {

        log.debug("Getting purchase orders: page={}, size={}", page, size);

        PageRequest pageable = PageRequest.of(page, size);

        Page<Long> idPage =
                purchaseOrderRepository.findPageIds(pageable);

        if (idPage.isEmpty()) {
            return PageResponse.from(
                    new PageImpl<>(
                            List.of(),
                            pageable,
                            idPage.getTotalElements()
                    )
            );
        }

        List<PurchaseOrder> purchaseOrders =
                purchaseOrderRepository.findAllByIdIn(
                        idPage.getContent()
                );

        Map<Long, PurchaseOrder> ordersById =
                purchaseOrders.stream()
                        .collect(Collectors.toMap(
                                PurchaseOrder::getId,
                                Function.identity()
                        ));

        List<PurchaseOrderResponse> content =
                idPage.getContent().stream()
                        .map(ordersById::get)
                        .map(purchaseOrderMapper::toResponse)
                        .toList();

        Page<PurchaseOrderResponse> responsePage =
                new PageImpl<>(
                        content,
                        pageable,
                        idPage.getTotalElements()
                );

        log.info("Found {} purchase orders",
                idPage.getTotalElements());

        return PageResponse.from(responsePage);
    }

    private PurchaseOrderItem createPurchaseOrderItem(
            PurchaseOrder purchaseOrder,
            CreatePurchaseOrderItemRequest request) {

        Item item = itemRepository.findById(request.itemId())
                .orElseThrow(() -> {
                    log.warn("Item with id={} not found", request.itemId());
                    return EntityNotFoundException.forId("Item", request.itemId());
                });

        if (!item.isActive()) {
            log.warn("Item with id={} is inactive", request.itemId());
            throw EntityNotFoundException.forId("Item", request.itemId());
        }

        return PurchaseOrderItem.builder()
                .purchaseOrder(purchaseOrder)
                .item(item)
                .orderedQty(request.orderedQty())
                .receivedQty(0)
                .unitPrice(item.getPrice())
                .unitCost(item.getCost())
                .build();
    }

    private void receivePurchaseOrderItem(
            PurchaseOrder purchaseOrder,
            ReceivePurchaseOrderItemRequest request,
            UserContext context) {

        PurchaseOrderItem orderItem = findOrderItem(
                purchaseOrder,
                request.purchaseOrderItemId()
        );

        int newReceivedQuantity =
                orderItem.getReceivedQty() + request.quantity();

        if (newReceivedQuantity > orderItem.getOrderedQty()) {
            throw PurchaseOrderOverReceiptException.forItem(
                    orderItem.getId(),
                    orderItem.getOrderedQty(),
                    orderItem.getReceivedQty(),
                    request.quantity()
            );
        }

        ChangeQuantityMovementRequest movementRequest =
                new ChangeQuantityMovementRequest(
                        orderItem.getItem().getId(),
                        request.quantity()
                );

        stockMovementService.registerReceipt(movementRequest, context);

        orderItem.setReceivedQty(newReceivedQuantity);
    }

    private PurchaseOrderItem findOrderItem(
            PurchaseOrder purchaseOrder,
            Long purchaseOrderItemId) {

        return purchaseOrder.getItems().stream()
                .filter(item -> item.getId().equals(purchaseOrderItemId))
                .findFirst()
                .orElseThrow(() -> EntityNotFoundException.forId(
                        "PurchaseOrderItem",
                        purchaseOrderItemId
                ));
    }

    private void updatePurchaseOrderStatus(PurchaseOrder purchaseOrder) {
        boolean fullyReceived = purchaseOrder.getItems().stream()
                .allMatch(item -> item.getReceivedQty() == item.getOrderedQty());

        if (fullyReceived) {
            purchaseOrder.setStatus(PurchaseOrderStatus.RECEIVED);
        } else {
            purchaseOrder.setStatus(PurchaseOrderStatus.PARTIALLY_RECEIVED);
        }
    }

    private void validatePurchaseOrderCanBePlaced(PurchaseOrder purchaseOrder) {
        if (purchaseOrder.getStatus() != PurchaseOrderStatus.DRAFT) {
            throw new InvalidPurchaseOrderStatusException(
                    "Purchase order with id '" + purchaseOrder.getId()
                            + "' cannot be placed from status '"
                            + purchaseOrder.getStatus() + "'"
            );
        }
    }

    private void validatePurchaseOrderCanBeReceived(PurchaseOrder purchaseOrder) {
        if (purchaseOrder.getStatus() != PurchaseOrderStatus.PLACED
                && purchaseOrder.getStatus() != PurchaseOrderStatus.PARTIALLY_RECEIVED) {
            throw new InvalidPurchaseOrderStatusException(
                    "Purchase order with id '" + purchaseOrder.getId()
                            + "' cannot be received from status '"
                            + purchaseOrder.getStatus() + "'"
            );
        }
    }

    private Supplier getActiveSupplierOrThrow(Long supplierId) {
        Supplier supplier = supplierRepository.findById(supplierId)
                .orElseThrow(() -> {
                    log.warn("Supplier with id={} not found", supplierId);
                    return EntityNotFoundException.forId("Supplier", supplierId);
                });

        if (!supplier.isActive()) {
            log.warn("Supplier with id={} is inactive", supplierId);
            throw EntityNotFoundException.forId("Supplier", supplierId);
        }

        return supplier;
    }

    private PurchaseOrder getPurchaseOrderOrThrow(Long purchaseOrderId) {
        return purchaseOrderRepository.findById(purchaseOrderId)
                .orElseThrow(() -> {
                    log.warn("Purchase order with id={} not found", purchaseOrderId);
                    return EntityNotFoundException.forId("PurchaseOrder", purchaseOrderId);
                });
    }

    private PurchaseOrder getPurchaseOrderForReceiveOrThrow(Long purchaseOrderId) {
        return purchaseOrderRepository.findByIdForReceive(purchaseOrderId)
                .orElseThrow(() -> {
                    log.warn("Purchase order with id={} not found",
                            purchaseOrderId);
                    return EntityNotFoundException.forId(
                            "PurchaseOrder", purchaseOrderId);
                });
    }
}
