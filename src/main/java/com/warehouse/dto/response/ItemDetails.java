package com.warehouse.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class ItemDetails { // Есть изменяемые поля, оставил как класс
    private Long id;
    private String sku;
    private String name;
    private String category;
    private Integer minStock;
    private boolean active;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Integer currentStock;
}