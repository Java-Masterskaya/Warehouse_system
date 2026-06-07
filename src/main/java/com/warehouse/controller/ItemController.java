package com.warehouse.controller;

import com.warehouse.dto.request.CreateItemRequest;
import com.warehouse.dto.response.ItemDetailsResponse;
import com.warehouse.dto.response.ItemResponse;
import com.warehouse.dto.request.UpdateItemRequest;
import com.warehouse.service.ItemService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/items")
@RequiredArgsConstructor
public class ItemController {

    private final ItemService itemService;

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

    @GetMapping("/{itemId}")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    public ItemDetailsResponse getItem(@PathVariable Long itemId) {
        return itemService.getItem(itemId);
    }
}