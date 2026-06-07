package com.warehouse.service;

import com.warehouse.dto.request.UpdateItemRequest;
import com.warehouse.dto.response.ItemResponse;
import com.warehouse.entity.Item;
import com.warehouse.exception.EntityNotFoundException;
import com.warehouse.mapper.ItemMapper;
import com.warehouse.repository.ItemRepository;
import com.warehouse.repository.StockRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
}
