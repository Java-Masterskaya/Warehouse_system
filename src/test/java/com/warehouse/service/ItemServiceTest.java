package com.warehouse.service;

import com.warehouse.dto.request.item.CreateItemRequest;
import com.warehouse.dto.request.item.UpdateItemRequest;
import com.warehouse.dto.response.PageResponse;
import com.warehouse.dto.response.item.ItemDetailsResponse;
import com.warehouse.dto.response.item.ItemResponse;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Юнит-тесты для ItemServiceImpl: CRUD операции с товарами.
 * Проверяют: создание, чтение, обновление, удаление, фильтрацию, сортировку.
 */
@ExtendWith(MockitoExtension.class)
class ItemServiceTest {

    @Mock
    private ItemRepository itemRepository;

    @Mock
    private StockRepository stockRepository;

    private ItemService itemService;

    @BeforeEach
    void setUp() {
        ItemMapper itemMapper = Mappers.getMapper(ItemMapper.class);
        itemService = new ItemServiceImpl(itemRepository, stockRepository, itemMapper);
    }

    /**
     * Успешное создание товара: репозитории вызываются корректно.
     */
    @Test
    void createItemSuccess() {
        CreateItemRequest request = new CreateItemRequest("SKU-001", "Ноутбук", "Электроника", 5);

        Item item = new Item();
        item.setId(1L);
        item.setSku("SKU-001");

        ItemResponse expected = new ItemResponse(1L, "SKU-001", "Ноутбук", "Электроника", 5, true, LocalDateTime.now());

        when(itemRepository.existsBySku("SKU-001")).thenReturn(false);
        // Используем реальный itemMapper для преобразования
        when(itemRepository.save(any(Item.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(stockRepository.save(any(Stock.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ItemResponse result = itemService.createItem(request);

        assertThat(result).isNotNull();
        assertThat(result.sku()).isEqualTo("SKU-001");
        verify(itemRepository).save(any(Item.class));
        verify(stockRepository).save(any(Stock.class));
    }

    /**
     * Попытка создания товара с дублирующимся SKU выбрасывает DuplicateSkuException.
     */
    @Test
    void createItemDuplicateSkuThrowsDuplicateSkuException() {
        CreateItemRequest request = new CreateItemRequest("SKU-001", "Ноутбук", "Электроника", 5);

        when(itemRepository.existsBySku("SKU-001")).thenReturn(true);

        assertThatThrownBy(() -> itemService.createItem(request))
                .isInstanceOf(DuplicateSkuException.class)
                .hasMessageContaining("SKU-001");

        verify(itemRepository, never()).save(any());
        verify(stockRepository, never()).save(any());
    }

    /**
     * Получение списка товаров с параметрами по умолчанию возвращает Page.
     */
    @Test
    void getItemsDefaultParamsReturnsPage() {
        ItemResponse response = new ItemResponse(1L, "SKU-1", "Ноутбук", "Электроника", 5, true, LocalDateTime.now());
        Item item = Item.builder()
                .id(1L)
                .sku("SKU-1")
                .name("Ноутбук")
                .category("Электроника")
                .minStock(5)
                .active(true)
                .createdAt(LocalDateTime.now())
                .build();

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
     * Сортировка по SKU по убыванию передаёт корректный Pageable.
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
     * Неизвестное поле сортировки fallback к name.
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
     * Пагинация передаёт корректный номер страницы и размер.
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
     * Успешное обновление товара: данные меняются корректно.
     */
    @Test
    void updateItemSuccess() {
        Long itemId = 3L;
        Item existingItem = Item.builder()
                .id(itemId)
                .name("Старое название")
                .category("Старая категория")
                .minStock(5)
                .active(true)
                .build();

        UpdateItemRequest request = new UpdateItemRequest("Новое название",
                "Новая категория", 10);

        when(itemRepository.findById(itemId)).thenReturn(Optional.of(existingItem));
        when(itemRepository.save(any(Item.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ItemResponse result = itemService.updateItem(itemId, request);

        assertThat(result).isNotNull();
        assertThat(result.name()).isEqualTo("Новое название");
        assertThat(result.category()).isEqualTo("Новая категория");
        assertThat(result.minStock()).isEqualTo(10);

        verify(itemRepository).findById(itemId);
        verify(itemRepository).save(existingItem);
    }

    /**
     * Обновление несуществующего товара выбрасывает EntityNotFoundException.
     */
    @Test
    void updateItemNotFoundThrowsEntityNotFoundException() {
        Long itemId = 3L;
        UpdateItemRequest request = new UpdateItemRequest("Тест", "Тест Категория", 10);

        when(itemRepository.findById(itemId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> itemService.updateItem(itemId, request))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("not found");

        verify(itemRepository, never()).save(any());
    }

    /**
     * Обновление неактивного товара выбрасывает EntityNotFoundException.
     */
    @Test
    void updateItemInactiveThrowsEntityNotFoundException() {
        Long itemId = 3L;
        Item inactiveItem = Item.builder()
                .id(itemId)
                .active(false)
                .build();

        UpdateItemRequest request = new UpdateItemRequest("Тест", "Тест Категория", 10);

        when(itemRepository.findById(itemId)).thenReturn(Optional.of(inactiveItem));

        assertThatThrownBy(() -> itemService.updateItem(itemId, request))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("not found");

        verify(itemRepository, never()).save(any());
    }

    /**
     * Успешное получение детальной информации о товаре.
     */
    @Test
    void getItemSuccess() {
        ItemDetailsResponse response = new ItemDetailsResponse(
                1L,
                "WH-001",
                "Ноутбук Dell XPS 15",
                "Электроника",
                5,
                23,
                true,
                LocalDateTime.now(),
                LocalDateTime.now()
        );

        when(itemRepository.findWithStock(1L)).thenReturn(Optional.of(response));

        ItemDetailsResponse result = itemService.getItem(1L);

        assertThat(result).isEqualTo(response);
        verify(itemRepository).findWithStock(1L);
    }

    /**
     * Получение несуществующего товара выбрасывает EntityNotFoundException.
     */
    @Test
    void getItemNotFoundThrowsEntityNotFoundException() {
        when(itemRepository.findWithStock(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> itemService.getItem(1L))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("Товар не найден");
    }

    /**
     * Получение неактивного товара выбрасывает EntityNotFoundException.
     */
    @Test
    void getItemInactiveThrowsEntityNotFoundException() {
        ItemDetailsResponse response = new ItemDetailsResponse(
                1L,
                "WH-001",
                "Ноутбук Dell XPS 15",
                "Электроника",
                5,
                23,
                false,
                LocalDateTime.now(),
                LocalDateTime.now()
        );

        when(itemRepository.findWithStock(1L)).thenReturn(Optional.of(response));

        assertThatThrownBy(() -> itemService.getItem(1L))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("Товар неактивен");
    }

    /**
     * Успешное удаление товара (soft delete): active = false.
     */
    @Test
    void softDeleteItemSuccess() {
        Long itemId = 1L;
        Item item = Item.builder()
                .id(itemId)
                .active(true)
                .build();

        when(itemRepository.findById(itemId)).thenReturn(Optional.of(item));

        itemService.softDeleteItem(itemId);

        verify(itemRepository).findById(itemId);
        assertThat(item.isActive()).isFalse();
    }

    /**
     * Удаление несуществующего товара выбрасывает EntityNotFoundException.
     */
    @Test
    void softDeleteItemNotFoundThrowsEntityNotFoundException() {
        Long itemId = 999L;

        when(itemRepository.findById(itemId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> itemService.softDeleteItem(itemId))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("not found");

        verify(itemRepository).findById(itemId);
        verify(itemRepository, never()).save(any());
    }

    /**
     * Удаление уже неактивного товара выбрасывает EntityNotFoundException.
     */
    @Test
    void softDeleteItemAlreadyInactiveThrowsEntityNotFoundException() {
        Long itemId = 1L;
        Item item = Item.builder()
                .id(itemId)
                .active(false)
                .build();

        when(itemRepository.findById(itemId)).thenReturn(Optional.of(item));

        assertThatThrownBy(() -> itemService.softDeleteItem(itemId))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("already deactivated");

        verify(itemRepository).findById(itemId);
        verify(itemRepository, never()).save(any());
    }
}
