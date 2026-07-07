package com.warehouse.service.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.warehouse.dto.event.LowStockAlertEvent;
import com.warehouse.entity.OutboxEvent;
import com.warehouse.entity.OutboxStatus;
import com.warehouse.exception.OutboxException;
import com.warehouse.repository.OutboxEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-тест для сервиса OutboxService.
 */
@ExtendWith(MockitoExtension.class)
class OutboxServiceTest {

    private static final Long EVENT_ID = 1L;
    private static final Long ITEM_ID = 10L;
    private static final String SKU = "SKU-001";
    private static final String ITEM_NAME = "Тестовый товар";
    private static final int CURRENT_STOCK = 5;
    private static final int MIN_STOCK = 10;
    private static final String TRIGGERED_BY = "admin";
    private static final LocalDateTime TRIGGERED_AT = LocalDateTime.now();

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private OutboxService outboxService;

    @Captor
    private ArgumentCaptor<OutboxEvent> eventCaptor;

    /**
     * saveLowStockAlertEvent сохраняет событие в outbox с правильными данными.
     */
    @Test
    void saveLowStockAlertEventSavesToOutbox() throws JsonProcessingException {
        LowStockAlertEvent event = createEvent();

        String expectedPayload = "{\"itemId\":10,\"sku\":\"SKU-001\",\"itemName\":\"Тестовый товар\",\"currentStock\":5,\"minStock\":10,\"triggeredBy\":\"admin\",\"triggeredAt\":\"" + TRIGGERED_AT + "\"}";
        when(objectMapper.writeValueAsString(any())).thenReturn(expectedPayload);
        when(outboxEventRepository.save(any(OutboxEvent.class)))
                .thenAnswer(invocation -> {
                    OutboxEvent saved = invocation.getArgument(0);
                    saved.setId(EVENT_ID);
                    return saved;
                });

        outboxService.saveLowStockAlertEvent(event);

        verify(outboxEventRepository).save(eventCaptor.capture());
        OutboxEvent savedEvent = eventCaptor.getValue();

        assertNotNull(savedEvent.getId());
        assertEquals("LowStockAlert", savedEvent.getEventType());
        assertEquals(expectedPayload, savedEvent.getPayload());
        assertEquals(OutboxStatus.PENDING, savedEvent.getStatus());
        assertNotNull(savedEvent.getCreatedAt());
    }

    /**
     * saveLowStockAlertEvent выбрасывает исключение при ошибке сериализации.
     */
    @Test
    void saveLowStockAlertEventThrowsExceptionOnJsonError() throws JsonProcessingException {
        LowStockAlertEvent event = createEvent();
        when(objectMapper.writeValueAsString(any())).thenThrow(new RuntimeException("JSON error"));

        OutboxException ex = assertThrows(OutboxException.class,
                () -> outboxService.saveLowStockAlertEvent(event));

        assertEquals("Failed to save event to outbox", ex.getMessage());
        verify(outboxEventRepository, never()).save(any());
    }

    /**
     * saveLowStockAlertEvent корректно устанавливает все поля из события.
     */
    @Test
    void saveLowStockAlertEventSetsAllFields() throws JsonProcessingException {
        LowStockAlertEvent event = createEvent();

        String expectedPayload = "{\"itemId\":" + ITEM_ID + ",\"sku\":\"" + SKU + "\",\"itemName\":\"" + ITEM_NAME + "\",\"currentStock\":" + CURRENT_STOCK + ",\"minStock\":" + MIN_STOCK + ",\"triggeredBy\":\"" + TRIGGERED_BY + "\",\"triggeredAt\":\"" + TRIGGERED_AT + "\"}";
        when(objectMapper.writeValueAsString(any())).thenReturn(expectedPayload);
        when(outboxEventRepository.save(any(OutboxEvent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        outboxService.saveLowStockAlertEvent(event);

        verify(outboxEventRepository).save(eventCaptor.capture());
        OutboxEvent savedEvent = eventCaptor.getValue();

        assertEquals("LowStockAlert", savedEvent.getEventType());
        assertEquals(expectedPayload, savedEvent.getPayload());
        assertEquals(OutboxStatus.PENDING, savedEvent.getStatus());
    }

    /**
     * Вспомогательный метод для создания LowStockAlertEvent.
     */
    private LowStockAlertEvent createEvent() {
        return new LowStockAlertEvent(
                ITEM_ID, SKU, ITEM_NAME, CURRENT_STOCK, MIN_STOCK, TRIGGERED_BY, TRIGGERED_AT
        );
    }
}
