package com.warehouse.service.property;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.warehouse.audit.AuditContext;
import com.warehouse.dto.UserContext;
import com.warehouse.dto.request.movement.ReceiveStockRequest;
import com.warehouse.dto.request.movement.TransferStockRequest;
import com.warehouse.dto.request.movement.WriteOffStockRequest;
import com.warehouse.entity.Batch;
import com.warehouse.entity.Item;
import com.warehouse.entity.MovementType;
import com.warehouse.entity.ReservationStatus;
import com.warehouse.entity.Role;
import com.warehouse.entity.Stock;
import com.warehouse.entity.StockMovement;
import com.warehouse.entity.User;
import com.warehouse.entity.Warehouse;
import com.warehouse.exception.EntityNotFoundException;
import com.warehouse.exception.InsufficientStockException;
import com.warehouse.exception.InvalidMovementRequestException;
import com.warehouse.exception.StockMovementInvariantException;
import com.warehouse.kafka.outbox.OutboxService;
import com.warehouse.mapper.StockMovementMapper;
import com.warehouse.metric.MetricService;
import com.warehouse.pagination.KeysetCursorCodec;
import com.warehouse.repository.BatchRepository;
import com.warehouse.repository.ItemRepository;
import com.warehouse.repository.StockMovementKeysetRepository;
import com.warehouse.repository.StockMovementRepository;
import com.warehouse.repository.StockRepository;
import com.warehouse.repository.StockReserveRepository;
import com.warehouse.repository.UserRepository;
import com.warehouse.repository.WarehouseRepository;
import com.warehouse.service.batch.BatchService;
import com.warehouse.service.batch.BatchServiceImpl;
import com.warehouse.service.batch.ExpiredBatchCleanupService;
import com.warehouse.service.movement.StockMovementService;
import com.warehouse.service.movement.StockMovementServiceImpl;
import com.warehouse.service.reservation.StockAvailabilityService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.cache.CacheManager;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Тестовый каркас для property-тестов инвариантов остатков ({@link StockInvariantsPropertyTest}).
 *
 * <p>Поднимает реальные production-сервисы {@link StockMovementServiceImpl},
 * {@link BatchServiceImpl} и {@link StockAvailabilityService} поверх лёгких in-memory
 * реализаций репозиториев (Mockito {@code Answer}, поддержанный общей картой состояния в
 * этом классе). Так каждая из сотен случайных последовательностей операций прогоняется через
 * настоящую бизнес-логику (FEFO-списание, блокировка остатка по складу, проверка
 * достаточности остатка) за миллисекунды — без поднятия Spring-контекста.</p>
 *
 * <p><b>Почему не Testcontainers.</b> jqwik не имеет официальной интеграции с Spring
 * TestContext Framework (в отличие от JUnit 5 Jupiter и {@code @ExtendWith(SpringExtension)}),
 * а поднимать полный {@code ApplicationContext} вручную на каждый прогон property избыточно
 * медленно именно для property-тестирования, где важно количество примеров. Атомарность и
 * поведение под реальной БД (транзакции, {@code @Version}, пессимистичные блокировки,
 * конкурентные транзакции) отдельно покрыты существующими Testcontainers-тестами:
 * {@code StockMovementAtomicityIntegrationTest}, {@code StockTransferControllerIntegrationTest}.</p>
 *
 * <p><b>Почему не мокается только сервис целиком.</b> Мок сервисного слоя ничего не говорит
 * об инвариантах — сама суть задачи в том, чтобы проверить, что настоящая реализация
 * {@code StockMovementServiceImpl}/{@code BatchServiceImpl} держит их на случайных
 * последовательностях. Поэтому мокаются только репозитории (границы с БД), а весь бизнес-код
 * между ними — настоящий.</p>
 */
final class StockMovementTestHarness {

    /** Количество складов в пуле: индекс 0 — дефолтный склад, остальные — дополнительные. */
    static final int WAREHOUSE_COUNT = 3;

    private static final Long ITEM_ID = 1L;
    private static final Long USER_ID = 1L;

    private final Item item;
    private final List<Warehouse> warehousePool = new ArrayList<>();
    private final Map<String, Stock> stocksByItemAndWarehouse = new HashMap<>();
    private final List<Batch> batches = new ArrayList<>();
    private final AtomicLong stockIdSeq = new AtomicLong();
    private final AtomicLong batchIdSeq = new AtomicLong();
    private final AtomicLong movementIdSeq = new AtomicLong();
    private final UserContext userContext = new UserContext(USER_ID, "property-test-user");
    private final StockMovementService stockMovementService;

    private long totalReceived;
    private long totalWrittenOff;

    StockMovementTestHarness() {
        this.item = Item.builder()
                .id(ITEM_ID)
                .sku("PBT-ITEM")
                .name("Property test item")
                .minStock(0)
                .active(true)
                .build();

        for (int index = 0; index < WAREHOUSE_COUNT; index++) {
            warehousePool.add(Warehouse.builder()
                    .id((long) index + 1)
                    .name("Warehouse-" + index)
                    .defaultWarehouse(index == 0)
                    .build());
        }

        User user = User.builder()
                .id(USER_ID)
                .username("property-test-user")
                .password("unused")
                .role(Role.ROLE_ADMIN)
                .active(true)
                .build();

        StockRepository stockRepository = mock(StockRepository.class);
        BatchRepository batchRepository = mock(BatchRepository.class);
        StockMovementRepository stockMovementRepository = mock(StockMovementRepository.class);
        ItemRepository itemRepository = mock(ItemRepository.class);
        WarehouseRepository warehouseRepository = mock(WarehouseRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        StockReserveRepository stockReserveRepository = mock(StockReserveRepository.class);

        stubItemRepository(itemRepository);
        stubWarehouseRepository(warehouseRepository);
        stubUserRepository(userRepository, user);
        stubStockRepository(stockRepository);
        stubBatchRepository(batchRepository);
        stubStockMovementRepository(stockMovementRepository);
        stubStockReserveRepository(stockReserveRepository);

        StockAvailabilityService availabilityService =
                new StockAvailabilityService(stockReserveRepository, stockRepository, batchRepository);
        // expiredBatchCleanupService/cacheManager используются только в clearExpiredBatches(),
        // которую этот harness не вызывает (проверяются инварианты receive/write-off/transfer) —
        // моки без стабов, только чтобы удовлетворить конструктор.
        ExpiredBatchCleanupService expiredBatchCleanupService = mock(ExpiredBatchCleanupService.class);
        CacheManager cacheManager = mock(CacheManager.class);
        BatchService batchService = new BatchServiceImpl(
                batchRepository,
                stockRepository,
                availabilityService,
                userRepository,
                expiredBatchCleanupService,
                cacheManager
        );
        AuditContext auditContext = new AuditContext(new ObjectMapper());
        MetricService metricService = new MetricService(new SimpleMeterRegistry());
        OutboxService outboxService = event -> {
            // Low-stock alert outbox не участвует в проверяемых инвариантах остатков.
        };
        StockMovementMapper mapper = (entity, stockAfter, lowStockAlert) -> null;
        // stockMovementKeysetRepository/cursorCodec используются только keyset-пагинацией
        // истории движений, которую harness не вызывает — моки без стабов.
        StockMovementKeysetRepository stockMovementKeysetRepository = mock(StockMovementKeysetRepository.class);
        KeysetCursorCodec cursorCodec = mock(KeysetCursorCodec.class);

        this.stockMovementService = new StockMovementServiceImpl(
                mapper,
                availabilityService,
                itemRepository,
                stockMovementRepository,
                stockMovementKeysetRepository,
                userRepository,
                warehouseRepository,
                stockRepository,
                batchService,
                batchRepository,
                outboxService,
                metricService,
                auditContext,
                cursorCodec
        );
    }

    /**
     * Продакшн-реализация сервиса, поднятая этим harness'ом — для тестов, которым нужно
     * вызвать сервис напрямую и проверить конкретное исключение, а не свёрнутый в {@code
     * boolean} результат {@link #apply}.
     *
     * @return {@code StockMovementService}, используемый {@link #apply}
     */
    StockMovementService service() {
        return stockMovementService;
    }

    /**
     * Контекст пользователя, от имени которого harness выполняет операции.
     *
     * @return {@code UserContext}, используемый {@link #apply}
     */
    UserContext userContext() {
        return userContext;
    }

    /**
     * Идентификатор единственного товара, с которым работает harness.
     *
     * @return id товара, созданного в конструкторе
     */
    Long itemId() {
        return item.getId();
    }

    /**
     * Идентификатор склада по индексу в пуле, созданном конструктором.
     *
     * @param warehouseIndex индекс склада в пуле
     * @return id склада с этим индексом
     */
    Long warehouseId(int warehouseIndex) {
        return warehousePool.get(warehouseIndex).getId();
    }

    /**
     * Применяет одну операцию к текущему состоянию склада через реальный
     * {@code StockMovementService}. Ожидаемые доменные отклонения (недостаточно остатка,
     * некорректное количество) перехватываются и трактуются как отсутствие изменений —
     * ровно так, как ведёт себя продакшн-сервис.
     *
     * @param operation операция из случайно сгенерированной последовательности
     * @return {@code true}, если операция была успешно применена
     */
    boolean apply(StockOperation operation) {
        return switch (operation.kind()) {
            case RECEIVE -> receive(operation.quantity());
            case WRITE_OFF -> writeOff(operation.quantity());
            case TRANSFER -> transfer(operation.fromWarehouseIndex(), operation.toWarehouseIndex(),
                    operation.quantity());
        };
    }

    /**
     * Текущий остаток на складе с указанным индексом в пуле.
     *
     * @param warehouseIndex индекс склада в пуле, созданном каркасом
     * @return текущее количество единиц товара на складе, 0 если для склада ещё нет строки Stock
     */
    int stockAt(int warehouseIndex) {
        Stock stock = stocksByItemAndWarehouse.get(stockKey(ITEM_ID, warehousePool.get(warehouseIndex).getId()));
        if (stock == null) {
            return 0;
        }
        return stock.getQuantity();
    }

    /**
     * Суммарный остаток товара по всем складам пула.
     *
     * @return сумма количеств по всем складам, на которых заведена строка Stock
     */
    long totalStock() {
        return stocksByItemAndWarehouse.values().stream().mapToLong(Stock::getQuantity).sum();
    }

    /**
     * Суммарное количество, успешно принятое по всем операциям прихода.
     *
     * @return сумма количеств из всех успешных вызовов {@code registerReceipt}
     */
    long totalReceived() {
        return totalReceived;
    }

    /**
     * Суммарное количество, успешно списанное по всем операциям списания.
     *
     * @return сумма количеств из всех успешных вызовов {@code writeOffReceipt}
     */
    long totalWrittenOff() {
        return totalWrittenOff;
    }

    private boolean receive(int quantity) {
        try {
            stockMovementService.registerReceipt(
                    new ReceiveStockRequest(ITEM_ID, quantity, LocalDateTime.now().plusYears(1)),
                    userContext
            );
            totalReceived += quantity;
            return true;
        } catch (InvalidMovementRequestException rejected) {
            return false;
        }
    }

    private boolean writeOff(int quantity) {
        try {
            stockMovementService.writeOffReceipt(new WriteOffStockRequest(ITEM_ID, quantity), userContext);
            totalWrittenOff += quantity;
            return true;
        } catch (InsufficientStockException | IllegalArgumentException rejected) {
            return false;
        } catch (EntityNotFoundException noStockYet) {
            // Списание раньше самого первого прихода: строка Stock для дефолтного склада ещё
            // не создана (её создаёт только приход), поэтому BatchServiceImpl.lockStock падает
            // с EntityNotFoundException, а не InsufficientStockException — с точки зрения
            // остатков это тот же исход "списывать нечего", состояние не меняется.
            return false;
        }
    }

    private boolean transfer(int fromWarehouseIndex, int toWarehouseIndex, int quantity) {
        Long fromWarehouseId = warehousePool.get(fromWarehouseIndex).getId();
        Long toWarehouseId = warehousePool.get(toWarehouseIndex).getId();
        try {
            stockMovementService.transfer(
                    new TransferStockRequest(ITEM_ID, fromWarehouseId, toWarehouseId, quantity),
                    userContext
            );
            return true;
        } catch (InsufficientStockException | InvalidMovementRequestException rejected) {
            // QA-8: StockMovementServiceImpl.transfer() теперь сам отклоняет quantity<=0
            // доменным InvalidMovementRequestException, симметрично registerReceipt() —
            // до похода в репозиторий, а не на @PrePersist-инварианте StockMovement.
            return false;
        }
    }

    private void stubItemRepository(ItemRepository itemRepository) {
        when(itemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));
    }

    private void stubWarehouseRepository(WarehouseRepository warehouseRepository) {
        when(warehouseRepository.findByDefaultWarehouseTrue()).thenReturn(Optional.of(warehousePool.get(0)));
        when(warehouseRepository.findById(any(Long.class))).thenAnswer(invocation -> {
            Long warehouseId = invocation.getArgument(0, Long.class);
            return warehousePool.stream().filter(w -> w.getId().equals(warehouseId)).findFirst();
        });
    }

    private void stubUserRepository(UserRepository userRepository, User user) {
        when(userRepository.getReferenceById(USER_ID)).thenReturn(user);
    }

    private void stubStockReserveRepository(StockReserveRepository stockReserveRepository) {
        when(stockReserveRepository.findActiveReserveSumByStock(
                any(Stock.class), any(ReservationStatus.class), any(LocalDateTime.class)
        )).thenReturn(0);
    }

    private void stubStockRepository(StockRepository stockRepository) {
        when(stockRepository.createEmptyStockIfAbsent(any(Long.class), any(Long.class))).thenAnswer(invocation -> {
            Long itemId = invocation.getArgument(0, Long.class);
            Long warehouseId = invocation.getArgument(1, Long.class);
            String key = stockKey(itemId, warehouseId);
            if (stocksByItemAndWarehouse.containsKey(key)) {
                return 0;
            }
            stocksByItemAndWarehouse.put(key, Stock.builder()
                    .id(stockIdSeq.incrementAndGet())
                    .item(item)
                    .warehouse(warehouseById(warehouseId))
                    .quantity(0)
                    .build());
            return 1;
        });

        when(stockRepository.findByItemIdAndWarehouseIdForUpdate(any(Long.class), any(Long.class)))
                .thenAnswer(invocation -> Optional.ofNullable(stocksByItemAndWarehouse.get(stockKey(
                        invocation.getArgument(0, Long.class), invocation.getArgument(1, Long.class)))));

        when(stockRepository.findByItemAndWarehousesForUpdate(any(Long.class), anyList())).thenAnswer(invocation -> {
            Long itemId = invocation.getArgument(0, Long.class);
            List<Long> warehouseIds = invocation.getArgument(1, List.class);
            return warehouseIds.stream()
                    .map(warehouseId -> stocksByItemAndWarehouse.get(stockKey(itemId, warehouseId)))
                    .filter(Objects::nonNull)
                    .toList();
        });

        when(stockRepository.findQuantityByItemId(any(Long.class))).thenAnswer(invocation -> {
            Long itemId = invocation.getArgument(0, Long.class);
            Stock stock = stocksByItemAndWarehouse.get(stockKey(itemId, warehousePool.get(0).getId()));
            if (stock == null) {
                return Optional.<Integer>empty();
            }
            return Optional.of(stock.getQuantity());
        });

        when(stockRepository.findTotalQuantityByItemId(any(Long.class))).thenAnswer(invocation -> {
            Long itemId = invocation.getArgument(0, Long.class);
            return stocksByItemAndWarehouse.values().stream()
                    .filter(stock -> stock.getItem().getId().equals(itemId))
                    .mapToLong(Stock::getQuantity)
                    .sum();
        });

        when(stockRepository.save(any(Stock.class))).thenAnswer(invocation -> {
            Stock stock = invocation.getArgument(0, Stock.class);
            trackStock(stock);
            return stock;
        });

        when(stockRepository.saveAll(anyList())).thenAnswer(invocation -> {
            List<Stock> stocks = invocation.getArgument(0, List.class);
            stocks.forEach(this::trackStock);
            return stocks;
        });
    }

    private void stubBatchRepository(BatchRepository batchRepository) {
        when(batchRepository.save(any(Batch.class))).thenAnswer(invocation -> {
            Batch batch = invocation.getArgument(0, Batch.class);
            trackBatch(batch);
            return batch;
        });

        when(batchRepository.saveAll(anyList())).thenAnswer(invocation -> {
            List<Batch> saved = invocation.getArgument(0, List.class);
            saved.forEach(this::trackBatch);
            return saved;
        });

        when(batchRepository.findNonExpiredByItemAndWarehouseOrderByExpiryDateAscForUpdate(
                any(Long.class), any(Long.class), any(LocalDateTime.class)
        )).thenAnswer(invocation -> {
            Long itemId = invocation.getArgument(0, Long.class);
            Long warehouseId = invocation.getArgument(1, Long.class);
            LocalDateTime now = invocation.getArgument(2, LocalDateTime.class);
            return batches.stream()
                    .filter(batch -> batch.getItem().getId().equals(itemId))
                    .filter(batch -> batch.getWarehouse().getId().equals(warehouseId))
                    .filter(batch -> batch.getExpiryDate().isAfter(now))
                    .filter(batch -> batch.getQuantity() > 0)
                    .sorted(Comparator.comparing(Batch::getExpiryDate).thenComparing(Batch::getId))
                    .toList();
        });

        when(batchRepository.findNonExpiredSumByItemAndWarehouse(
                any(Long.class), any(Long.class), any(LocalDateTime.class)
        )).thenAnswer(invocation -> {
            Long itemId = invocation.getArgument(0, Long.class);
            Long warehouseId = invocation.getArgument(1, Long.class);
            LocalDateTime now = invocation.getArgument(2, LocalDateTime.class);
            return batches.stream()
                    .filter(batch -> batch.getItem().getId().equals(itemId))
                    .filter(batch -> batch.getWarehouse().getId().equals(warehouseId))
                    .filter(batch -> batch.getExpiryDate().isAfter(now))
                    .mapToLong(Batch::getQuantity)
                    .sum();
        });
    }

    /**
     * Эмулирует {@code @PrePersist}-инвариант {@code StockMovement} (количество должно быть
     * положительным для всех типов, кроме {@code ADJUSTMENT}) — см. {@code StockMovement.java},
     * метод, аннотированный {@code @PrePersist} (на момент написания — строка 85). Mockito-моки
     * не проходят через настоящий JPA-жизненный цикл, поэтому эта проверка, в реальной БД
     * срабатывающая при flush'е, здесь воспроизведена явно — иначе тест не заметил бы её
     * нарушение. Копия не связана с оригиналом компилятором: при изменении инварианта в
     * {@code StockMovement} эту копию нужно обновить вручную, иначе harness молча разойдётся
     * с реальным поведением сущности.
     *
     * @param stockMovementRepository мок репозитория движений, на который навешиваются стабы
     */
    private void stubStockMovementRepository(StockMovementRepository stockMovementRepository) {
        when(stockMovementRepository.save(any(StockMovement.class)))
                .thenAnswer(invocation -> persistMovement(invocation.getArgument(0, StockMovement.class)));

        when(stockMovementRepository.saveAllAndFlush(anyList())).thenAnswer(invocation -> {
            List<StockMovement> movements = invocation.getArgument(0, List.class);
            movements.forEach(this::persistMovement);
            return movements;
        });
    }

    private StockMovement persistMovement(StockMovement movement) {
        if (movement.getType() != MovementType.ADJUSTMENT && movement.getQuantity() <= 0) {
            throw new StockMovementInvariantException("Quantity must be greater than zero for type "
                    + movement.getType());
        }
        movement.setId(movementIdSeq.incrementAndGet());
        return movement;
    }

    private void trackStock(Stock stock) {
        stocksByItemAndWarehouse.put(stockKey(stock.getItem().getId(), stock.getWarehouse().getId()), stock);
    }

    private void trackBatch(Batch batch) {
        if (batch.getId() == null) {
            batch.setId(batchIdSeq.incrementAndGet());
            batches.add(batch);
            return;
        }
        if (batches.stream().noneMatch(existing -> existing == batch)) {
            batches.add(batch);
        }
    }

    private Warehouse warehouseById(Long warehouseId) {
        return warehousePool.stream()
                .filter(warehouse -> warehouse.getId().equals(warehouseId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Unknown warehouse id " + warehouseId));
    }

    private static String stockKey(Long itemId, Long warehouseId) {
        return itemId + ":" + warehouseId;
    }
}
