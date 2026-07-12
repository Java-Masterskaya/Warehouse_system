package com.warehouse.repository;

import com.warehouse.entity.OutboxDltEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Репозиторий для работы с Dead Letter Table (outbox_dlt).
 *
 * outbox_dlt хранит события, которые не удалось отправить в Kafka
 * после всех попыток (max retries exceeded) или имеют битый payload.
 * Эти события отделены от активного outbox и не мешают polling.
 */
@Repository
public interface OutboxDltEventRepository extends JpaRepository<OutboxDltEvent, Long> {

    /**
     * Вставляет запись из DLT в outbox.
     * Использует CTE для атомарной вставки и получения ID.
     *
     * @param dltId ID записи в DLT
     * @return ID новой записи в outbox или null, если DLT запись не найдена
     */
    @Query(value = """
            WITH dlt_record AS (
                SELECT id, event_type, payload, error_message, retry_count, last_attempt_at
                FROM outbox_dlt
                WHERE id = :dltId
                FOR UPDATE
            )
            INSERT INTO outbox (event_type, payload, status, created_at, retry_count, last_attempt_at, error_message, sent_at)
            SELECT event_type, payload, 'PENDING', NOW(), 0, NULL, error_message, NULL
            FROM dlt_record
            RETURNING id
            """, nativeQuery = true)
    Long insertFromDltToOutboxReturningId(@Param("dltId") Long dltId);

    /**
     * Удаляет запись из DLT.
     */
    @Modifying
    @Query(value = """
            DELETE FROM outbox_dlt
            WHERE id = :dltId
            """, nativeQuery = true)
    int deleteFromDlt(@Param("dltId") Long dltId);

    /**
     * Находит все записи в DLT, отсортированные по времени создания (новые первые).
     * Использует FOR UPDATE SKIP LOCKED для защиты от параллельных репроцессов.
     *
     * @param limit максимальное количество событий для выборки
     * @return список событий в DLT
     */
    @Query(value = """
            SELECT *
            FROM outbox_dlt
            ORDER BY dlt_created_at DESC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxDltEvent> findDltEventsForReprocess(@Param("limit") int limit);

    /**
     * Считает количество записей в DLT.
     */
    long count();
}
