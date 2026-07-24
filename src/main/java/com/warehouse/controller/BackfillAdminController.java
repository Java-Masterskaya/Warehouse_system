package com.warehouse.controller;

import com.warehouse.batch.ItemBarcodeBackfillJob;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Админ-эндпоинт для ручного запуска backfill-джобы.
 * <p>
 * <strong>Безопасность:</strong> Доступ только для роли ADMIN.
 */
@Slf4j
@RestController
@RequestMapping("/admin/backfill")
@RequiredArgsConstructor
@Tag(name = "Admin: Backfill", description = "Управление ручными backfill-операциями")
@SecurityRequirement(name = "bearerAuth")
public class BackfillAdminController {

    private final ItemBarcodeBackfillJob backfillJob;

    /**
     * Запустить джобу backfill штрихкодов.
     *
     * @param batchSize размер батча (по умолчанию 500)
     * @return сводка по выполнению
     */
    @Operation(summary = "Запустить backfill штрихкодов")
    @PostMapping("/barcode")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> runBackfill(
            @RequestParam(name = "batchSize", required = false, defaultValue = "500") int batchSize) {

        log.info("Админ запросил backfill barcode с batchSize={}", batchSize);
        ItemBarcodeBackfillJob.Result result = backfillJob.run(batchSize);

        Map<String, Object> body = Map.of(
                "status", result.status().name(),
                "rowsProcessed", result.rowsProcessed(),
                "lastId", result.lastId(),
                "iterations", result.iterations()
        );

        return ResponseEntity.ok(body);
    }

    /**
     * Мягко запросить остановку работающей джобы после текущего батча.
     *
     * @return подтверждение запроса остановки
     */
    @Operation(summary = "Остановить backfill штрихкодов")
    @PostMapping("/barcode/stop")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, String>> stopBackfill() {
        backfillJob.stop();
        return ResponseEntity.ok(Map.of("message", "Запрошена остановка. Джоба завершит текущий батч и выйдет."));
    }
}
