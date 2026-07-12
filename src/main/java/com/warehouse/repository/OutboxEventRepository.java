package com.warehouse.repository;

import com.warehouse.entity.OutboxEvent;
import com.warehouse.entity.OutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Репозиторий для работы с событиями outbox.
 * Обеспечивает надежную доставку событий через паттерн Outbox.
 */
@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * Находит все неотправленные события, отсортированные по времени создания.
     * Используется релаем для отправки событий в Kafka.
     * Использует FOR UPDATE SKIP LOCKED для параллельной обработки несколькими инстансами.
     * Исключает FAILED события (они требуют ручной обработки).
     *
     * @param limit максимальное количество событий для выборки
     * @return список неотправленных событий (только PENDING)
     */
    @Query(value = """
            SELECT *
            FROM outbox
            WHERE status = 'PENDING'
            ORDER BY created_at ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> findPendingEvents(@Param("limit") int limit);

    /**
     * Находит FAILED события для повторной обработки (ретраи с экспоненциальной задержкой).
     * Использует параметры для настраиваемого лимита ретраев и интервала бэкоффа.
     * Использует FOR UPDATE SKIP LOCKED для защиты от параллельных релеев.
     * Проверяет, что maxRetries ретраев еще не исчерпан.
     * Проверка экспоненциального бэкоффа выполняется в Java (OutboxEventRelay.processSingleEvent).
     *
     * @param limit        максимальное количество событий для выборки
     * @param maxRetries   максимальное количество попыток (из конфигурации)
     * @param backoffMs    базовый интервал бэкоффа в миллисекундах (из конфигурации)
     * @return список FAILED событий для ретрая
     */
    @Query(value = """
            SELECT *
            FROM outbox
            WHERE status = 'FAILED'
              AND retry_count < :maxRetries
            ORDER BY created_at ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> findFailedEventsForRetry(@Param("limit") int limit,
                                                @Param("maxRetries") int maxRetries,
                                                @Param("backoffMs") long backoffMs);

    /**
     * Считает количество событий по статусу (для метрик).
     *
     * @param status статус
     * @return количество событий
     */
    long countByStatus(OutboxStatus status);

    /**
     * Обновляет статус события на SENT после успешной отправки в Kafka.
     * Сбрасывает retry_count и other retry-related поля.
     *
     * @param id     ID события
     * @param sentAt время отправки
     * @return количество обновленных строк
     */
    @Modifying
    @Query("""
            UPDATE OutboxEvent e
            SET e.status = com.warehouse.entity.OutboxStatus.SENT,
                e.sentAt = :sentAt,
                e.retryCount = 0,
                e.lastAttemptAt = NULL,
                e.errorMessage = NULL
            WHERE e.id = :id
              AND e.status = com.warehouse.entity.OutboxStatus.PENDING
            """)
    int updateToSent(@Param("id") Long id, @Param("sentAt") LocalDateTime sentAt);

    /**
     * Помечает событие как FAILED.
     *
     * @param id            ID события
     * @param errorMessage  сообщение об ошибке
     * @param retryCount    текущее количество ретраев
     * @param lastAttemptAt время последней попытки
     * @return количество обновленных строк
     */
    @Modifying
    @Query("""
            UPDATE OutboxEvent e
            SET e.status = com.warehouse.entity.OutboxStatus.FAILED,
                e.errorMessage = :errorMessage,
                e.retryCount = :retryCount,
                e.lastAttemptAt = :lastAttemptAt
            WHERE e.id = :id
              AND e.status = com.warehouse.entity.OutboxStatus.PENDING
            """)
    int updateToFailed(@Param("id") Long id, @Param("errorMessage") String errorMessage,
                       @Param("retryCount") int retryCount, @Param("lastAttemptAt") LocalDateTime lastAttemptAt);

    /**
     * Перемещает событие из outbox в DLT (Dead Letter Table) после превышения лимита ретраев.
     * Сначала вставляет в outbox_dlt, затем удаляет из outbox и обновляет статус.
     *
     * @param id            ID события
     * @param errorMessage  сообщение об ошибке
     * @param retryCount    количество попыток
     * @param lastAttemptAt время последней попытки
     * @return количество перемещенных строк (1 если успешно, 0 если не найдено)
     */
    @Modifying
    @Query(value = """
            -- Вставляем в DLT
            INSERT INTO outbox_dlt (
                original_outbox_id,
                event_type,
                payload,
                error_message,
                retry_count,
                last_attempt_at,
                permanent_failure_reason
            )
            SELECT
                o.id,
                o.event_type,
                o.payload,
                :errorMessage,
                :retryCount,
                :lastAttemptAt,
                'MAX_RETRIES_EXCEEDED'
            FROM outbox o
            WHERE o.id = :id
            RETURNING id;
            """, nativeQuery = true)
    Long insertIntoDlt(@Param("id") Long id, @Param("errorMessage") String errorMessage,
                       @Param("retryCount") int retryCount, @Param("lastAttemptAt") LocalDateTime lastAttemptAt);

    /**
     * Удаляет событие из outbox после его перемещения в DLT.
     *
     * @param id ID события
     * @return количество удаленных строк
     */
    @Modifying
    @Query(value = """
            DELETE FROM outbox
            WHERE id = :id
            """, nativeQuery = true)
    int deleteFromOutbox(@Param("id") Long id);

    /**
     * Обновляет статус события на PERMANENT_FAILURE.
     * Используется после успешного перемещения в outbox_dlt.
     *
     * @param id ID события
     * @return количество обновленных строк
     */
    @Modifying
    @Query("""
            UPDATE OutboxEvent e
            SET e.status = com.warehouse.entity.OutboxStatus.PERMANENT_FAILURE
            WHERE e.id = :id
            """)
    int updateToPermanentFailure(@Param("id") Long id);
}
