package com.warehouse.service.item;

import com.warehouse.dto.request.item.CreateItemRequest;
import com.warehouse.dto.request.item.UpdateItemRequest;
import com.warehouse.dto.response.PageResponse;
import com.warehouse.dto.response.item.ItemDetailsProjection;
import com.warehouse.dto.response.item.ItemDetailsResponse;
import com.warehouse.dto.response.item.ItemResponse;
import com.warehouse.dto.response.item.WarehouseStockResponse;
import com.warehouse.entity.Item;
import com.warehouse.entity.Stock;
import com.warehouse.entity.Warehouse;
import com.warehouse.exception.DuplicateBarcodeException;
import com.warehouse.exception.DuplicateSkuException;
import com.warehouse.exception.EntityNotFoundException;
import com.warehouse.mapper.ItemMapper;
import com.warehouse.repository.ItemRepository;
import com.warehouse.repository.StockRepository;
import com.warehouse.repository.WarehouseRepository;
import com.warehouse.service.reservation.StockAvailabilityService;
import com.warehouse.specification.ItemSpecification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ItemServiceImpl implements ItemService {

    private final ItemRepository itemRepository;
    private final StockRepository stockRepository;
    private final WarehouseRepository warehouseRepository;
    private final ItemMapper itemMapper;
    private final StockAvailabilityService availabilityService;

    @Transactional
    @Override
    @CacheEvict(value = "categories", allEntries = true)
    public ItemResponse createItem(CreateItemRequest request) {
        log.debug("Creating item with SKU '{}'", request.sku());

        if (itemRepository.existsBySku(request.sku())) {
            log.warn("Duplicate SKU '{}' — item already exists", request.sku());
            throw DuplicateSkuException.forSku(request.sku());
        }

        Item item = itemMapper.toEntity(request);
        item.setPrice(confirmPrice(request.price()));
        item.setCost(confirmCost(request.cost()));

        // Проверка уникальности пользовательского barcode (OPS-5)
        if (request.barcode() != null && !request.barcode().isBlank()) {
            if (itemRepository.existsByBarcode(request.barcode())) {
                log.warn("Duplicate barcode '{}' — item already exists", request.barcode());
                throw DuplicateBarcodeException.forBarcode(request.barcode());
            }
            item.setBarcode(request.barcode());
        }

        itemRepository.save(item);

        Warehouse defaultWarehouse = warehouseRepository.findByDefaultWarehouseTrue()
                .orElseThrow(() -> new IllegalStateException("Default warehouse is not configured"));

        // Генерируем штрихкод на основе id, если не задан в запросе
        if (item.getBarcode() == null || item.getBarcode().isBlank()) {
            item.setBarcode(String.format("ITEM-%010d", item.getId()));
            itemRepository.save(item);
        }

        Stock stock = new Stock();
        stock.setItem(item);
        stock.setWarehouse(defaultWarehouse);
        stock.setQuantity(0);
        stockRepository.save(stock);

        log.info("Item created: id={}, SKU='{}'", item.getId(), item.getSku());
        return itemMapper.toResponse(item);
    }

    @Transactional
    @Override
    @Caching(evict = {
        @CacheEvict(value = "item", key = "#itemId"),
        @CacheEvict(value = "categories", allEntries = true)
    })
    public ItemResponse updateItem(Long itemId, UpdateItemRequest request) {
        log.debug("Updating item with id={}", itemId);

        Item item = itemRepository.findById(itemId)
                .orElseThrow(() -> {
                    log.warn("Item with id={} not found", itemId);
                    return EntityNotFoundException.forId("Item", itemId);
                });

        if (!item.isActive()) {
            log.warn("Attempt to update inactive item with id={}", itemId);
            throw EntityNotFoundException.forId("Item", itemId);
        }

        item.setName(request.name());
        item.setCategory(request.category());
        item.setMinStock(request.minStock());
        item.setPrice(confirmPrice(request.price()));
        item.setCost(confirmCost(request.cost()));

        // Проверка уникальности barcode при обновлении (OPS-5)
        if (request.barcode() != null && !request.barcode().isBlank()
                && !request.barcode().equals(item.getBarcode())) {
            if (itemRepository.existsByBarcode(request.barcode())) {
                log.warn("Duplicate barcode '{}' — cannot update item id={}", request.barcode(), itemId);
                throw DuplicateBarcodeException.forBarcode(request.barcode());
            }
            item.setBarcode(request.barcode());
        }

        Item savedItem = itemRepository.save(item);
        log.info("Item updated: id={}, SKU='{}'", savedItem.getId(), savedItem.getSku());
        return itemMapper.toResponse(savedItem);
    }

    @Transactional(readOnly = true)
    @Override
    public PageResponse<ItemResponse> getItems(
            String sort, String order, String category, String search, int page, int size) {
        log.debug("Getting items: sort={}, order={}, category={}, search={}, page={}, size={}",
                sort, order, category, search, page, size);

        Sort.Direction direction;
        if ("desc".equalsIgnoreCase(order)) {
            direction = Sort.Direction.DESC;
        } else {
            direction = Sort.Direction.ASC;
        }
        String sortField;
        if ("sku".equalsIgnoreCase(sort)) {
            sortField = "sku";
        } else {
            sortField = "name";
        }

        Specification<Item> spec = Specification.where(ItemSpecification.isActive());
        if (category != null && !category.isBlank()) {
            spec = spec.and(ItemSpecification.hasCategory(category));
        }
        if (search != null && !search.isBlank()) {
            spec = spec.and(ItemSpecification.nameContains(search));
        }

        PageRequest pageable = PageRequest.of(page, size, Sort.by(direction, sortField));

        var itemsPage = itemRepository.findAll(spec, pageable);
        log.info("Found {} items for request (category={}, search={})", itemsPage.getTotalElements(), category, search);

        return PageResponse.from(itemsPage.map(itemMapper::toResponse));
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(value = "item", key = "#itemId")
    public ItemDetailsResponse getItem(Long itemId) {
        log.debug("Getting item with id '{}'", itemId);
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
        long available = item.currentStock() - reserved;

        List<WarehouseStockResponse> warehouseStocks = stockRepository.findAllByItemIdWithWarehouse(itemId).stream()
                .map(this::toWarehouseStockResponse)
                .toList();

        return itemMapper.mapProjectionToDetailsResponse(item, available, reserved, warehouseStocks);
    }

    @Transactional
    @Override
    @CacheEvict(value = "item", key = "#itemId")
    public void softDeleteItem(Long itemId) {
        Item item = itemRepository.findById(itemId).orElseThrow(() -> {
            log.warn("Item с id={} не найден", itemId);
            return EntityNotFoundException.forId("Item", itemId);
        });

        if (!item.isActive()) {
            log.warn("Item с id={} уже неактивный", itemId);
            throw new EntityNotFoundException("Item with id=" + itemId + " is already deactivated");
        }

        item.setActive(false);
        log.info("Item c id={} успешно деактивирован", itemId);
    }

    @Cacheable(value = "categories")
    @Transactional(readOnly = true)
    @Override
    public List<String> getCategories() {
        log.debug("Getting all active categories");
        List<String> categories = itemRepository.findDistinctCategories();
        log.info("Found {} categories: {}", categories.size(), categories);
        return categories;
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

    private WarehouseStockResponse toWarehouseStockResponse(Stock stock) {
        long reserved = availabilityService.getReserved(stock);
        return new WarehouseStockResponse(
                stock.getWarehouse().getId(),
                stock.getWarehouse().getName(),
                stock.getQuantity(),
                reserved,
                (long) stock.getQuantity() - reserved
        );
    }
}
