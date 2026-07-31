package com.warehouse.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
public class AuditContext {

    private final ObjectMapper objectMapper;

    private final ThreadLocal<JsonNode> oldValue = new ThreadLocal<>();
    private final ThreadLocal<JsonNode> newValue = new ThreadLocal<>();
    private final ThreadLocal<Long> entityId = new ThreadLocal<>();

    public void setOldValue(Object value) {
        oldValue.set(objectMapper.valueToTree(value));
    }

    public void setNewValue(Object value) {
        newValue.set(objectMapper.valueToTree(value));
    }

    public JsonNode getOldValue() {
        return oldValue.get();
    }

    public JsonNode getNewValue() {
        return newValue.get();
    }

    public void setEntityId(Long id) {
        entityId.set(id);
    }

    public Long getEntityId() {
        return entityId.get();
    }

    public void clear() {
        oldValue.remove();
        newValue.remove();
        entityId.remove();
    }
}
