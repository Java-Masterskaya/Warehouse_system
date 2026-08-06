package com.warehouse.exception;

/**
 * Попытка запустить backfill-джобу, пока уже выполняется другой её запуск.
 * <p>
 * Без этой проверки второй {@code POST /api/v1/admin/backfill/barcode} сбрасывал бы
 * флаг остановки первого запуска и портил метрики прогресса.
 */
public class BackfillAlreadyRunningException extends RuntimeException {
    public BackfillAlreadyRunningException(String message) {
        super(message);
    }

    public static BackfillAlreadyRunningException forJob(String jobName) {
        return new BackfillAlreadyRunningException(
                "Джоба '" + jobName + "' уже выполняется. Дождитесь завершения или остановите её через /stop.");
    }
}
