package com.warehouse.service.item;

import com.warehouse.audit.AuditContext;
import com.warehouse.audit.Auditable;
import com.warehouse.audit.entity.AuditAction;
import com.warehouse.audit.entity.EntityType;
import com.warehouse.dto.request.item.CreateItemRequest;
import com.warehouse.dto.request.item.UpdateItemRequest;
import com.warehouse.dto.response.CursorPageResponse;
import com.warehouse.dto.response.PageResponse;
import com.warehouse.dto.response.item.ItemDetailsProjection;
import com.warehouse.dto.response.item.ItemDetailsResponse;
import com.warehouse.dto.response.item.ItemResponse;
import com.warehouse.entity.Category;
import com.warehouse.dto.response.item.WarehouseStockResponse;
import com.warehouse.entity.Item;
import com.warehouse.entity.Stock;
import com.warehouse.entity.Warehouse;
import com.warehouse.exception.DuplicateBarcodeException;
import com.warehouse.exception.DuplicateSkuException;
import com.warehouse.exception.EntityNotFoundException;
import com.warehouse.exception.InvalidCursorException;
import com.warehouse.exception.ReservedBarcodeFormatException;
import com.warehouse.mapper.ItemMapper;
import com.warehouse.pagination.KeysetCursorCodec;
import com.warehouse.pagination.KeysetCursorCodec.CursorContext;
import com.warehouse.pagination.KeysetCursorCodec.CursorPosition;
import com.warehouse.repository.CategoryRepository;
import com.warehouse.repository.ItemKeysetRepository;
import com.warehouse.repository.ItemRepository;
import com.warehouse.repository.StockRepository;
import com.warehouse.repository.WarehouseRepository;
import com.warehouse.service.reservation.StockAvailabilityService;
import com.warehouse.specification.ItemSpecification;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
public class ItemServiceImpl implements ItemService {

    private static final String CURSOR_ENDPOINT = "items";
    private static final int MAX_ITEM_NAME_LENGTH = 255;
    private static final int MAX_ITEM_SKU_LENGTH = 100;
    private static final int MAX_CURSOR_PAGE_SIZE = 100;

    private final ItemRepository itemRepository;
    private final ItemKeysetRepository itemKeysetRepository;
    private final StockRepository stockRepository;
    private final WarehouseRepository warehouseRepository;
    private final ItemMapper itemMapper;
    private final AuditContext auditContext;
    private final StockAvailabilityService availabilityService;
    private final CategoryRepository categoryRepository;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final ItemBarcodeGeneratorService barcodeGenerator;
    private final KeysetCursorCodec cursorCodec;

    @Transactional
    @Override
    @Auditable(action = AuditAction.CREATE, entityType = EntityType.ITEM)
    public ItemResponse createItem(CreateItemRequest request) {
        log.debug("Creating item with SKU '{}'", request.sku());

        if (itemRepository.existsBySku(request.sku())) {
            log.warn("Duplicate SKU '{}' — item already exists", request.sku());
            throw DuplicateSkuException.forSku(request.sku());
        }

        Item item = itemMapper.toEntity(request);
        item.setCategory(getCategory(request.category()));
        item.setPrice(confirmPrice(request.price()));
        item.setCost(confirmCost(request.cost()));

        // Проверка уникальности пользовательского barcode
        if (request.barcode() != null && !request.barcode().isBlank()) {
            if (barcodeGenerator.matchesReservedFormat(request.barcode())) {
                log.warn("Manual barcode '{}' uses reserved auto-generation format — rejected", request.barcode());
                throw ReservedBarcodeFormatException.forBarcode(request.barcode());
            }
            if (itemRepository.existsByBarcode(request.barcode())) {
                log.warn("Duplicate barcode '{}' — item already exists", request.barcode());
                throw DuplicateBarcodeException.forBarcode(request.barcode());
            }
            item.setBarcode(request.barcode());
        }
        if (item.getBarcode() == null || item.getBarcode().isBlank()) {
            item.setBarcode(barcodeGenerator.generate());
        }

        itemRepository.save(item);

        Warehouse defaultWarehouse = warehouseRepository.findByDefaultWarehouseTrue()
                .orElseThrow(() -> new IllegalStateException("Default warehouse is not configured"));

        Stock stock = new Stock();
        stock.setItem(item);
        stock.setWarehouse(defaultWarehouse);
        stock.setQuantity(0);
        stockRepository.save(stock);

        auditContext.setEntityId(item.getId());
        auditContext.setNewValue(item);

        log.info("Item created: id={}, SKU='{}'", item.getId(), item.getSku());
        return itemMapper.toResponse(item);
    }

    @Transactional
    @Override
    @CacheEvict(value = "item", key = "#itemId")
    @Auditable(action = AuditAction.UPDATE, entityType = EntityType.ITEM)
    public ItemResponse updateItem(Long itemId, UpdateItemRequest request) {
        log.debug("Updating item with id={}", itemId);

        Item item = itemRepository.findById(itemId)
                .orElseThrow(() -> {
                    log.warn("Item with id={} not found", itemId);
                    return EntityNotFoundException.forId("Item", itemId);
                });
        auditContext.setEntityId(itemId);
        auditContext.setOldValue(item);
        if (!item.isActive()) {
            log.warn("Attempt to update inactive item with id={}", itemId);
            throw EntityNotFoundException.forId("Item", itemId);
        }

        item.setName(request.name());
        item.setCategory(getCategory(request.category()));
        item.setMinStock(request.minStock());
        item.setPrice(confirmPrice(request.price()));
        item.setCost(confirmCost(request.cost()));

        // Проверка уникальности barcode при обновлении
        if (request.barcode() != null && !request.barcode().isBlank()
                && !request.barcode().equals(item.getBarcode())) {

            if (barcodeGenerator.matchesReservedFormat(request.barcode())) {
                log.warn("Manual barcode '{}' uses reserved auto-generation format — "
                        + "cannot update item id={}", request.barcode(), itemId);
                throw ReservedBarcodeFormatException.forBarcode(request.barcode());
            }
            if (itemRepository.existsByBarcode(request.barcode())) {
                log.warn("Duplicate barcode '{}' — cannot update item id={}", request.barcode(), itemId);
                throw DuplicateBarcodeException.forBarcode(request.barcode());
            }
            item.setBarcode(request.barcode());
        }

        Item savedItem = itemRepository.save(item);
        auditContext.setNewValue(savedItem);
        log.info("Item updated: id={}, SKU='{}'", savedItem.getId(), savedItem.getSku());
        return itemMapper.toResponse(savedItem);
    }

    @Transactional(readOnly = true)
    @Override
    public PageResponse<ItemResponse> getItems(
            String sort, String order, String category, String search, int page, int size) {
        log.debug("Getting items: sort={}, order={}, category={}, search={}, page={}, size={}",
                sort, order, category, search, page, size);

        Sort.Direction direction = resolveDirection(order);
        String sortField = resolveSortField(sort);

        Specification<Item> spec = Specification.where(ItemSpecification.isActive());
        if (category != null && !category.isBlank()) {
            spec = spec.and(ItemSpecification.hasCategory(category));
        }
        if (search != null && !search.isBlank()) {
            spec = spec.and(ItemSpecification.nameContains(search));
        }

        PageRequest pageable = PageRequest.of(page, size, Sort.by(direction, sortField, "id"));

        var itemsPage = itemRepository.findAll(spec, pageable);
        log.info("Found {} items for request (category={}, search={})", itemsPage.getTotalElements(), category, search);

        return PageResponse.from(itemsPage.map(itemMapper::toResponse));
    }

    @Transactional(readOnly = true)
    @Override
    public CursorPageResponse<ItemResponse> getItemsByCursor(
            String sort,
            String order,
            String category,
            String search,
            String cursor,
            int size
    ) {
        validateCursorPageSize(size);
        Sort.Direction direction = resolveDirection(order);
        String sortField = resolveSortField(sort);
        String normalizedCategory = normalizeCategory(category);
        String normalizedSearch = normalizeSearch(search);
        CursorContext context = new CursorContext(
                CURSOR_ENDPOINT,
                sortField,
                direction.name().toLowerCase(Locale.ROOT),
                List.of(
                        KeysetCursorCodec.fingerprint(normalizedCategory),
                        KeysetCursorCodec.fingerprint(normalizedSearch)
                )
        );

        CursorPosition position = null;
        if (cursor != null && !cursor.isEmpty()) {
            position = cursorCodec.decode(cursor, context);
            validateCursorPosition(position, sortField);
        }
        String lastSortValue = null;
        Long lastId = null;
        if (position != null) {
            lastSortValue = position.lastValue();
            lastId = position.lastId();
        }

        List<Item> items = itemKeysetRepository.findNextPage(
                sortField,
                direction,
                normalizedCategory,
                normalizedSearch,
                lastSortValue,
                lastId,
                size + 1
        );
        boolean hasNext = items.size() > size;
        List<Item> pageItems = items.stream().limit(size).toList();
        String nextCursor = null;
        if (hasNext) {
            Item lastItem = pageItems.get(pageItems.size() - 1);
            String lastValue = lastItem.getName();
            if ("sku".equals(sortField)) {
                lastValue = lastItem.getSku();
            }
            nextCursor = cursorCodec.encode(context, lastValue, lastItem.getId());
        }

        return new CursorPageResponse<>(
                pageItems.stream().map(itemMapper::toResponse).toList(),
                nextCursor,
                hasNext
        );
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(value = "item", key = "#itemId")
    @CircuitBreaker(name = "itemCache", fallbackMethod = "getItemFallback")
    public ItemDetailsResponse getItem(Long itemId) {
        log.debug("Getting item with id '{}'", itemId);
        return getItemFromDb(itemId);
    }

    @SuppressWarnings("unused")
    public ItemDetailsResponse getItemFallback(Long itemId, Throwable t) {
        var state = circuitBreakerRegistry
                .circuitBreaker("itemCache")
                .getState();
        log.warn("itemCache call failed (breaker state = {}), fallback to DB. Error: {}",
                state, t.getMessage());
        return getItemFromDb(itemId);
    }

    @Transactional
    @Override
    @CacheEvict(value = "item", key = "#itemId")
    @Auditable(action = AuditAction.DEACTIVATE, entityType = EntityType.ITEM)
    public void softDeleteItem(Long itemId) {
        Item item = itemRepository.findById(itemId).orElseThrow(() -> {
            log.warn("Item с id={} не найден", itemId);
            return EntityNotFoundException.forId("Item", itemId);
        });
        auditContext.setEntityId(itemId);
        auditContext.setOldValue(item);
        if (!item.isActive()) {
            log.warn("Item с id={} уже неактивный", itemId);
            throw new EntityNotFoundException("Item with id=" + itemId + " is already deactivated");
        }

        item.setActive(false);
        auditContext.setNewValue(item);
        log.info("Item c id={} успешно деактивирован", itemId);
    }

    @Override
    public BigDecimal confirmPrice(BigDecimal price) {
        if (price == null) {
            return BigDecimal.ZERO;
        }
        return price.setScale(2, RoundingMode.HALF_UP);
    }

    @Override
    public BigDecimal confirmCost(BigDecimal cost) {
        if (cost == null) {
            return BigDecimal.ZERO;
        }
        return cost.setScale(2, RoundingMode.HALF_UP);
    }

    private Category getCategory(String categoryName) {
        String name = categoryName.trim();
        return categoryRepository.findByNameIgnoreCase(name)
                .orElseThrow(() ->
                        new EntityNotFoundException("Категория " + name + " не найдена"));
    }

    private Sort.Direction resolveDirection(String order) {
        if ("desc".equalsIgnoreCase(order)) {
            return Sort.Direction.DESC;
        }
        return Sort.Direction.ASC;
    }

    private String resolveSortField(String sort) {
        if ("sku".equalsIgnoreCase(sort)) {
            return "sku";
        }
        return "name";
    }

    private String normalizeCategory(String category) {
        if (category == null || category.isBlank()) {
            return null;
        }
        return category;
    }

    private String normalizeSearch(String search) {
        if (search == null || search.isBlank()) {
            return null;
        }
        return search.toLowerCase(Locale.ROOT);
    }

    private void validateCursorPosition(CursorPosition position, String sortField) {
        int maxLength = MAX_ITEM_NAME_LENGTH;
        if ("sku".equals(sortField)) {
            maxLength = MAX_ITEM_SKU_LENGTH;
        }
        String value = position.lastValue();
        if (value.codePointCount(0, value.length()) > maxLength || value.indexOf('\0') >= 0) {
            throw new InvalidCursorException();
        }
    }

    private void validateCursorPageSize(int size) {
        if (size < 1 || size > MAX_CURSOR_PAGE_SIZE) {
            throw new InvalidCursorException();
        }
    }

    private WarehouseStockResponse toWarehouseStockResponse(Stock stock) {
        long reserved = availabilityService.getReserved(stock);
        return new WarehouseStockResponse(
                stock.getWarehouse().getId(),
                stock.getWarehouse().getName(),
                stock.getQuantity(),
                reserved,
                availabilityService.getAvailable(stock)
        );
    }

    private ItemDetailsResponse getItemFromDb(Long itemId) {
        ItemDetailsProjection item = itemRepository.findWithStock(itemId)
                .orElseThrow(() -> {
                    log.warn("Item not found: id={}", itemId);
                    return new EntityNotFoundException("Товар не найден");
                });
        if (!item.active()) {
            log.warn("Item inactive: id={}", itemId);
            throw new EntityNotFoundException("Товар неактивен");
        }
        long reserved = availabilityService.getTotalReserved(itemId);
        long available = availabilityService.getTotalAvailable(itemId);

        List<WarehouseStockResponse> warehouseStocks = stockRepository.findAllByItemIdWithWarehouse(itemId).stream()
                .map(this::toWarehouseStockResponse)
                .toList();

        return itemMapper.mapProjectionToDetailsResponse(item, available, reserved, warehouseStocks);
    }
}
