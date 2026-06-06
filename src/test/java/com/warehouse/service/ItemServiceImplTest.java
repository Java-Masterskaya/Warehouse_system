package com.warehouse.service;

import com.warehouse.dto.request.UpdateItemRequest;
import com.warehouse.dto.response.ItemResponse;
import com.warehouse.entity.Item;
import com.warehouse.mapper.ItemMapper;
import com.warehouse.repository.ItemRepository;
import com.warehouse.repository.StockRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

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
        Item existingItem = new Item();
        existingItem.setId(3L);
        existingItem.setName("Старое название");
        existingItem.setCategory("Старая категория");
        existingItem.setMinStock(5);
        existingItem.setActive(true); // Товар активен

        UpdateItemRequest request = new UpdateItemRequest("Новое название",
                "Новая категория", 10);

        // 2. Настройка моков
        when(itemRepository.findById(3L)).thenReturn(Optional.of(existingItem));
        when(itemRepository.save(any(Item.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // 3. Выполнение
        ItemResponse result = itemService.updateItem(3L, request);

        // 4. Проверки
        assertNotNull(result);
        assertEquals("Новое название", existingItem.getName());
        assertEquals("Новая категория", existingItem.getCategory());
        assertEquals(10, existingItem.getMinStock());

        verify(itemRepository, times(1)).findById(3L);
        verify(itemRepository, times(1)).save(existingItem);
    }

    // Товар не найден (должен вернуть 404)
    @Test
    void updateItemItemNotFoundThrowsException() {
        // 1. Подготовка данных

        UpdateItemRequest request = new UpdateItemRequest("Тест",
                "Тест Категория", 10);

        // 2. Настройка моков
        when(itemRepository.findById(3L)).thenReturn(Optional.empty());

        // 3. Выполнение и проверка исключения
        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> {
            itemService.updateItem(3L, request);
        });

        // 4. Проверка деталей исключения
        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
        assertTrue(exception.getReason().contains("не найден"));

        // Проверяем, что сохранение не вызывалось
        verify(itemRepository, never()).save(any(Item.class));
    }

    // Товар неактивен (должен вернуть 404)
    @Test
    void updateItemItemInactiveThrowsException() {
        // 1. Подготовка данных
        Item inactiveItem = new Item();
        inactiveItem.setId(3L);
        inactiveItem.setActive(false);

        UpdateItemRequest request = new UpdateItemRequest("Тест",
                "Тест Категория", 10);

        // 2. Настройка моков
        when(itemRepository.findById(3L)).thenReturn(Optional.of(inactiveItem));

        // 3. Выполнение и проверка исключения
        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> {
            itemService.updateItem(3L, request);
        });

        // 4. Проверка деталей исключения
        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
        assertTrue(exception.getReason().contains("неактивен"));

        // Проверяем, что сохранение не вызывалось
        verify(itemRepository, never()).save(any(Item.class));
    }
}
