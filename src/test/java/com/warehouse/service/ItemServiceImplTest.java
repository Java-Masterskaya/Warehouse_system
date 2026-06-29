package com.warehouse.service;

import com.warehouse.dto.request.item.CreateItemRequest;
import com.warehouse.dto.request.item.UpdateItemRequest;
import com.warehouse.dto.response.PageResponse;
import com.warehouse.dto.response.item.ItemResponse;
import com.warehouse.dto.response.item.ItemDetailsResponse;
import com.warehouse.entity.Item;
import com.warehouse.entity.Stock;
import com.warehouse.exception.DuplicateSkuException;
import com.warehouse.exception.EntityNotFoundException;
import com.warehouse.mapper.ItemMapper;
import com.warehouse.repository.ItemRepository;
import com.warehouse.repository.StockRepository;
import com.warehouse.service.item.ItemService;
import com.warehouse.service.item.ItemServiceImpl;
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
import java.util.Collections;
import java.util.HashSet;
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

    private final ItemMapper itemMapper = Mappers.getMapper(ItemMapper.class);

    private ItemService itemService;

    @BeforeEach
    void setUp() {
        itemService = new ItemServiceImpl(itemRepository, stockRepository, itemMapper);
    }

    /**
     * ADMIN может успешно создать товар,
     * возвращает ItemResponse с данными и сохраняет в репозиторий.
     */
    @Test
    void createItemSuccess() {
        CreateItemRequest request = new CreateItemRequest("SKU-001", "Ноутбук", "Электроника", 5, BigDecimal.valueOf(100.50), BigDecimal.valueOf(75.25));

        Item item = new Item();
        item.setId(1L);
        item.setSku("SKU-001");
        item.setCreatedAt(LocalDateTime.now());
        item.setPrice(BigDecimal.valueOf(100.50));
        item.setCost(BigDecimal.valueOf(75.25));

        when(itemRepository.existsBySku("SKU-001")).thenReturn(false);
        when(itemRepository.save(any(Item.class))).thenAnswer(invocation -> {
            Item savedItem = invocation.getArgument(0);
            savedItem.setId(1L);
            savedItem.setCreatedAt(LocalDateTime.now());
            return savedItem;
        });
        when(stockRepository.save(any(Stock.class))).thenAnswer(invocation -> invocation.getArgument(0));

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
    }

    /**
     * Попытка создать товар с дублирующимся SKU выбрасывает DuplicateSkuException.
     */
    @Test
    void createItemDuplicateSkuThrowsDuplicateSkuException() {
        CreateItemRequest request = new CreateItemRequest("SKU-001", "Ноутбук", "Электроника", 5, BigDecimal.valueOf(100.50), BigDecimal.valueOf(75.25));

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
        existingItem.setCategory("Старая категория");
        existingItem.setMinStock(5);
        existingItem.setActive(true);
        existingItem.setPrice(BigDecimal.valueOf(100.50));
        existingItem.setCost(BigDecimal.valueOf(75.25));

        UpdateItemRequest request = new UpdateItemRequest("Новое название", "Новая категория", 10, BigDecimal.valueOf(120.00), BigDecimal.valueOf(85.00));

        when(itemRepository.findById(itemId)).thenReturn(Optional.of(existingItem));
        when(itemRepository.save(any(Item.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ItemResponse result = itemService.updateItem(itemId, request);

        assertNotNull(result);
        assertEquals("Новое название", existingItem.getName());
        assertEquals("Новая категория", existingItem.getCategory());
        assertEquals(10, existingItem.getMinStock());

        verify(itemRepository, times(1)).findById(itemId);
        verify(itemRepository, times(1)).save(existingItem);
    }

    /**
     * Обновление несуществующего товара выбрасывает EntityNotFoundException.
     */
    @Test
    void updateItemItemNotFoundThrowsException() {
        Long itemId = 3L;
        UpdateItemRequest request = new UpdateItemRequest("Тест", "Тест Категория", 10, BigDecimal.valueOf(50.00), BigDecimal.valueOf(30.00));

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

        UpdateItemRequest request = new UpdateItemRequest("Тест", "Тест Категория", 10, BigDecimal.valueOf(50.00), BigDecimal.valueOf(30.00));

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
        ItemDetailsResponse response = new ItemDetailsResponse(
                1L, "WH-001", "Ноутбук Dell XPS 15", "Электроника", 5, 23,
                BigDecimal.valueOf(1500.00), BigDecimal.valueOf(1000.00),
                true, LocalDateTime.now(), LocalDateTime.now()
        );

        when(itemRepository.findWithStock(1L)).thenReturn(Optional.of(response));

        ItemDetailsResponse result = itemService.getItem(1L);

        assertEquals(response, result);
        verify(itemRepository).findWithStock(1L);
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
        ItemDetailsResponse response = new ItemDetailsResponse(
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
        item.setCategory("Электроника");
        item.setMinStock(5);
        item.setActive(true);

        PageRequest pageable = PageRequest.of(0, 20);
        when(itemRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(item), pageable, 1));

        PageResponse<ItemResponse> result = itemService.getItems("name", "asc", null, null, 0, 20);

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
     * getCategories возвращает списокdistinct категорий.
     */
    @Test
    void getCategoriesShouldReturnDistinctList() {
        when(itemRepository.findDistinctCategories())
                .thenReturn(List.of("Электроника", "Мебель", "Инструменты"));

        List<String> categories = itemService.getCategories();

        assertNotNull(categories);
        assertEquals(3, categories.size());
        assertTrue(categories.contains("Электроника"));
        assertTrue(categories.contains("Мебель"));
        assertTrue(categories.contains("Инструменты"));
        assertEquals(3, new HashSet<>(categories).size());

        verify(itemRepository, times(1)).findDistinctCategories();
    }

    /**
     * getCategories возвращает пустой список, если нет активных товаров.
     */
    @Test
    void getCategoriesShouldReturnEmptyListWhenNoActiveItems() {
        when(itemRepository.findDistinctCategories())
                .thenReturn(Collections.emptyList());

        List<String> categories = itemService.getCategories();

        assertNotNull(categories);
        assertTrue(categories.isEmpty());

        verify(itemRepository, times(1)).findDistinctCategories();
    }
}