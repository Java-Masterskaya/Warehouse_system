package com.warehouse.dto.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Unit-тест для DTO OutboxLowStockAlertEvent.
 * Проверяет сериализацию и десериализацию в JSON.
 */
class OutboxLowStockAlertEventTest {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final Long ITEM_ID = 1L;
    private static final String SKU = "SKU-001";
    private static final String ITEM_NAME = "Тестовый товар";
    private static final int CURRENT_STOCK = 5;
    private static final int MIN_STOCK = 10;
    private static final String TRIGGERED_BY = "admin";
    private static final LocalDateTime TRIGGERED_AT = LocalDateTime.of(2026, 7, 7, 12, 0);

    /**
     * Событие создаётся через конструктор.
     */
    @Test
    void constructorCreatesEvent() {
        OutboxLowStockAlertEvent event = new OutboxLowStockAlertEvent(
                ITEM_ID, SKU, ITEM_NAME, CURRENT_STOCK, MIN_STOCK, TRIGGERED_BY, TRIGGERED_AT
        );

        assertEquals(ITEM_ID, event.itemId());
        assertEquals(SKU, event.sku());
        assertEquals(ITEM_NAME, event.itemName());
        assertEquals(CURRENT_STOCK, event.currentStock());
        assertEquals(MIN_STOCK, event.minStock());
        assertEquals(TRIGGERED_BY, event.triggeredBy());
        assertEquals(TRIGGERED_AT, event.triggeredAt());
    }

    /**
     * Событие сериализуется в JSON.
     */
    @Test
    void serializeToJson() throws Exception {
        OutboxLowStockAlertEvent event = new OutboxLowStockAlertEvent(
                ITEM_ID, SKU, ITEM_NAME, CURRENT_STOCK, MIN_STOCK, TRIGGERED_BY, TRIGGERED_AT
        );

        String json = objectMapper.writeValueAsString(event);

        assertNotNull(json);
        // LocalDateTime сериализуется как массив [year,month,day,hour,minute]
        String expectedJson = "{" +
                "\"itemId\":" + ITEM_ID + "," +
                "\"sku\":\"" + SKU + "\"," +
                "\"itemName\":\"" + ITEM_NAME + "\"," +
                "\"currentStock\":" + CURRENT_STOCK + "," +
                "\"minStock\":" + MIN_STOCK + "," +
                "\"triggeredBy\":\"" + TRIGGERED_BY + "\"," +
                "\"triggeredAt\":[2026,7,7,12,0]" +
                "}";
        assertEquals(expectedJson, json);
    }

    /**
     * Событие десериализуется из JSON.
     */
    @Test
    void deserializeFromJson() throws Exception {
        String json = "{" +
                "\"itemId\":" + ITEM_ID + "," +
                "\"sku\":\"" + SKU + "\"," +
                "\"itemName\":\"" + ITEM_NAME + "\"," +
                "\"currentStock\":" + CURRENT_STOCK + "," +
                "\"minStock\":" + MIN_STOCK + "," +
                "\"triggeredBy\":\"" + TRIGGERED_BY + "\"," +
                "\"triggeredAt\":\"" + TRIGGERED_AT + "\"" +
                "}";

        OutboxLowStockAlertEvent event = objectMapper.readValue(json, OutboxLowStockAlertEvent.class);

        assertEquals(ITEM_ID, event.itemId());
        assertEquals(SKU, event.sku());
        assertEquals(ITEM_NAME, event.itemName());
        assertEquals(CURRENT_STOCK, event.currentStock());
        assertEquals(MIN_STOCK, event.minStock());
        assertEquals(TRIGGERED_BY, event.triggeredBy());
        assertEquals(TRIGGERED_AT, event.triggeredAt());
    }

    /**
     * Сериализация/десериализация идемпотентна.
     */
    @Test
    void serializeDeserializeIsIdempotent() throws Exception {
        OutboxLowStockAlertEvent original = new OutboxLowStockAlertEvent(
                ITEM_ID, SKU, ITEM_NAME, CURRENT_STOCK, MIN_STOCK, TRIGGERED_BY, TRIGGERED_AT
        );

        String json = objectMapper.writeValueAsString(original);
        OutboxLowStockAlertEvent restored = objectMapper.readValue(json, OutboxLowStockAlertEvent.class);

        assertEquals(original.itemId(), restored.itemId());
        assertEquals(original.sku(), restored.sku());
        assertEquals(original.itemName(), restored.itemName());
        assertEquals(original.currentStock(), restored.currentStock());
        assertEquals(original.minStock(), restored.minStock());
        assertEquals(original.triggeredBy(), restored.triggeredBy());
        assertEquals(original.triggeredAt(), restored.triggeredAt());
    }
}
