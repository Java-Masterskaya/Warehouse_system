package com.warehouse.service;

import com.warehouse.dto.request.item.CreateItemRequest;
import com.warehouse.dto.response.item.ItemResponse;
import com.warehouse.entity.Item;
import com.warehouse.entity.Stock;
import com.warehouse.exception.DuplicateSkuException;
import com.warehouse.mapper.ItemMapper;
import com.warehouse.repository.ItemRepository;
import com.warehouse.repository.StockRepository;
import com.warehouse.service.item.ItemServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Unit-тест сервиса — Spring-контекст не поднимается, репозитории заменены моками
@ExtendWith(MockitoExtension.class)
class ItemServiceTest {

    @Mock
    private ItemRepository itemRepository;

    @Mock
    private StockRepository stockRepository;

    @Mock
    private ItemMapper itemMapper;

    @InjectMocks
    private ItemServiceImpl itemService;

    @Test
    void createItemSuccess() {
        CreateItemRequest request = new CreateItemRequest("SKU-001", "Ноутбук", "Электроника", 5);

        Item item = new Item();
        item.setId(1L);
        item.setSku("SKU-001");

        ItemResponse expected = new ItemResponse(1L, "SKU-001", "Ноутбук", "Электроника", 5, true, LocalDateTime.now());

        when(itemRepository.existsBySku("SKU-001")).thenReturn(false);
        when(itemMapper.toEntity(request)).thenReturn(item);
        when(itemMapper.toResponse(item)).thenReturn(expected);

        ItemResponse result = itemService.createItem(request);

        assertThat(result).isEqualTo(expected);
        verify(itemRepository).save(item);
        verify(stockRepository).save(any(Stock.class));
    }

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
}
