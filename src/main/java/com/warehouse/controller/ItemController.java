package com.warehouse.controller;

import com.warehouse.dto.request.item.CreateItemRequest;
import com.warehouse.dto.request.item.UpdateItemRequest;
import com.warehouse.dto.response.item.ItemDetailsResponse;
import com.warehouse.dto.response.item.ItemResponse;
import com.warehouse.dto.response.PageResponse;
import com.warehouse.service.item.ItemService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.DeleteMapping;

import java.util.List;

@RestController
@RequestMapping("/api/items")
@RequiredArgsConstructor
@Slf4j
public class ItemController {

    private final ItemService itemService;

    @GetMapping
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    public PageResponse<ItemResponse> getItems(
            @RequestParam(defaultValue = "name") String sort,
            @RequestParam(defaultValue = "asc") String order,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        log.debug("Received get items request: sort={}, order={}, category={}, search={}, page={}, size={}",
                sort, order, category, search, page, size);
        return itemService.getItems(sort, order, category, search, page, size);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public ItemResponse createItem(@Valid @RequestBody CreateItemRequest request) {
        log.debug("Received create item request: sku={}, name={}", request.sku(), request.name());
        return itemService.createItem(request);
    }

    @PutMapping("/{itemId}")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasRole('ADMIN')")
    public ItemResponse updateItem(
            @PathVariable Long itemId,
            @Valid @RequestBody UpdateItemRequest request) {
        log.debug("Received update item request: itemId={}, name={}, category={}", itemId, request.name(),
                request.category());
        return itemService.updateItem(itemId, request);
    }

    @GetMapping("/{itemId}")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    public ItemDetailsResponse getItem(@PathVariable Long itemId) {
        log.debug("Received get item request: itemId={}", itemId);
        return itemService.getItem(itemId);
    }

    @DeleteMapping("/{itemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void softDeleteItem(@PathVariable Long itemId) {
        log.debug("Received soft delete item request: itemId={}", itemId);
        itemService.softDeleteItem(itemId);
    }

    @GetMapping("/categories")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    public List<String> getCategories() {
        log.debug("Received get categories request");
        return itemService.getCategories();
    }
}