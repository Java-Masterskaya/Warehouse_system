package com.warehouse.entity;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit-тест для сущности OutboxEvent.
 */
class OutboxEventTest {

    private static final String EVENT_TYPE = "LowStockAlert";
    private static final String PAYLOAD = "{\"itemId\":1,\"currentStock\":5,\"minStock\":10}";
    private static final LocalDateTime CREATED_AT = LocalDateTime.now();

    /**
     * Сущность создаётся через builder.
     */
    @Test
    void buildEventSuccessfully() {
        OutboxEvent event = OutboxEvent.builder()
                .eventType(EVENT_TYPE)
                .payload(PAYLOAD)
                .status(OutboxStatus.PENDING)
                .createdAt(CREATED_AT)
                .build();

        assertNotNull(event);
        assertNull(event.getId()); // ID генерируется БД
        assertEquals(EVENT_TYPE, event.getEventType());
        assertEquals(PAYLOAD, event.getPayload());
        assertEquals(OutboxStatus.PENDING, event.getStatus());
        assertEquals(CREATED_AT, event.getCreatedAt());
        assertNull(event.getSentAt());
        assertNull(event.getErrorMessage());
    }

    /**
     * Все поля сущности могут быть изменены через сеттеры.
     */
    @Test
    void settersWorkCorrectly() {
        OutboxEvent event = new OutboxEvent();
        event.setId(1L);
        event.setEventType("LowStockAlert");
        event.setPayload("{\"itemId\":1,\"currentStock\":5,\"minStock\":10}");
        event.setStatus(OutboxStatus.SENT);
        event.setCreatedAt(CREATED_AT);
        event.setSentAt(CREATED_AT.plusSeconds(1));
        event.setErrorMessage(null);

        assertEquals(1L, event.getId());
        assertEquals("LowStockAlert", event.getEventType());
        assertEquals("{\"itemId\":1,\"currentStock\":5,\"minStock\":10}", event.getPayload());
        assertEquals(OutboxStatus.SENT, event.getStatus());
        assertEquals(CREATED_AT, event.getCreatedAt());
        assertEquals(CREATED_AT.plusSeconds(1), event.getSentAt());
        assertNull(event.getErrorMessage());
    }

    /**
     * Конструктор с аргументами работает корректно.
     */
    @Test
    void constructorWithArgsWorks() {
        OutboxEvent event = new OutboxEvent(
                1L,
                "LowStockAlert",
                "{\"itemId\":1,\"currentStock\":5,\"minStock\":10}",
                OutboxStatus.PENDING,
                CREATED_AT,
                null,
                null
        );

        assertEquals(1L, event.getId());
        assertEquals("LowStockAlert", event.getEventType());
        assertEquals("{\"itemId\":1,\"currentStock\":5,\"minStock\":10}", event.getPayload());
        assertEquals(OutboxStatus.PENDING, event.getStatus());
        assertEquals(CREATED_AT, event.getCreatedAt());
        assertNull(event.getSentAt());
        assertNull(event.getErrorMessage());
    }

    /**
     * Статусы перечисления OutboxStatus доступны.
     */
    @Test
    void outboxStatusValues() {
        assertEquals(3, OutboxStatus.values().length);
        assertEquals("PENDING", OutboxStatus.PENDING.name());
        assertEquals("SENT", OutboxStatus.SENT.name());
        assertEquals("FAILED", OutboxStatus.FAILED.name());
    }

    /**
     * Статус PENDING используется по умолчанию при создании.
     */
    @Test
    void defaultStatusIsPending() {
        OutboxEvent event = OutboxEvent.builder()
                .eventType(EVENT_TYPE)
                .payload(PAYLOAD)
                .createdAt(CREATED_AT)
                .build();

        // Статус по умолчанию - PENDING (установлен в конструкторе бuilder)
        assertEquals(OutboxStatus.PENDING, event.getStatus());
    }
}
