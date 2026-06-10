package com.warehouse.service.item;

import com.warehouse.dto.request.item.UpdateItemRequest;
import com.warehouse.dto.request.item.CreateItemRequest;
import com.warehouse.dto.response.item.ItemResponse;
import com.warehouse.dto.response.ItemDetailsResponse;
import com.warehouse.dto.response.PageResponse;
import com.warehouse.entity.Item;
import com.warehouse.entity.Stock;
import com.warehouse.exception.DuplicateSkuException;
import com.warehouse.exception.EntityNotFoundException;
import com.warehouse.mapper.ItemMapper;
import com.warehouse.repository.ItemRepository;
import com.warehouse.repository.StockRepository;
import com.warehouse.specification.ItemSpecification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@Service
@RequiredArgsConstructor
public class ItemServiceImpl implements ItemService {

    private final ItemRepository itemRepository;
    private final StockRepository stockRepository;
    private final ItemMapper itemMapper;

    @Transactional
    @Override
    public ItemResponse createItem(CreateItemRequest request) {
        log.debug("Creating item with SKU '{}'", request.sku());

        if (itemRepository.existsBySku(request.sku())) {
            log.warn("Duplicate SKU '{}' — item already exists", request.sku());
            throw DuplicateSkuException.forSku(request.sku());
        }

        Item item = itemMapper.toEntity(request);
        itemRepository.save(item);

        Stock stock = new Stock();
        stock.setItem(item);
        stock.setQuantity(0);
        stockRepository.save(stock);

        log.info("Item created: id={}, SKU='{}'", item.getId(), item.getSku());
        return itemMapper.toResponse(item);
    }

    @Transactional
    @Override
    public ItemResponse updateItem(Long itemId, UpdateItemRequest request) {
        Item item = itemRepository.findById(itemId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Товар с itemId " + itemId + " не найден"
                ));
        if (!item.isActive()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Товар неактивен и не подлежит редактированию"
            );
        }

        item.setName(request.name());
        item.setCategory(request.category());
        item.setMinStock(request.minStock());

        Item savedItem = itemRepository.save(item);
        return itemMapper.toResponse(savedItem);
    }

    @Transactional(readOnly = true)
    @Override
    public PageResponse<ItemResponse> getItems(
            String sort, String order, String category, String search, int page, int size) {
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

        return PageResponse.from(itemRepository.findAll(spec, pageable).map(itemMapper::toResponse));
    }

    @Override
    @Transactional(readOnly = true)
    public ItemDetailsResponse getItem(Long itemId) {
        log.debug("Getting item with id '{}'", itemId);
        ItemDetailsResponse item = itemRepository.findWithStock(itemId)
                        .orElseThrow(() -> {
                            log.warn("Item not found: id={}", itemId);
                            return new EntityNotFoundException("Товар не найден");
                        });

        if (!item.active()) {
            log.warn("Item inactive: id={}", itemId);
            throw new EntityNotFoundException("Товар неактивен");
        }

        return item;
    }
}