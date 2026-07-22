package com.warehouse.repository;

import com.warehouse.entity.OutboxDltEvent;
import org.springframework.data.jpa.repository.JpaRepository;
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
     * Вставляет запись из DLT в outbox и удаляет из DLT в одной атомарной операции.
     * Использует CTE с FOR UPDATE для защиты от конкурентных репроцессов.
     * Гарантирует, что событие не дублируется при краше между операциями.
     * Возвращает null, если DLT запись не найдена или произошла ошибка.
     *
     * @param dltId ID записи в DLT
     * @return ID новой записи в outbox или null, если DLT запись не найдена
     */
    @Query(value = """
            WITH dlt_record AS (
                SELECT id, original_outbox_id, event_type, payload, error_message, retry_count, last_attempt_at
                FROM outbox_dlt
                WHERE id = :dltId
                FOR UPDATE
            ),
            inserted_outbox AS (
                INSERT INTO outbox (event_type, payload, status, created_at, retry_count, 
                            last_attempt_at, error_message, sent_at)
                SELECT event_type, payload, 'PENDING', NOW(), 0, NULL, error_message, NULL
                FROM dlt_record
                RETURNING id
            ),
            deleted_dlt AS (
                DELETE FROM outbox_dlt
                WHERE id = :dltId
                RETURNING id
            )
            SELECT (
                CASE 
                    WHEN EXISTS (SELECT 1 FROM inserted_outbox)
                         AND EXISTS (SELECT 1 FROM deleted_dlt)
                    THEN (SELECT id FROM inserted_outbox)
                    ELSE NULL
                END
            ) AS id
            """, nativeQuery = true)
    Long insertFromDltToOutboxAndDeleteFromDlt(@Param("dltId") Long dltId);

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
     *
     * @return количество записей в DLT
     */
    long count();
}
