package com.warehouse.controller;

import com.warehouse.batch.ItemBarcodeBackfillJob;
import com.warehouse.exception.BackfillAlreadyRunningException;
import com.warehouse.web.ApiPaths;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * Админ-эндпоинт для ручного запуска backfill-джобы.
 * <p>
 * <strong>Безопасность:</strong> Доступ только для роли ADMIN.
 * <p>
 * Запуск асинхронный: {@code POST /barcode} только планирует выполнение
 * и сразу возвращает {@code 202 Accepted} — на большой таблице сам backfill может
 * идти минутами, и держать HTTP-поток/балансировщик открытым всё это время небезопасно.
 * Прогресс и результат — через {@code GET /barcode/status}.
 */
@Slf4j
@RestController
@RequestMapping({ApiPaths.V1_BACKFILL_ROOT, ApiPaths.LEGACY_BACKFILL_ROOT})
@RequiredArgsConstructor
@Validated
@Tag(name = "Admin: Backfill", description = "Управление ручными backfill-операциями")
@SecurityRequirement(name = "bearerAuth")
public class BackfillAdminController {

    private final ItemBarcodeBackfillJob backfillJob;

    /**
     * Запустить джобу backfill штрихкодов асинхронно.
     * <p>
     * Не ждёт завершения — джоба на больших таблицах может идти минутами,
     * а держать HTTP-поток и балансировщик подвешенными всё это время нельзя.
     * Прогресс/результат смотреть через {@link #backfillStatus()}.
     *
     * @param batchSize размер батча (по умолчанию 500, минимум 1)
     * @return подтверждение запуска (202 Accepted)
     */
    @Operation(summary = "Запустить backfill штрихкодов (асинхронно)")
    @PostMapping("/barcode")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> runBackfill(
            @RequestParam(name = "batchSize", required = false, defaultValue = "500")
            @Min(value = 1, message = "batchSize должен быть не меньше 1")
            int batchSize) {

        log.info("Админ запросил backfill barcode с batchSize={}", batchSize);
        if (backfillJob.isRunning()) {
            throw BackfillAlreadyRunningException.forJob("ItemBarcodeBackfillJob");
        }
        backfillJob.runAsync(batchSize)
                .exceptionally(ex -> {
                    log.warn("Backfill не запустился: {}", ex.getMessage());
                    return null;
                });

        Map<String, Object> body = Map.of(
                "status", "STARTED",
                "batchSize", batchSize,
                "message", "Backfill запущен в фоне. Прогресс - GET /api/v1/admin/backfill/barcode/status"
        );
        return ResponseEntity.accepted().body(body);
    }

    /**
     * Текущий статус backfill-джобы: идёт ли выполнение сейчас и каким был
     * результат последнего завершённого запуска.
     *
     * @return статус и (если есть) результат последнего запуска
     */
    @Operation(summary = "Статус backfill штрихкодов")
    @GetMapping("/barcode/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> backfillStatus() {
        Map<String, Object> body = new HashMap<>();
        body.put("running", backfillJob.isRunning());

        ItemBarcodeBackfillJob.Result lastResult = backfillJob.getLastResult();
        if (lastResult != null) {
            body.put("lastResult", Map.of(
                    "status", lastResult.status().name(),
                    "rowsProcessed", lastResult.rowsProcessed(),
                    "lastId", lastResult.lastId(),
                    "iterations", lastResult.iterations()
            ));
        } else {
            body.put("lastResult", null);
        }

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
