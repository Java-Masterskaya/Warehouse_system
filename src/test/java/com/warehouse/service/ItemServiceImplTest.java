package com.warehouse.service;

import com.warehouse.audit.AuditContext;
import com.warehouse.dto.request.item.CreateItemRequest;
import com.warehouse.dto.request.item.UpdateItemRequest;
import com.warehouse.dto.response.PageResponse;
import com.warehouse.dto.response.item.ItemDetailsResponse;
import com.warehouse.dto.response.item.ItemResponse;
import com.warehouse.dto.response.item.ItemDetailsProjection;
import com.warehouse.entity.Category;
import com.warehouse.entity.Item;
import com.warehouse.entity.Stock;
import com.warehouse.entity.Warehouse;
import com.warehouse.exception.DuplicateSkuException;
import com.warehouse.exception.EntityNotFoundException;
import com.warehouse.mapper.ItemMapper;
import com.warehouse.repository.CategoryRepository;
import com.warehouse.repository.ItemRepository;
import com.warehouse.repository.StockRepository;
import com.warehouse.repository.WarehouseRepository;
import com.warehouse.service.item.ItemService;
import com.warehouse.service.item.ItemServiceImpl;
import com.warehouse.service.reservation.StockAvailabilityService;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit-тест для ItemServiceImpl.
 * Тестирует CRUD операции, получение товаров с фильтрацией, сортировкой и пагинацией.
 */
@ExtendWith(MockitoExtension.class)
class ItemServiceImplTest {

    @Mock
    private ItemRepository itemRepository;

    @Mock
    private StockRepository stockRepository;

    @Mock
    private AuditContext auditContext;

    @Mock
    private WarehouseRepository warehouseRepository;

    @Mock
    private StockAvailabilityService availabilityService;

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private CircuitBreakerRegistry circuitBreakerRegistry;

    private final ItemMapper itemMapper = Mappers.getMapper(ItemMapper.class);

    private ItemService itemService;

    @BeforeEach
    void setUp() {
        itemService = new ItemServiceImpl(
                itemRepository,
                stockRepository,
                warehouseRepository,
                itemMapper,
                auditContext,
                availabilityService,
                categoryRepository,
                circuitBreakerRegistry
        );
    }

    /**
     * ADMIN может успешно создать товар,
     * возвращает ItemResponse с данными и сохраняет в репозиторий.
     */
    @Test
    void createItemSuccess() {
        CreateItemRequest request = new CreateItemRequest("SKU-001", "Ноутбук", "Электроника",
                5, BigDecimal.valueOf(100.50), BigDecimal.valueOf(75.25));

        Category category = createCategory("Электроника");

        when(itemRepository.existsBySku("SKU-001")).thenReturn(false);

        when(categoryRepository.findByNameIgnoreCase("Электроника"))
                .thenReturn(Optional.of(category));
        when(warehouseRepository.findByDefaultWarehouseTrue()).thenReturn(Optional.of(
                Warehouse.builder().id(1L).name("Default Warehouse").defaultWarehouse(true).build()
        ));
        when(itemRepository.save(any(Item.class))).thenAnswer(invocation -> {
            Item savedItem = invocation.getArgument(0);
            savedItem.setId(1L);
            savedItem.setCreatedAt(LocalDateTime.now());
            return savedItem;
        });
        when(stockRepository.save(any(Stock.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ItemResponse result = itemService.createItem(request);

        assertThat(result.id()).isEqualTo(1L);
        assertThat(result.sku()).isEqualTo("SKU-001");
        assertThat(result.name()).isEqualTo("Ноутбук");
        assertThat(result.category()).isEqualTo("Электроника");
        assertThat(result.minStock()).isEqualTo(5);
        assertThat(result.active()).isTrue();
        assertThat(result.createdAt()).isNotNull();
        verify(itemRepository).save(any(Item.class));
        verify(stockRepository).save(any(Stock.class));
        verify(categoryRepository).findByNameIgnoreCase("Электроника");
    }

    /**
     * Попытка создать товар с дублирующимся SKU выбрасывает DuplicateSkuException.
     */
    @Test
    void createItemDuplicateSkuThrowsDuplicateSkuException() {
        CreateItemRequest request = new CreateItemRequest("SKU-001", "Ноутбук", "Электроника",
                5, BigDecimal.valueOf(100.50), BigDecimal.valueOf(75.25));

        when(itemRepository.existsBySku("SKU-001")).thenReturn(true);

        assertThatThrownBy(() -> itemService.createItem(request))
                .isInstanceOf(DuplicateSkuException.class)
                .hasMessageContaining("SKU-001");

        verify(itemRepository, never()).save(any());
        verify(stockRepository, never()).save(any());
    }

    /**
     * ADMIN может успешно обновить активный товар.
     */
    @Test
    void updateItemSuccess() {
        Long itemId = 3L;
        Item existingItem = new Item();
        existingItem.setId(itemId);
        existingItem.setName("Старое название");
        existingItem.setCategory(createCategory("Старая категория"));
        existingItem.setMinStock(5);
        existingItem.setActive(true);
        existingItem.setPrice(BigDecimal.valueOf(100.50));
        existingItem.setCost(BigDecimal.valueOf(75.25));

        UpdateItemRequest request = new UpdateItemRequest("Новое название", "Новая категория",
                10, BigDecimal.valueOf(120.00), BigDecimal.valueOf(85.00));

        when(itemRepository.findById(itemId)).thenReturn(Optional.of(existingItem));
        when(categoryRepository.findByNameIgnoreCase("Новая категория"))
                .thenReturn(Optional.of(createCategory("Новая категория")));
        when(itemRepository.save(any(Item.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ItemResponse result = itemService.updateItem(itemId, request);

        assertNotNull(result);
        assertEquals("Новое название", existingItem.getName());
        assertEquals("Новая категория", existingItem.getCategory().getName());
        assertEquals(10, existingItem.getMinStock());
        assertThat(existingItem.getPrice().compareTo(BigDecimal.valueOf(120.00))).isEqualTo(0);
        assertThat(existingItem.getCost().compareTo(BigDecimal.valueOf(85.00))).isEqualTo(0);

        verify(itemRepository, times(1)).findById(itemId);
        verify(itemRepository, times(1)).save(existingItem);
    }

    /**
     * Обновление товара с изменением цены и себестоимости.
     */
    @Test
    void updateItemPriceAndCostChange() {
        Long itemId = 5L;
        Item existingItem = new Item();
        existingItem.setId(itemId);
        existingItem.setName("Товар");
        existingItem.setCategory(createCategory("Категория"));
        existingItem.setMinStock(5);
        existingItem.setActive(true);
        existingItem.setPrice(BigDecimal.valueOf(100.00));
        existingItem.setCost(BigDecimal.valueOf(50.00));

        UpdateItemRequest request = new UpdateItemRequest("Обновленный товар", "Обновленная категория",
                10, BigDecimal.valueOf(150.00), BigDecimal.valueOf(80.00));

        when(itemRepository.findById(itemId)).thenReturn(Optional.of(existingItem));
        when(categoryRepository.findByNameIgnoreCase("Обновленная категория"))
                .thenReturn(Optional.of(createCategory("Обновленная категория")));
        when(itemRepository.save(any(Item.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ItemResponse result = itemService.updateItem(itemId, request);

        assertNotNull(result);
        assertThat(existingItem.getPrice().compareTo(BigDecimal.valueOf(150.00))).isEqualTo(0);
        assertThat(existingItem.getCost().compareTo(BigDecimal.valueOf(80.00))).isEqualTo(0);
        assertEquals("Обновленный товар", existingItem.getName());
        assertEquals("Обновленная категория", existingItem.getCategory().getName());
    }

    /**
     * Обновление товара с price и cost = 0.
     */
    @Test
    void updateItemPriceAndCostZero() {
        Long itemId = 7L;
        Item existingItem = new Item();
        existingItem.setId(itemId);
        existingItem.setName("Товар");
        existingItem.setCategory(createCategory("Категория"));
        existingItem.setMinStock(5);
        existingItem.setActive(true);
        existingItem.setPrice(BigDecimal.valueOf(100.00));
        existingItem.setCost(BigDecimal.valueOf(50.00));

        UpdateItemRequest request = new UpdateItemRequest("Товар с нулевой ценой",
                "Категория", 5, BigDecimal.ZERO, BigDecimal.ZERO);

        when(itemRepository.findById(itemId)).thenReturn(Optional.of(existingItem));
        when(categoryRepository.findByNameIgnoreCase("Категория"))
                .thenReturn(Optional.of(createCategory("Категория")));
        when(itemRepository.save(any(Item.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ItemResponse result = itemService.updateItem(itemId, request);

        assertNotNull(result);
        assertThat(existingItem.getPrice().compareTo(BigDecimal.ZERO)).isEqualTo(0);
        assertThat(existingItem.getCost().compareTo(BigDecimal.ZERO)).isEqualTo(0);
    }

    /**
     * Обновление товара с дефолтными значениями price и cost.
     */
    @Test
    void updateItemDefaultPriceAndCost() {
        Long itemId = 9L;
        Item existingItem = new Item();
        existingItem.setId(itemId);
        existingItem.setName("Товар");
        existingItem.setCategory(createCategory("Категория"));
        existingItem.setMinStock(5);
        existingItem.setActive(true);
        existingItem.setPrice(BigDecimal.valueOf(100.00));
        existingItem.setCost(BigDecimal.valueOf(50.00));

        UpdateItemRequest request = new UpdateItemRequest("Товар", "Категория",
                5, BigDecimal.valueOf(100.00), BigDecimal.valueOf(50.00));

        when(itemRepository.findById(itemId)).thenReturn(Optional.of(existingItem));
        when(categoryRepository.findByNameIgnoreCase("Категория"))
                .thenReturn(Optional.of(createCategory("Категория")));
        when(itemRepository.save(any(Item.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ItemResponse result = itemService.updateItem(itemId, request);

        assertNotNull(result);
        assertThat(existingItem.getPrice().compareTo(BigDecimal.valueOf(100.00))).isEqualTo(0);
        assertThat(existingItem.getCost().compareTo(BigDecimal.valueOf(50.00))).isEqualTo(0);
    }

    /**
     * Обновление несуществующего товара выбрасывает EntityNotFoundException.
     */
    @Test
    void updateItemItemNotFoundThrowsException() {
        Long itemId = 3L;
        UpdateItemRequest request = new UpdateItemRequest("Тест", "Тест Категория",
                10, BigDecimal.valueOf(50.00), BigDecimal.valueOf(30.00));

        when(itemRepository.findById(itemId)).thenReturn(Optional.empty());

        EntityNotFoundException exception = assertThrows(EntityNotFoundException.class, () -> {
            itemService.updateItem(itemId, request);
        });

        assertTrue(exception.getMessage().contains("not found"));
        verify(itemRepository, never()).save(any(Item.class));
    }

    /**
     * Обновление неактивного товара выбрасывает EntityNotFoundException.
     */
    @Test
    void updateItemItemInactiveThrowsException() {
        Long itemId = 3L;
        Item inactiveItem = new Item();
        inactiveItem.setId(itemId);
        inactiveItem.setActive(false);
        inactiveItem.setPrice(BigDecimal.valueOf(50.00));
        inactiveItem.setCost(BigDecimal.valueOf(30.00));

        UpdateItemRequest request = new UpdateItemRequest("Тест", "Тест Категория",
                10, BigDecimal.valueOf(50.00), BigDecimal.valueOf(30.00));

        when(itemRepository.findById(itemId)).thenReturn(Optional.of(inactiveItem));

        EntityNotFoundException exception = assertThrows(EntityNotFoundException.class, () -> {
            itemService.updateItem(itemId, request);
        });

        assertTrue(exception.getMessage().contains("not found"));
        verify(itemRepository, never()).save(any(Item.class));
    }

    /**
     * ADMIN может успешно деактивировать существующий товар.
     */
    @Test
    void successSoftDeleteItem() {
        Long itemId = 1L;
        Item item = new Item();
        item.setId(itemId);
        item.setActive(true);

        when(itemRepository.findById(itemId)).thenReturn(Optional.of(item));

        itemService.softDeleteItem(itemId);

        verify(itemRepository).findById(itemId);
        assertFalse(item.isActive());
        verifyNoMoreInteractions(stockRepository);
    }

    /**
     * Деактивация несуществующего товара выбрасывает EntityNotFoundException.
     */
    @Test
    void softDeleteNotExistentItem() {
        Long itemId = 999L;

        when(itemRepository.findById(itemId)).thenReturn(Optional.empty());

        EntityNotFoundException exception = assertThrows(EntityNotFoundException.class, () -> {
            itemService.softDeleteItem(itemId);
        });

        assertEquals("Item with id 999 not found", exception.getMessage());
        verify(itemRepository).findById(itemId);
        verifyNoInteractions(stockRepository);
    }

    /**
     * Деактивация уже деактивированного товара выбрасывает EntityNotFoundException.
     */
    @Test
    void softDeleteAlreadyInactiveItem() {
        Long itemId = 1L;
        Item item = new Item();
        item.setId(itemId);
        item.setActive(false);

        when(itemRepository.findById(itemId)).thenReturn(Optional.of(item));

        EntityNotFoundException exception = assertThrows(EntityNotFoundException.class, () -> {
            itemService.softDeleteItem(itemId);
        });

        assertEquals("Item with id=1 is already deactivated", exception.getMessage());
        verify(itemRepository).findById(itemId);
        verifyNoInteractions(stockRepository);
    }

    /**
     * Возвращает детали товара, если он существует и активен.
     */
    @Test
    void shouldReturnItemWhenItemExistsAndActive() {
        ItemDetailsProjection projection = new ItemDetailsProjection(
                1L, "WH-001", "Ноутбук Dell XPS 15", "Электроника", 5, 23,
                BigDecimal.valueOf(1500.00), BigDecimal.valueOf(1000.00),
                true, LocalDateTime.now(), LocalDateTime.now()
        );

        when(itemRepository.findWithStock(1L)).thenReturn(Optional.of(projection));
        when(availabilityService.getTotalReserved(1L)).thenReturn(3L);
        when(availabilityService.getTotalAvailable(1L)).thenReturn(20L);
        when(stockRepository.findAllByItemIdWithWarehouse(1L)).thenReturn(List.of());

        ItemDetailsResponse result = itemService.getItem(1L);

        assertEquals(projection.id(), result.getId());
        assertEquals(projection.sku(), result.getSku());
        assertEquals(projection.name(), result.getName());
        assertEquals(projection.category(), result.getCategory());
        assertEquals(projection.minStock(), result.getMinStock());
        assertEquals(projection.currentStock(), result.getCurrentStock());
        assertEquals(projection.price(), result.getPrice());
        assertEquals(projection.cost(), result.getCost());
        assertEquals(projection.active(), result.isActive());
        assertEquals(projection.createdAt(), result.getCreatedAt());
        assertEquals(projection.updatedAt(), result.getUpdatedAt());

        assertEquals(20, result.getAvailable());
        assertEquals(3, result.getReserved());

        verify(itemRepository).findWithStock(1L);
        verify(availabilityService).getTotalReserved(1L);
        verify(availabilityService).getTotalAvailable(1L);
        verify(stockRepository).findAllByItemIdWithWarehouse(1L);
    }

    /**
     * Получение несуществующего товара выбрасывает EntityNotFoundException.
     */
    @Test
    void shouldThrowEntityNotFoundExceptionWhenItemNotFound() {
        when(itemRepository.findWithStock(1L)).thenReturn(Optional.empty());

        EntityNotFoundException exception = assertThrows(EntityNotFoundException.class, () -> {
            itemService.getItem(1L);
        });

        assertEquals("Товар не найден", exception.getMessage());
    }

    /**
     * Получение неактивного товара выбрасывает EntityNotFoundException.
     */
    @Test
    void shouldThrowEntityNotFoundExceptionWhenItemNotActive() {
        ItemDetailsProjection response = new ItemDetailsProjection(
                1L, "WH-001", "Ноутбук Dell XPS 15", "Электроника", 5, 23,
                BigDecimal.valueOf(1500.00), BigDecimal.valueOf(1000.00),
                false, LocalDateTime.now(), LocalDateTime.now()
        );

        when(itemRepository.findWithStock(1L)).thenReturn(Optional.of(response));

        EntityNotFoundException exception = assertThrows(EntityNotFoundException.class, () -> {
            itemService.getItem(1L);
        });

        assertEquals("Товар неактивен", exception.getMessage());
    }

    /**
     * getItems с дефолтными параметрами возвращает страницу товаров.
     */
    @Test
    void getItemsDefaultParamsReturnsPage() {
        Item item = new Item();
        item.setId(1L);
        item.setSku("SKU-1");
        item.setName("Ноутбук");
        item.setCategory(createCategory("Электроника"));
        item.setMinStock(5);
        item.setActive(true);

        PageRequest pageable = PageRequest.of(0, 20);
        when(itemRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(item), pageable, 1));

        PageResponse<ItemResponse> result = itemService.getItems("name", "asc",
                null, null, 0, 20);

        assertThat(result.content()).hasSize(1);
        assertThat(result.totalElements()).isEqualTo(1);
        assertThat(result.page()).isEqualTo(0);
        assertThat(result.size()).isEqualTo(20);
    }

    /**
     * getItems с сортировкой по SKU по убыванию передает правильный Pageable.
     */
    @Test
    void getItemsSortBySkuDescPassesCorrectPageable() {
        when(itemRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        itemService.getItems("sku", "desc", null, null, 0, 20);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(itemRepository).findAll(any(Specification.class), pageableCaptor.capture());

        Pageable pageable = pageableCaptor.getValue();
        Sort.Order order = pageable.getSort().getOrderFor("sku");

        assertThat(order).isNotNull();
        assertThat(order.getDirection()).isEqualTo(Sort.Direction.DESC);
    }

    /**
     * getItems с неизвестным полем сортировки падает на name.
     */
    @Test
    void getItemsUnknownSortFieldFallsBackToName() {
        when(itemRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        itemService.getItems("invalid", "asc", null, null, 0, 20);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(itemRepository).findAll(any(Specification.class), pageableCaptor.capture());

        assertThat(pageableCaptor.getValue().getSort().getOrderFor("name")).isNotNull();
        assertThat(pageableCaptor.getValue().getSort().getOrderFor("sku")).isNull();
    }

    /**
     * getItems с пагинацией передает правильный номер страницы и размер.
     */
    @Test
    void getItemsPaginationPassesCorrectPageNumber() {
        when(itemRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        itemService.getItems("name", "asc", null, null, 2, 10);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(itemRepository).findAll(any(Specification.class), pageableCaptor.capture());

        assertThat(pageableCaptor.getValue().getPageNumber()).isEqualTo(2);
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(10);
    }

    /**
     * price и cost отображаются корректно в карточке товара.
     */
    @Test
    void priceAndCostDisplayedInItemDetails() {
        ItemDetailsProjection response = new ItemDetailsProjection(
                1L, "WH-001", "Ноутбук Dell XPS 15", "Электроника", 5, 23,
                BigDecimal.valueOf(1500.99), BigDecimal.valueOf(1000.49),
                true, LocalDateTime.now(), LocalDateTime.now()
        );

        assertThat(response.price()).isEqualTo(BigDecimal.valueOf(1500.99));
        assertThat(response.cost()).isEqualTo(BigDecimal.valueOf(1000.49));
    }

    /**
     * price и cost не могут быть отрицательными (валидация).
     */
    @Test
    void priceAndCostCannotBeNegative() {
        Item item = new Item();
        item.setSku("SKU-TEST");
        item.setName("Тест");
        item.setCategory(createCategory("Тест"));
        item.setMinStock(0);
        item.setActive(true);

        item.setPrice(BigDecimal.valueOf(100.00));
        item.setCost(BigDecimal.valueOf(50.00));

        assertThat(item.getPrice().compareTo(BigDecimal.valueOf(100.00))).isEqualTo(0);
        assertThat(item.getCost().compareTo(BigDecimal.valueOf(50.00))).isEqualTo(0);

        item.setPrice(BigDecimal.ZERO);
        item.setCost(BigDecimal.ZERO);

        assertThat(item.getPrice().compareTo(BigDecimal.ZERO)).isEqualTo(0);
        assertThat(item.getCost().compareTo(BigDecimal.ZERO)).isEqualTo(0);
    }

    /**
     * price и cost округляются до 2 знаков после запятой.
     */
    @Test
    void priceAndCostRoundingWorksCorrectly() {
        Item item = new Item();
        item.setSku("SKU-ROUNDING");
        item.setName("Тест");
        item.setCategory(createCategory("Тест"));
        item.setMinStock(0);
        item.setActive(true);

        item.setPrice(itemService.confirmPrice(BigDecimal.valueOf(1500.999)));
        item.setCost(itemService.confirmCost(BigDecimal.valueOf(1000.444)));

        assertThat(item.getPrice().compareTo(BigDecimal.valueOf(1501.00))).isEqualTo(0);
        assertThat(item.getCost().compareTo(BigDecimal.valueOf(1000.44))).isEqualTo(0);

        item.setPrice(itemService.confirmPrice(BigDecimal.valueOf(100.005)));
        item.setCost(itemService.confirmCost(BigDecimal.valueOf(50.005)));

        assertThat(item.getPrice().compareTo(BigDecimal.valueOf(100.01))).isEqualTo(0);
        assertThat(item.getCost().compareTo(BigDecimal.valueOf(50.01))).isEqualTo(0);
    }

    private Category createCategory(String name) {
        return Category.builder()
                .name(name)
                .build();
    }
}
