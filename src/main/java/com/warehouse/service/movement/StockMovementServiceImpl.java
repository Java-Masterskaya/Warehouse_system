package com.warehouse.service.movement;

import com.warehouse.dto.UserContext;
import com.warehouse.dto.event.LowStockAlertEvent;
import com.warehouse.dto.request.movement.ChangeQuantityMovementRequest;
import com.warehouse.dto.request.movement.StocktakeRequest;
import com.warehouse.dto.response.PageResponse;
import com.warehouse.dto.response.movement.StockMovementHistoryResponse;
import com.warehouse.dto.response.movement.StockMovementResponse;
import com.warehouse.entity.Batch;
import com.warehouse.entity.Item;
import com.warehouse.entity.MovementType;
import com.warehouse.entity.Stock;
import com.warehouse.entity.StockMovement;
import com.warehouse.entity.User;
import com.warehouse.exception.EntityNotFoundException;
import com.warehouse.exception.InsufficientStockException;
import com.warehouse.exception.InvalidMovementRequestException;
import com.warehouse.metric.MetricService;
import com.warehouse.exception.StocktakeConflictException;
import com.warehouse.mapper.StockMovementMapper;
import com.warehouse.repository.ItemRepository;
import com.warehouse.repository.StockMovementRepository;
import com.warehouse.repository.StockRepository;
import com.warehouse.repository.UserRepository;
import com.warehouse.kafka.outbox.OutboxService;
import com.warehouse.repository.BatchRepository;
import com.warehouse.service.batch.BatchService;
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
    StockRepository stockRepository;
    BatchService batchService;
    BatchRepository batchRepository;
    OutboxService outboxService;
    MetricService metricService;

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
    public StockMovementResponse registerReceipt(ChangeQuantityMovementRequest request, UserContext ctx) {
        int quantity = request.quantity();
        Long itemId = request.itemId();

        if (quantity <= 0) {
            log.warn("Invalid quantity for stock receipt: itemId={}, quantity={}", itemId, quantity);
            throw new InvalidMovementRequestException("Quantity must be greater than 0");
        }

        Item item = itemCheckForExist(itemId);
        itemCheckForActive(item);

        log.debug("Processing stock receipt for itemId={}, quantity={}, expiryDate={}, userId={}",
                itemId, quantity, request.expiryDate(), ctx.userId());

        int stockAfter = stockService.receiveStock(itemId, quantity);

        // Создаем партию с указанным сроком годности
        Batch batch = batchService.createBatch(item, quantity, request.expiryDate());

        StockMovement stockMovement = newStockMovement(item, quantity, ctx, MovementType.RECEIVE, batch);

        log.info("Stock receipt registered: itemId={}, quantity={}, expiryDate={}, batchId={}, newTotal={}, userId={}, movementId={}", itemId,
                quantity, request.expiryDate(), batch.getId(), stockAfter, ctx.userId(), stockMovement.getId());

        metricService.increment("warehouse.movements.receive.total");

        return mapper.toResponse(stockMovement, stockAfter, false);
    }

    @Override
    @Transactional
    @CacheEvict(value = "item", key = "#request.itemId")
    public StockMovementResponse writeOffReceipt(ChangeQuantityMovementRequest request, UserContext ctx) {
        int quantity = request.quantity();
        Long itemId = request.itemId();

        Item item = itemCheckForExist(itemId);

        itemCheckForActive(item);

        log.debug("Processing stock write-off for itemId={}, quantity={}, userId={}",
                itemId, quantity, ctx.userId());

        LocalDateTime now = LocalDateTime.now();

        try {
            // FEFO списание: гасим из партий с ближайшим сроком
            int stockAfter = batchService.writeOffByFEFO(itemId, quantity, now);

            StockMovement stockMovement = newStockMovement(item, quantity, ctx, MovementType.WRITE_OFF);

            boolean lowStock = stockAfter < item.getMinStock();
            if (lowStock) {
                LowStockAlertEvent event = new LowStockAlertEvent(
                        item.getId(),
                        item.getSku(),
                        item.getName(),
                        stockAfter,
                        item.getMinStock(),
                        ctx.username(),
                        LocalDateTime.now()
                );
                // Сохраняем событие в outbox атомарно с движением
                outboxService.saveLowStockAlertEvent(event);
                log.info("LowStockAlert saved to outbox: itemId={}, stockAfter={}, minStock={}",
                        item.getId(), stockAfter, item.getMinStock());
            }

            log.info("Write-off completed: itemId={}, quantity={}, newTotal={}, userId={}, movementId={}",
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
    public PageResponse<StockMovementHistoryResponse> getItemMovementHistory(Long itemId, MovementType type, int page,
            int size) {
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
    public StockMovementResponse stocktake(StocktakeRequest request, UserContext ctx) {
        Long itemId = request.itemId();
        int counted = request.countedQuantity();

        Item item = itemCheckForExist(itemId);
        itemCheckForActive(item);

        //with lock
        Stock stock = stockRepository.findByItemIdForUpdate(itemId)
                .orElseThrow(() -> EntityNotFoundException.forId("Stock not found for item", itemId));

        int reserved = availabilityService.getReserved(itemId);
        if (counted < reserved) {
            log.warn("Stocktake conflict: itemId={}, countedQuantity={}, reservedQuantity={}. "
                    + "Physical quantity is lower than active reservations", itemId, counted, reserved);
            throw StocktakeConflictException.of(counted, reserved);
        }

        int current = stock.getQuantity();
        int delta = counted - current;

        if (delta == 0) {
            log.info("Stocktake: no change for itemId={}", itemId);
            return mapper.toNoMovementResponse(itemId, counted);
        }

        // Получаем все партии товара
        List<Batch> batches = batchService.findByItemIdOrderByExpiryDate(itemId);
        
        if (batches.isEmpty()) {
            log.warn("Stocktake: no batches found for itemId={}. Cannot perform stocktake without batches", itemId);
            throw new IllegalStateException("Cannot perform stocktake: no batches found for item");
        }
        
        // Распределяем разницу по партиям
        int remainingDelta = delta;
        
        if (remainingDelta > 0) {
            // Нам нужно увеличить остаток (нашли лишние товары)
            // Добавляем к первой партии (самый ранний срок годности)
            Batch firstBatch = batches.get(0);
            firstBatch.setQuantity(firstBatch.getQuantity() + remainingDelta);
            batchRepository.save(firstBatch);
            remainingDelta = 0;
        } else if (remainingDelta < 0) {
            // Нам нужно уменьшить остаток (товар пропал)
            // Списываем из последней партии (самый отдаленный срок годности - FEFO reversed)
            // Но логичнее списать из самых близких - First Expire First Out
            // Пройдем с начала списка (ближайшие сроки)
            for (Batch batch : batches) {
                if (remainingDelta == 0) break;
                
                int batchQty = batch.getQuantity();
                int writeOff = Math.min(-remainingDelta, batchQty);
                batch.setQuantity(batchQty - writeOff);
                remainingDelta += writeOff;
                batchRepository.save(batch);
            }
        }
        
        if (remainingDelta != 0) {
            log.error("Stocktake: unable to distribute delta={}. remaining={}", delta, remainingDelta);
            throw new IllegalStateException("Unable to distribute adjustment across batches");
        }

        // Обновляем общий остаток
        stock.setQuantity(counted);
        stockRepository.save(stock);

        User userRef = userRepository.getReferenceById(ctx.userId());

        // Создаем движение с партией, к которой было применено изменение
        Batch affectedBatch;
        if (delta > 0) {
            // Добавили товар - связываем с первой партией (ближайший срок)
            affectedBatch = batches.get(0);
        } else {
            // Списали товар - связываем с последней партией (FEFO - отдаленный срок)
            affectedBatch = batches.get(batches.size() - 1);
        }
        
        StockMovement stockMovement = StockMovement.builder().item(item).user(userRef).type(MovementType.ADJUSTMENT)
                .quantity(delta).batch(affectedBatch).build();
        stockMovementRepository.save(stockMovement);

        boolean lowStock = counted < item.getMinStock();
        if (lowStock) {
            LowStockAlertEvent event = new LowStockAlertEvent(
                    item.getId(),
                    item.getSku(),
                    item.getName(),
                    counted,
                    item.getMinStock(),
                    ctx.username(),
                    LocalDateTime.now()
            );
            // Сохраняем событие в outbox атомарно с движением
            outboxService.saveLowStockAlertEvent(event);
            log.info("LowStockAlert saved to outbox from stocktake: itemId={}, counted={}, minStock={}",
                    item.getId(), counted, item.getMinStock());
        }

        log.info("Stocktake: itemId={}, current={}, counted={}, delta={}, userId={}",
                itemId, current, counted, delta, ctx.userId());

        metricService.increment("warehouse.movements.adjustment.total");

        return mapper.toResponse(stockMovement, counted, lowStock);
    }

    @Override
    public StockMovement newStockMovement(Item item, int quantity, UserContext ctx, MovementType type) {
        return newStockMovement(item, quantity, ctx, type, null);
    }

    /**
     * Сохраняет новое движение товара с опциональной партией.
     *
     * @param item     перемещаемый товар
     * @param quantity количество перемещаемых товаров
     * @param ctx      пользователь, выполняющий операцию
     * @param type     тип выполняемой операции
     * @param batch    партия (опционально)
     * @return сохраненное движение товара
     */
    public StockMovement newStockMovement(Item item, int quantity, UserContext ctx, MovementType type, Batch batch) {
        User userRef = userRepository.getReferenceById(ctx.userId());

        StockMovement stockMovement = StockMovement.builder().item(item).user(userRef).type(type).quantity(quantity)
                .batch(batch).build();
        return stockMovementRepository.save(stockMovement);
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
