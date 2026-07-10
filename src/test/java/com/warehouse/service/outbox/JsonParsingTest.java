package com.warehouse.service.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.warehouse.dto.event.LowStockAlertEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class JsonParsingTest {

    @Test
    void testMalformedJsonThrowsException() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        
        // Тестируем различные плохие JSON
        String[] badJsons = {
            "{invalid json here, missing quotes and proper structure}",
            "{unterminated string field: \"value\"",
            "{\"itemId\": 1, missing comma, \"field\": \"value\"}"
        };
        
        for (String json : badJsons) {
            System.out.println("Trying: " + json.substring(0, Math.min(50, json.length())) + "...");
            try {
                LowStockAlertEvent event = mapper.readValue(json, LowStockAlertEvent.class);
                System.out.println("  SUCCESS (unexpected): " + event);
            } catch (Exception e) {
                System.out.println("  EXCEPTION: " + e.getClass().getSimpleName() + " - " + e.getMessage().split("\n")[0]);
            }
        }
    }
}
