package com.warehouse.controller;

import com.warehouse.dto.request.item.CreateItemRequest;
import com.warehouse.dto.request.item.UpdateItemRequest;
import com.warehouse.dto.response.category.CategoryResponse;
import com.warehouse.dto.response.item.ItemDetailsResponse;
import com.warehouse.dto.response.item.ItemImportResultDto;
import com.warehouse.dto.response.item.ItemPaginationResponse;
import com.warehouse.dto.response.item.ItemResponse;
import com.warehouse.exception.InvalidCursorException;
import com.warehouse.service.category.CategoryService;
import com.warehouse.service.import_export.CsvExportService;
import com.warehouse.service.import_export.CsvImportService;
import com.warehouse.service.item.ItemService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/items")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Товары", description = "Управление каталогом товаров")
@SecurityRequirement(name = "bearerAuth")
@Validated
public class ItemController {

    private final ItemService      itemService;
    private final CsvExportService csvExportService;
    private final CsvImportService csvImportService;
    private final CategoryService  categoryService;

    @Operation(summary = "Список товаров", description = "Постраничный список с фильтрацией и сортировкой")
    @GetMapping
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    public ItemPaginationResponse getItems(
            @RequestParam(defaultValue = "name") String sort,
            @RequestParam(defaultValue = "asc") String order,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String search,
            @Parameter(schema = @Schema(
                    type = "integer",
                    format = "int32",
                    defaultValue = "0",
                    minimum = "0"
            ))
            @RequestParam(required = false) @Min(0) Integer page,
            @Parameter(description = "Page size. Maximum 100.")
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @Parameter(
                    description = "Opaque keyset cursor. Supply an empty value to start cursor pagination.",
                    allowEmptyValue = true
            )
            @RequestParam(required = false) String cursor
    ) {
        if (cursor != null) {
            if (page != null) {
                throw new InvalidCursorException();
            }
            log.debug(
                    "Received cursor item request: sort={}, order={}, category={}, search={}, size={}",
                    sort,
                    order,
                    category,
                    search,
                    size
            );
            return ItemPaginationResponse.from(
                    itemService.getItemsByCursor(sort, order, category, search, cursor, size)
            );
        }

        int requestedPage = 0;
        if (page != null) {
            requestedPage = page;
        }
        log.debug("Received get items request: sort={}, order={}, category={}, search={}, page={}, size={}",
                sort, order, category, search, requestedPage, size);
        return ItemPaginationResponse.from(
                itemService.getItems(sort, order, category, search, requestedPage, size)
        );
    }

    @Operation(summary = "Создать товар")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public ItemResponse createItem(@Valid @RequestBody CreateItemRequest request) {
        log.debug("Received create item request: sku={}, name={}", request.sku(), request.name());
        return itemService.createItem(request);
    }

    @Operation(summary = "Редактировать товар")
    @PutMapping("/{itemId}")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasRole('ADMIN')")
    public ItemResponse updateItem(
            @PathVariable Long itemId,
            @Valid @RequestBody UpdateItemRequest request
    ) {
        log.debug("Received update item request: itemId={}, name={}, category={}", itemId, request.name(),
                request.category());
        return itemService.updateItem(itemId, request);
    }

    @Operation(summary = "Карточка товара")
    @GetMapping("/{itemId}")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    public ItemDetailsResponse getItem(@PathVariable Long itemId) {
        log.debug("Received get item request: itemId={}", itemId);
        return itemService.getItem(itemId);
    }

    @Operation(summary = "Удалить товар (soft delete)")
    @DeleteMapping("/{itemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void softDeleteItem(@PathVariable Long itemId) {
        log.debug("Received soft delete item request: itemId={}", itemId);
        itemService.softDeleteItem(itemId);
    }

    @Operation(summary = "Список категорий")
    @GetMapping("/categories")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    public List<CategoryResponse> getCategories() {
        log.debug("Received get categories request");
        return categoryService.getCategories();
    }

    @Operation(summary = "Экспорт списка товаров")
    @GetMapping("/export")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<StreamingResponseBody> exportItems(
            Authentication authentication,
            @RequestParam(value = "active", required = false, defaultValue = "true") Boolean active
    ) {
        SecurityContext context = SecurityContextHolder.getContext();
        StreamingResponseBody responseBody = outputStream -> {
            SecurityContextHolder.setContext(context);
            try (OutputStreamWriter writer = new OutputStreamWriter(outputStream, StandardCharsets.UTF_8)) {
                csvExportService.exportItems(writer, active);
            } catch (UncheckedIOException e) {
                if (e.getCause() instanceof IOException) {
                    log.warn("Клиент прервал загрузку CSV файла (ClientAbortException)");
                } else {
                    log.error("Ошибка при стриминге CSV файла", e);
                }
            } finally {
                SecurityContextHolder.clearContext();
            }
        };

        return ResponseEntity.ok()
                             .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
                             .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"items.csv\"")
                             .body(responseBody);
    }

    @Operation(summary = "Импорт товаров из csv")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ItemImportResultDto> importItems(@RequestParam("file") MultipartFile file) {
        ItemImportResultDto result = csvImportService.importItems(file);
        return ResponseEntity.ok(result);
    }
}