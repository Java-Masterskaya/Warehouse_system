package com.warehouse.service;

import com.warehouse.dto.response.ItemDetails;
import com.warehouse.dto.request.UpdateItemRequest;
import com.warehouse.entity.Item;
import com.warehouse.entity.Stock;
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

import static org.junit.jupiter.api.Assertions.*;
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
    void updateItem_Success() {
        // 1. Подготовка данных
        Long itemId = 1L;
        Item existingItem = new Item();
        existingItem.setId(itemId);
        existingItem.setName("Старое название");
        existingItem.setCategory("Старая категория");
        existingItem.setMinStock(5);
        existingItem.setActive(true); // Товар активен

        UpdateItemRequest request = new UpdateItemRequest("Новое название",
                "Новая категория", 10);

        Stock stock = new Stock();
        stock.setItem(existingItem);
        stock.setQuantity(42);

        ItemDetails expectedDetails = new ItemDetails();
        expectedDetails.setCurrentStock(42);

        // 2. Настройка моков
        when(itemRepository.findById(itemId)).thenReturn(Optional.of(existingItem));
        when(itemRepository.save(any(Item.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(stockRepository.findByItemId(itemId)).thenReturn(Optional.of(stock));

        // 3. Выполнение
        ItemDetails result = itemService.updateItem(itemId, request);

        // 4. Проверки
        assertNotNull(result);
        assertEquals(42, result.getCurrentStock());
        assertEquals("Новое название", existingItem.getName());
        assertEquals("Новая категория", existingItem.getCategory());
        assertEquals(10, existingItem.getMinStock());

        verify(itemRepository, times(1)).findById(itemId);
        verify(itemRepository, times(1)).save(existingItem);
        verify(stockRepository, times(1)).findByItemId(itemId);
    }

    // Товар не найден (должен вернуть 404)
    @Test
    void updateItem_ItemNotFound_ThrowsException() {
        // 1. Подготовка данных
        Long itemId = 99L;

        UpdateItemRequest request = new UpdateItemRequest("Тест",
                "Тест Категория", 1);

        // 2. Настройка моков
        when(itemRepository.findById(itemId)).thenReturn(Optional.empty());

        // 3. Выполнение и проверка исключения
        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> {
            itemService.updateItem(itemId, request);
        });

        // 4. Проверка деталей исключения
        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
        assertTrue(exception.getReason().contains("не найден"));

        // Проверяем, что сохранение не вызывалось
        verify(itemRepository, never()).save(any(Item.class));
    }

    // Товар неактивен (должен вернуть 404)
    @Test
    void updateItem_ItemInactive_ThrowsException() {
        // 1. Подготовка данных
        Long itemId = 2L;
        Item inactiveItem = new Item();
        inactiveItem.setId(itemId);
        inactiveItem.setActive(false);

        UpdateItemRequest request = new UpdateItemRequest("Тест",
                "Тест Категория", 1);

        // 2. Настройка моков
        when(itemRepository.findById(itemId)).thenReturn(Optional.of(inactiveItem));

        // 3. Выполнение и проверка исключения
        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> {
            itemService.updateItem(itemId, request);
        });

        // 4. Проверка деталей исключения
        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
        assertTrue(exception.getReason().contains("неактивен"));

        // Проверяем, что сохранение не вызывалось
        verify(itemRepository, never()).save(any(Item.class));
    }

    // Товар активен, но запись об остатках (Stock) отсутствует
    @Test
    void updateItem_StockNotFound_SetsCurrentStockToZero() {
        // 1. Подготовка данных
        Long itemId = 3L;
        Item existingItem = new Item();
        existingItem.setId(itemId);
        existingItem.setName("Тестовый товар");
        existingItem.setCategory("Тестовая категория");
        existingItem.setMinStock(5);
        existingItem.setActive(true);

        UpdateItemRequest request = new UpdateItemRequest("Обновленное название",
                "Обновленная категория", 15);

        ItemDetails mockedDetails = new ItemDetails();

        // 2. Настройка моков (Stubbing)
        when(itemRepository.findById(itemId)).thenReturn(Optional.of(existingItem));
        when(itemRepository.save(any(Item.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Имитируем отсутствие записи об остатках в БД
        when(stockRepository.findByItemId(itemId)).thenReturn(Optional.empty());

        // 3. Выполнение
        ItemDetails result = itemService.updateItem(itemId, request);

        // 4. Проверки
        assertNotNull(result);
        assertEquals(0, result.getCurrentStock(),
                "Текущий остаток должен быть равен 0, если запись Stock не найдена");

        // Проверяем, что данные товара обновились
        assertEquals("Обновленное название", existingItem.getName());
        assertEquals(15, existingItem.getMinStock());

        // Проверяем вызовы методов
        verify(itemRepository, times(1)).findById(itemId);
        verify(itemRepository, times(1)).save(existingItem);
        verify(stockRepository, times(1)).findByItemId(itemId);
    }
}