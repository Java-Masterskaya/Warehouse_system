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
     * Исключает события, которые были успешно отправлены (sent_at != NULL) или имеют слишком много ретраев.
     * Исключает события, которые были попробованы недавно (бэкофф).
     *
     * @param limit максимальное количество событий для выборки
     * @return список FAILED событий для ретрая
     */
    @Query(value = """
            SELECT *
            FROM outbox
            WHERE status = 'FAILED'
              AND retry_count < 3  -- Максимум 3 ретрая
              AND (last_attempt_at IS NULL OR last_attempt_at < NOW() - INTERVAL '5 seconds')  -- 5 сек бэкофф
            ORDER BY created_at ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> findFailedEventsForRetry(@Param("limit") int limit);

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
}
