package com.warehouse.controller;

import com.warehouse.kafka.service.DltReprocessingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/dlq/low-stock")
@RequiredArgsConstructor
@Tag(name = "DLQ Management", description = "Управление Dead Letter Queue (только ADMIN)")
@SecurityRequirement(name = "bearerAuth")
public class DltReprocessController {

    private final DltReprocessingService dltReprocessingService;

    @Operation(summary = "Асинхронная реобработка DLT", 
            description = "Запускает асинхронную обработку всех сообщений из "
                   + "DLT и отправляет их обратно в основной топик")
    @PostMapping("/reprocess")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> reprocessDltMessages() {
        dltReprocessingService.reprocessAllDltMessages();
        return ResponseEntity.accepted().build();
    }
}
