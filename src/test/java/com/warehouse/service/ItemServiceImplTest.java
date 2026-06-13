package com.warehouse.service;

import com.warehouse.dto.request.item.UpdateItemRequest;
import com.warehouse.dto.response.item.ItemResponse;
import com.warehouse.dto.response.item.ItemDetailsResponse;
import com.warehouse.entity.Item;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class ItemServiceImplTest {

    @Mock
    private ItemRepository itemRepository;

    @Mock
    private StockRepository stockRepository;

    private ItemService itemService;

    @BeforeEach
    void setUp() {
        // Используем реальную реализацию MapStruct
        ItemMapper itemMapper = Mappers.getMapper(ItemMapper.class);
        // Внедряем её в сервис вручную
        itemService = new ItemServiceImpl(itemRepository, stockRepository, itemMapper);
    }

    // Успешное обновление активного товара
    @Test
    void updateItemSuccess() {
        // 1. Подготовка данных
        Long itemId = 3L;
        Item existingItem = new Item();
        existingItem.setId(itemId);
        existingItem.setName("Старое название");
        existingItem.setCategory("Старая категория");
        existingItem.setMinStock(5);
        existingItem.setActive(true); // Товар активен

        UpdateItemRequest request = new UpdateItemRequest("Новое название",
                "Новая категория", 10);

        // 2. Настройка моков
        when(itemRepository.findById(itemId)).thenReturn(Optional.of(existingItem));
        when(itemRepository.save(any(Item.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // 3. Выполнение
        ItemResponse result = itemService.updateItem(itemId, request);

        // 4. Проверки
        assertNotNull(result);
        assertEquals("Новое название", existingItem.getName());
        assertEquals("Новая категория", existingItem.getCategory());
        assertEquals(10, existingItem.getMinStock());

        verify(itemRepository, times(1)).findById(itemId);
        verify(itemRepository, times(1)).save(existingItem);
    }

    // Товар не найден (должен вернуть 404)
    @Test
    void updateItemItemNotFoundThrowsException() {
        // 1. Подготовка данных
        Long itemId = 3L;

        UpdateItemRequest request = new UpdateItemRequest("Тест",
                "Тест Категория", 10);

        // 2. Настройка моков
        when(itemRepository.findById(itemId)).thenReturn(Optional.empty());

        // 3. Выполнение и проверка исключения
        EntityNotFoundException exception = assertThrows(EntityNotFoundException.class, () -> {
            itemService.updateItem(itemId, request);
        });

        // 4. Проверка деталей исключения
        assertTrue(exception.getMessage().contains("not found"));

        // Проверяем, что сохранение не вызывалось
        verify(itemRepository, never()).save(any(Item.class));
    }

    // Товар неактивен (должен вернуть 404)
    @Test
    void updateItemItemInactiveThrowsException() {
        // 1. Подготовка данных
        Long itemId = 3L;
        Item inactiveItem = new Item();
        inactiveItem.setId(itemId);
        inactiveItem.setActive(false);

        UpdateItemRequest request = new UpdateItemRequest("Тест",
                "Тест Категория", 10);

        // 2. Настройка моков
        when(itemRepository.findById(itemId)).thenReturn(Optional.of(inactiveItem));

        // 3. Выполнение и проверка исключения
        EntityNotFoundException exception = assertThrows(EntityNotFoundException.class, () -> {
            itemService.updateItem(itemId, request);
        });

        // 4. Проверка деталей исключения
        assertTrue(exception.getMessage().contains("not found"));

        // Проверяем, что сохранение не вызывалось
        verify(itemRepository, never()).save(any(Item.class));
    }

    // Скрытие(деактивация) существующего товара
    @Test
    void successSoftDeleteItem() {
        Long itemId = 1L;

        Item item = new Item();
        item.setId(itemId);
        item.setActive(true);

        when(itemRepository.findById(itemId))
                .thenReturn(Optional.of(item));

        itemService.softDeleteItem(itemId);

        verify(itemRepository).findById(itemId);

        assertFalse(item.isActive());

        verifyNoMoreInteractions(stockRepository);
    }

    // Скрытие(деактивация) несуществующего товара
    @Test
    void softDeleteNotExistentItem() {
        Long itemId = 999L;

        when(itemRepository.findById(itemId))
                .thenReturn(Optional.empty());

        EntityNotFoundException exception =
                assertThrows(
                        EntityNotFoundException.class,
                        () -> itemService.softDeleteItem(itemId)
                );

        assertEquals(
                "Item with id 999 not found",
                exception.getMessage()
        );

        verify(itemRepository).findById(itemId);
        verifyNoInteractions(stockRepository);
    }

    @Test
    void shouldReturnItemWhenItemExistsAndActive() {
        // 1. Подготовка данных
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

        // 2. Настройка моков
        when(itemRepository.findWithStock(1L)).thenReturn(Optional.of(response));

        // 3. Выполнение
        ItemDetailsResponse result = itemService.getItem(1L);

        // 4. Проверка
        assertEquals(response, result);
        verify(itemRepository).findWithStock(1L);
    }

    @Test
    void shouldThrowEntityNotFoundExceptionWhenItemNotFound() {
        // 2. Настройка моков
        when(itemRepository.findWithStock(1L)).thenReturn(Optional.empty());

        // 3. Выполнение
        EntityNotFoundException exception = assertThrows(EntityNotFoundException.class, () -> itemService.getItem(1L));

        // 4. Проверка
        assertEquals("Товар не найден", exception.getMessage());
    }

    @Test
    void shouldThrowEntityNotFoundExceptionWhenItemNotActive() {
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

        // 2. Настройка моков
        when(itemRepository.findWithStock(1L)).thenReturn(Optional.of(response));

        // 3. Выполнение
        EntityNotFoundException exception = assertThrows(EntityNotFoundException.class, () -> itemService.getItem(1L));

        // 4. Проверка
        assertEquals("Товар неактивен", exception.getMessage());
    }

    // Скрытие(деактивация) уже деактивированного товара
    @Test
    void softDeleteAlreadyInactiveItem() {
        Long itemId = 1L;

        Item item = new Item();
        item.setId(itemId);
        item.setActive(false);

        when(itemRepository.findById(itemId))
                .thenReturn(Optional.of(item));

        EntityNotFoundException exception =
                assertThrows(
                        EntityNotFoundException.class,
                        () -> itemService.softDeleteItem(itemId)
                );

        assertEquals(
                "Item with id=1 is already deactivated",
                exception.getMessage()
        );

        verify(itemRepository).findById(itemId);
        verifyNoInteractions(stockRepository);
    }

}


