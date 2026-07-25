package com.warehouse.service;

import com.warehouse.dto.UserContext;
import com.warehouse.dto.request.purchaseorder.CreatePurchaseOrderItemRequest;
import com.warehouse.dto.request.purchaseorder.CreatePurchaseOrderRequest;
import com.warehouse.dto.request.purchaseorder.ReceivePurchaseOrderItemRequest;
import com.warehouse.dto.request.purchaseorder.ReceivePurchaseOrderRequest;
import com.warehouse.entity.Item;
import com.warehouse.entity.PurchaseOrder;
import com.warehouse.entity.PurchaseOrderItem;
import com.warehouse.entity.PurchaseOrderStatus;
import com.warehouse.entity.Supplier;
import com.warehouse.exception.InvalidPurchaseOrderStatusException;
import com.warehouse.exception.PurchaseOrderOverReceiptException;
import com.warehouse.mapper.PurchaseOrderMapper;
import com.warehouse.repository.ItemRepository;
import com.warehouse.repository.PurchaseOrderItemRepository;
import com.warehouse.repository.PurchaseOrderRepository;
import com.warehouse.repository.SupplierRepository;
import com.warehouse.service.movement.StockMovementService;
import com.warehouse.service.purchaseorder.PurchaseOrderService;
import com.warehouse.service.purchaseorder.PurchaseOrderServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PurchaseOrderServiceImplTest {

    @Mock
    private PurchaseOrderRepository purchaseOrderRepository;

    @Mock
    private PurchaseOrderItemRepository purchaseOrderItemRepository;

    @Mock
    private SupplierRepository supplierRepository;

    @Mock
    private ItemRepository itemRepository;

    @Mock
    private StockMovementService stockMovementService;

    private PurchaseOrderService purchaseOrderService;

    @BeforeEach
    void setUp() {
        PurchaseOrderMapper purchaseOrderMapper =
                Mappers.getMapper(PurchaseOrderMapper.class);

        purchaseOrderService = new PurchaseOrderServiceImpl(
                purchaseOrderRepository,
                purchaseOrderItemRepository,
                supplierRepository,
                itemRepository,
                purchaseOrderMapper,
                stockMovementService
        );
    }

    @Test
    void shouldCreatePurchaseOrderInDraftStatus() {
        Supplier supplier = Supplier.builder()
                .id(1L)
                .name("Samsung")
                .active(true)
                .build();

        Item item = Item.builder()
                .id(10L)
                .name("Monitor")
                .active(true)
                .price(new BigDecimal("1500.00"))
                .cost(new BigDecimal("1100.00"))
                .build();

        CreatePurchaseOrderRequest request = new CreatePurchaseOrderRequest(
                1L,
                List.of(new CreatePurchaseOrderItemRequest(10L, 5))
        );

        when(supplierRepository.findById(1L))
                .thenReturn(Optional.of(supplier));

        when(itemRepository.findById(10L))
                .thenReturn(Optional.of(item));

        when(purchaseOrderRepository.save(any(PurchaseOrder.class)))
                .thenAnswer(invocation -> {
                    PurchaseOrder order = invocation.getArgument(0);
                    order.setId(100L);
                    return order;
                });

        when(purchaseOrderItemRepository.saveAll(any()))
                .thenAnswer(invocation -> {
                    List<PurchaseOrderItem> items = invocation.getArgument(0);
                    items.get(0).setId(1000L);
                    return items;
                });

        var result = purchaseOrderService.createPurchaseOrder(request);

        assertThat(result.id()).isEqualTo(100L);
        assertThat(result.status()).isEqualTo(PurchaseOrderStatus.DRAFT);
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).orderedQty()).isEqualTo(5);
        assertThat(result.items().get(0).receivedQty()).isZero();
        assertThat(result.items().get(0).unitPrice())
                .isEqualByComparingTo("1500.00");
        assertThat(result.items().get(0).unitCost())
                .isEqualByComparingTo("1100.00");
    }

    @Test
    void shouldPlaceDraftPurchaseOrder() {
        PurchaseOrder purchaseOrder = PurchaseOrder.builder()
                .id(100L)
                .status(PurchaseOrderStatus.DRAFT)
                .items(List.of())
                .build();

        when(purchaseOrderRepository.findById(100L))
                .thenReturn(Optional.of(purchaseOrder));

        var result = purchaseOrderService.placePurchaseOrder(100L);

        assertThat(result.status()).isEqualTo(PurchaseOrderStatus.PLACED);
        assertThat(purchaseOrder.getStatus())
                .isEqualTo(PurchaseOrderStatus.PLACED);
    }

    @Test
    void shouldThrowWhenPlacingPurchaseOrderFromInvalidStatus() {
        PurchaseOrder purchaseOrder = PurchaseOrder.builder()
                .id(100L)
                .status(PurchaseOrderStatus.PLACED)
                .items(List.of())
                .build();

        when(purchaseOrderRepository.findById(100L))
                .thenReturn(Optional.of(purchaseOrder));

        assertThrows(
                InvalidPurchaseOrderStatusException.class,
                () -> purchaseOrderService.placePurchaseOrder(100L)
        );
    }

    @Test
    void shouldThrowWhenReceivingDraftPurchaseOrder() {
        PurchaseOrder purchaseOrder = PurchaseOrder.builder()
                .id(100L)
                .status(PurchaseOrderStatus.DRAFT)
                .items(List.of())
                .build();

        when(purchaseOrderRepository.findByIdForReceive(100L))
                .thenReturn(Optional.of(purchaseOrder));

        ReceivePurchaseOrderRequest request =
                new ReceivePurchaseOrderRequest(
                        List.of(
                                new ReceivePurchaseOrderItemRequest(
                                        1000L,
                                        4,
                                        LocalDateTime.now().plusDays(30)
                                )
                        )
                );

        UserContext context = new UserContext(1L, "admin");

        assertThrows(
                InvalidPurchaseOrderStatusException.class,
                () -> purchaseOrderService.receivePurchaseOrder(
                        100L,
                        request,
                        context
                )
        );

        verify(stockMovementService, never())
                .registerReceipt(any(), any());
    }

    @Test
    void shouldPartiallyReceivePurchaseOrder() {
        Item item = Item.builder()
                .id(10L)
                .name("Monitor")
                .active(true)
                .build();

        PurchaseOrder purchaseOrder = PurchaseOrder.builder()
                .id(100L)
                .status(PurchaseOrderStatus.PLACED)
                .build();

        PurchaseOrderItem orderItem = PurchaseOrderItem.builder()
                .id(1000L)
                .purchaseOrder(purchaseOrder)
                .item(item)
                .orderedQty(10)
                .receivedQty(0)
                .build();

        purchaseOrder.setItems(List.of(orderItem));

        when(purchaseOrderRepository.findByIdForReceive(100L))
                .thenReturn(Optional.of(purchaseOrder));

        ReceivePurchaseOrderRequest request =
                new ReceivePurchaseOrderRequest(
                        List.of(
                                new ReceivePurchaseOrderItemRequest(
                                        1000L,
                                        4,
                                        LocalDateTime.now().plusDays(30)
                                )
                        )
                );

        UserContext context = new UserContext(1L, "admin");

        var result = purchaseOrderService.receivePurchaseOrder(
                100L,
                request,
                context
        );

        assertThat(result.status())
                .isEqualTo(PurchaseOrderStatus.PARTIALLY_RECEIVED);

        assertThat(orderItem.getReceivedQty()).isEqualTo(4);

        verify(stockMovementService).registerReceipt(
                argThat(r -> r.itemId().equals(10L) && r.quantity() == 4),
                eq(context)
        );
    }

    @Test
    void shouldFullyReceivePurchaseOrder() {
        Item item = Item.builder()
                .id(10L)
                .name("Monitor")
                .active(true)
                .build();

        PurchaseOrder purchaseOrder = PurchaseOrder.builder()
                .id(100L)
                .status(PurchaseOrderStatus.PLACED)
                .build();

        PurchaseOrderItem orderItem = PurchaseOrderItem.builder()
                .id(1000L)
                .purchaseOrder(purchaseOrder)
                .item(item)
                .orderedQty(10)
                .receivedQty(0)
                .build();

        purchaseOrder.setItems(List.of(orderItem));

        when(purchaseOrderRepository.findByIdForReceive(100L))
                .thenReturn(Optional.of(purchaseOrder));

        ReceivePurchaseOrderRequest request =
                new ReceivePurchaseOrderRequest(
                        List.of(
                                new ReceivePurchaseOrderItemRequest(
                                        1000L,
                                        10,
                                        LocalDateTime.now().plusDays(30)
                                )
                        )
                );

        UserContext context = new UserContext(1L, "admin");

        var result = purchaseOrderService.receivePurchaseOrder(
                100L,
                request,
                context
        );

        assertThat(result.status())
                .isEqualTo(PurchaseOrderStatus.RECEIVED);

        assertThat(orderItem.getReceivedQty()).isEqualTo(10);

        verify(stockMovementService).registerReceipt(
                argThat(r -> r.itemId().equals(10L) && r.quantity() == 10),
                eq(context)
        );
    }

    @Test
    void shouldThrowWhenReceivingMoreThanOrdered() {
        Item item = Item.builder()
                .id(10L)
                .name("Monitor")
                .active(true)
                .build();

        PurchaseOrder purchaseOrder = PurchaseOrder.builder()
                .id(100L)
                .status(PurchaseOrderStatus.PLACED)
                .build();

        PurchaseOrderItem orderItem = PurchaseOrderItem.builder()
                .id(1000L)
                .purchaseOrder(purchaseOrder)
                .item(item)
                .orderedQty(10)
                .receivedQty(7)
                .build();

        purchaseOrder.setItems(List.of(orderItem));

        when(purchaseOrderRepository.findByIdForReceive(100L))
                .thenReturn(Optional.of(purchaseOrder));

        ReceivePurchaseOrderRequest request =
                new ReceivePurchaseOrderRequest(
                        List.of(
                                new ReceivePurchaseOrderItemRequest(
                                        1000L,
                                        4,
                                        LocalDateTime.now().plusDays(30)
                                )
                        )
                );

        UserContext context = new UserContext(1L, "admin");

        assertThrows(
                PurchaseOrderOverReceiptException.class,
                () -> purchaseOrderService.receivePurchaseOrder(
                        100L,
                        request,
                        context
                )
        );

        assertThat(orderItem.getReceivedQty()).isEqualTo(7);

        verify(stockMovementService, never())
                .registerReceipt(any(), any());
    }
}
