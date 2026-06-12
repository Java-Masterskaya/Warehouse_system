package com.warehouse.controller;

import com.warehouse.dto.request.item.CreateItemRequest;
import com.warehouse.dto.request.item.UpdateItemRequest;
import com.warehouse.dto.response.PageResponse;
import com.warehouse.dto.response.item.ItemResponse;
import com.warehouse.service.item.ItemService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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

@RestController
@RequestMapping("/api/v1/items")
@RequiredArgsConstructor
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
        return itemService.getItems(sort, order, category, search, page, size);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public ItemResponse createItem(@Valid @RequestBody CreateItemRequest request) {
        return itemService.createItem(request);
    }

    @PutMapping("/{itemId}")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasRole('ADMIN')")
    public ItemResponse updateItem(
            @PathVariable Long itemId,
            @Valid @RequestBody UpdateItemRequest request) {
        return itemService.updateItem(itemId, request);
    }

    /**
     * Скрывает товар из выдачи по его идентификатору.
     * <p>
     * Доступен только пользователям с ролью ADMIN.
     *
     * @param itemId идентификатор скрываемого товара
     * @throws EntityNotFoundException если товар не найден
     */
    @DeleteMapping("/{itemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void softDeleteItem(@PathVariable Long itemId) {
        itemService.softDeleteItem(itemId);
    }
}