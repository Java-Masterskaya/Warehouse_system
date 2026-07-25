package com.warehouse.service.movement;

import com.warehouse.audit.AuditContext;
import com.warehouse.audit.Auditable;
import com.warehouse.audit.entity.AuditAction;
import com.warehouse.audit.entity.EntityType;
import com.warehouse.dto.UserContext;
import com.warehouse.dto.event.LowStockAlertEvent;
import com.warehouse.dto.request.movement.ChangeQuantityMovementRequest;
import com.warehouse.dto.request.movement.StocktakeRequest;
import com.warehouse.dto.request.movement.TransferStockRequest;
import com.warehouse.dto.response.PageResponse;
import com.warehouse.dto.response.movement.StockMovementHistoryResponse;
import com.warehouse.dto.response.movement.StockMovementResponse;
import com.warehouse.dto.response.stock.StockAuditDto;
import com.warehouse.dto.response.movement.StockTransferResponse;
import com.warehouse.entity.Item;
import com.warehouse.entity.MovementType;
import com.warehouse.entity.Stock;
import com.warehouse.entity.StockMovement;
import com.warehouse.entity.User;
import com.warehouse.entity.Warehouse;
import com.warehouse.exception.EntityNotFoundException;
import com.warehouse.exception.InsufficientStockException;
import com.warehouse.exception.InvalidMovementRequestException;
import com.warehouse.exception.StocktakeConflictException;
import com.warehouse.kafka.outbox.OutboxService;
import com.warehouse.mapper.StockMovementMapper;
import com.warehouse.metric.MetricService;
import com.warehouse.repository.ItemRepository;
import com.warehouse.repository.StockMovementRepository;
import com.warehouse.repository.StockRepository;
import com.warehouse.repository.UserRepository;
import com.warehouse.repository.WarehouseRepository;
import com.warehouse.service.reservation.StockAvailabilityService;
import com.warehouse.service.stock.StockService;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Реализация сервиса для управления движениями товаров на складе.
 * Обрабатывает операции прихода товара и сохраняет записи о движениях.
 *
 * <p>LowStockAlert события сохраняются в outbox (атомарно с движением), а затем
 * релей отправляет их в Kafka. Это гарантирует, что событие не потеряется даже при краше.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class StockMovementServiceImpl implements StockMovementService {

    StockMovementMapper mapper;
    StockService stockService;
    StockAvailabilityService availabilityService;
    ItemRepository itemRepository;
    StockMovementRepository stockMovementRepository;
    UserRepository userRepository;
    WarehouseRepository warehouseRepository;
    StockRepository stockRepository;
    OutboxService outboxService;
    MetricService metricService;
    AuditContext auditContext;

    /**
     * Регистрирует приход товара на склад.
     * Выполняет валидацию товара, обновляет остаток и сохраняет запись о движении.
     *
     * @param request данные запроса на приход товара
     * @param ctx     record UserContext, содержит ID и username пользователя, выполняющего операцию
     * @return ответ с информацией о движении товара
     * @throws EntityNotFoundException если товар не найден или неактивен
     */
    @Override
    @Transactional
    @CacheEvict(value = "item", key = "#request.itemId")
    @Auditable(action = AuditAction.RECEIVE, entityType = EntityType.STOCK)
    public StockMovementResponse registerReceipt(ChangeQuantityMovementRequest request, UserContext ctx) {
        int quantity = request.quantity();
        Long itemId = request.itemId();

        if (quantity <= 0) {
            log.warn("Invalid quantity for stock receipt: itemId={}, quantity={}", itemId, quantity);
            throw new InvalidMovementRequestException("Quantity must be greater than 0");
        }

        Item item = itemCheckForExist(itemId);
        itemCheckForActive(item);

        log.debug("Processing stock receipt for itemId={}, quantity={}, userId={}", itemId, quantity, ctx.userId());

        int stockAfter = stockService.receiveStock(itemId, quantity);

        StockMovement stockMovement = newStockMovement(item, quantity, ctx, MovementType.RECEIVE);

        auditContext.setEntityId(itemId);
        auditContext.setOldValue(new StockAuditDto(itemId, stockAfter - quantity));
        auditContext.setNewValue(new StockAuditDto(itemId, stockAfter));

        log.info(
                "Stock receipt registered: itemId={}, quantity={}, defaultStockAfter={}, userId={}, movementId={}",
                itemId, quantity, stockAfter, ctx.userId(), stockMovement.getId()
        );

        metricService.increment("warehouse.movements.receive.total");

        return mapper.toResponse(stockMovement, stockAfter, false);
    }

    @Override
    @Transactional
    @CacheEvict(value = "item", key = "#request.itemId")
    @Auditable(action = AuditAction.WRITE_OFF, entityType = EntityType.STOCK)
    public StockMovementResponse writeOffReceipt(ChangeQuantityMovementRequest request, UserContext ctx) {
        int quantity = request.quantity();
        Long itemId = request.itemId();

        Item item = itemCheckForExist(itemId);

        itemCheckForActive(item);

        log.debug("Processing stock write-off for itemId={}, quantity={}, userId={}", itemId, quantity, ctx.userId());

        try {
            int stockAfter = stockService.writeOffStock(itemId, quantity);

            StockMovement stockMovement = newStockMovement(item, quantity, ctx, MovementType.WRITE_OFF);

            long totalAfter = availabilityService.getTotalQuantity(itemId);
            boolean lowStock = totalAfter < item.getMinStock();
            if (lowStock) {
                LowStockAlertEvent event = new LowStockAlertEvent(
                        item.getId(),
                        item.getSku(),
                        item.getName(),
                        Math.toIntExact(totalAfter),
                        item.getMinStock(),
                        ctx.username(),
                        LocalDateTime.now()
                );
                // Сохраняем событие в outbox атомарно с движением
                outboxService.saveLowStockAlertEvent(event);
                log.info("LowStockAlert saved to outbox: itemId={}, stockAfter={}, minStock={}", item.getId(),
                        stockAfter, item.getMinStock());
            }

            auditContext.setEntityId(itemId);
            auditContext.setOldValue(new StockAuditDto(itemId, stockAfter + quantity));
            auditContext.setNewValue(new StockAuditDto(itemId, stockAfter));

            log.info("Write-off completed: itemId={}, quantity={}, defaultStockAfter={}, userId={}, movementId={}",
                    itemId, quantity, stockAfter, ctx.userId(), stockMovement.getId());

            metricService.increment("warehouse.movements.writeoff.total");

            return mapper.toResponse(stockMovement, stockAfter, lowStock);
        } catch (InsufficientStockException e) {
            metricService.increment("warehouse.movements.writeoff.rejected.total");
            throw e;
        }
    }

    @Transactional(readOnly = true)
    @Override
    public PageResponse<StockMovementHistoryResponse> getItemMovementHistory(
            Long itemId, MovementType type, int page,
            int size
    ) {
        if (!itemRepository.existsById(itemId)) {
            log.warn("Item с id={} не найден", itemId);
            throw EntityNotFoundException.forId("Item", itemId);
        }

        Pageable pageable = PageRequest.of(page, size);

        Page<StockMovementHistoryResponse> history = stockMovementRepository.findHistoryByItemId(itemId, type,
                pageable);

        return PageResponse.from(history);
    }

    @Override
    @Transactional
    @CacheEvict(value = "item", key = "#request.itemId")
    @Auditable(action = AuditAction.ADJUSTMENT, entityType = EntityType.STOCK)
    public StockMovementResponse stocktake(StocktakeRequest request, UserContext ctx) {
        Long itemId = request.itemId();
        int counted = request.countedQuantity();

        Item item = itemCheckForExist(itemId);
        itemCheckForActive(item);

        //with lock
        Stock stock = stockRepository.findByItemIdForUpdate(itemId)
                .orElseThrow(() -> EntityNotFoundException.forId("Stock not found for item", itemId));
        long totalBefore = stockRepository.findTotalQuantityByItemId(itemId);

        auditContext.setEntityId(itemId);
        int reserved = availabilityService.getReserved(itemId);
        if (counted < reserved) {
            log.warn("Stocktake conflict: itemId={}, countedQuantity={}, reservedQuantity={}. "
                    + "Physical quantity is lower than active reservations", itemId, counted, reserved);
            throw StocktakeConflictException.of(counted, reserved);
        }

        int current = stock.getQuantity();
        auditContext.setOldValue(new StockAuditDto(itemId, current));
        int delta = counted - current;

        if (delta == 0) {
            auditContext.clear();
            log.info("Stocktake: no change for itemId={}", itemId);
            return mapper.toNoMovementResponse(itemId, counted);
        }

        stock.setQuantity(counted);
        stockRepository.save(stock);
        long totalAfter = Math.addExact(totalBefore - current, counted);
        auditContext.setNewValue(new StockAuditDto(itemId, counted));

        User userRef = userRepository.getReferenceById(ctx.userId());

        StockMovement stockMovement = StockMovement.builder().item(item).user(userRef).type(MovementType.ADJUSTMENT)
                .warehouse(stock.getWarehouse()).quantity(delta).build();
        stockMovementRepository.save(stockMovement);

        boolean lowStock = totalAfter < item.getMinStock();
        if (lowStock) {
            LowStockAlertEvent event = new LowStockAlertEvent(
                    item.getId(),
                    item.getSku(),
                    item.getName(),
                    Math.toIntExact(totalAfter),
                    item.getMinStock(),
                    ctx.username(),
                    LocalDateTime.now()
            );
            // Сохраняем событие в outbox атомарно с движением
            outboxService.saveLowStockAlertEvent(event);
            log.info("LowStockAlert saved to outbox from stocktake: itemId={}, totalAfter={}, minStock={}",
                    item.getId(), totalAfter, item.getMinStock());
        }

        log.info("Stocktake: itemId={}, current={}, counted={}, delta={}, userId={}", itemId, current, counted, delta,
                ctx.userId());

        metricService.increment("warehouse.movements.adjustment.total");

        return mapper.toResponse(stockMovement, counted, lowStock);
    }

    @Override
    @Transactional
    @CacheEvict(value = "item", key = "#request.itemId")
    public StockTransferResponse transfer(TransferStockRequest request, UserContext ctx) {
        Long itemId = request.itemId();
        Long fromWarehouseId = request.fromWarehouseId();
        Long toWarehouseId = request.toWarehouseId();
        int quantity = request.quantity();

        if (fromWarehouseId.equals(toWarehouseId)) {
            throw new InvalidMovementRequestException("Source and destination warehouses must be different");
        }

        Item item = itemCheckForExist(itemId);
        itemCheckForActive(item);
        Warehouse fromWarehouse = warehouseCheckForExist(fromWarehouseId);
        Warehouse toWarehouse = warehouseCheckForExist(toWarehouseId);

        List<Long> warehouseIds = List.of(fromWarehouseId, toWarehouseId).stream().sorted().toList();
        for (Long warehouseId : warehouseIds) {
            stockRepository.createEmptyStockIfAbsent(itemId, warehouseId);
        }

        List<Stock> lockedStocks = stockRepository.findByItemAndWarehousesForUpdate(itemId, warehouseIds);
        Stock fromStock = findLockedStock(lockedStocks, fromWarehouseId, itemId);
        Stock toStock = findLockedStock(lockedStocks, toWarehouseId, itemId);

        int available = availabilityService.getAvailable(fromStock);
        if (available < quantity) {
            metricService.increment("warehouse.movements.transfer.rejected.total");
            throw InsufficientStockException.atWarehouse(itemId, fromWarehouseId, quantity, available);
        }

        fromStock.setQuantity(fromStock.getQuantity() - quantity);
        toStock.setQuantity(toStock.getQuantity() + quantity);

        UUID transferId = UUID.randomUUID();
        LocalDateTime transferredAt = LocalDateTime.now();
        User userRef = userRepository.getReferenceById(ctx.userId());

        StockMovement outMovement = StockMovement.builder()
                .item(item)
                .warehouse(fromWarehouse)
                .user(userRef)
                .type(MovementType.TRANSFER_OUT)
                .quantity(quantity)
                .createdAt(transferredAt)
                .transferId(transferId)
                .build();
        StockMovement inMovement = StockMovement.builder()
                .item(item)
                .warehouse(toWarehouse)
                .user(userRef)
                .type(MovementType.TRANSFER_IN)
                .quantity(quantity)
                .createdAt(transferredAt)
                .transferId(transferId)
                .build();

        stockMovementRepository.saveAllAndFlush(List.of(outMovement, inMovement));
        metricService.increment("warehouse.movements.transfer.total");

        log.info(
                "Stock transfer completed: transferId={}, itemId={}, fromWarehouseId={}, "
                        + "toWarehouseId={}, quantity={}, fromStockAfter={}, toStockAfter={}, userId={}",
                transferId,
                itemId,
                fromWarehouseId,
                toWarehouseId,
                quantity,
                fromStock.getQuantity(),
                toStock.getQuantity(),
                ctx.userId()
        );

        return new StockTransferResponse(
                transferId,
                itemId,
                fromWarehouseId,
                toWarehouseId,
                quantity,
                fromStock.getQuantity(),
                toStock.getQuantity(),
                outMovement.getId(),
                inMovement.getId(),
                transferredAt
        );
    }

    @Override
    public StockMovement newStockMovement(Item item, int quantity, UserContext ctx, MovementType type) {
        Warehouse warehouse = warehouseRepository.findByDefaultWarehouseTrue()
                .orElseThrow(() -> new EntityNotFoundException("Default warehouse not found"));
        return newStockMovement(item, warehouse, quantity, ctx, type);
    }

    @Override
    public StockMovement newStockMovement(
            Item item,
            Warehouse warehouse,
            int quantity,
            UserContext ctx,
            MovementType type
    ) {
        User userRef = userRepository.getReferenceById(ctx.userId());

        StockMovement stockMovement = StockMovement.builder()
                .item(item)
                .warehouse(warehouse)
                .user(userRef)
                .type(type)
                .quantity(quantity)
                .build();
        return stockMovementRepository.save(stockMovement);
    }

    private Stock findLockedStock(List<Stock> stocks, Long warehouseId, Long itemId) {
        return stocks.stream()
                .filter(stock -> stock.getWarehouse().getId().equals(warehouseId))
                .findFirst()
                .orElseThrow(() -> new EntityNotFoundException(
                        "Stock not found for item " + itemId + " at warehouse " + warehouseId));
    }

    private Warehouse warehouseCheckForExist(Long warehouseId) {
        return warehouseRepository.findById(warehouseId)
                .orElseThrow(() -> EntityNotFoundException.forId("Warehouse", warehouseId));
    }

    private void itemCheckForActive(Item item) {
        if (!item.isActive()) {
            log.warn("Attempt to receive inactive item: itemId={}", item.getId());
            throw EntityNotFoundException.forId("Item", item.getId());
        }
    }

    private Item itemCheckForExist(Long itemId) {
        return itemRepository.findById(itemId).orElseThrow(() -> {
            log.warn("Item not found: itemId={}", itemId);
            return EntityNotFoundException.forId("Item", itemId);
        });
    }
}
