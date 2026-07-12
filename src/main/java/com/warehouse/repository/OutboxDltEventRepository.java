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
     * Находит все записи в DLT, отсортированные по времени создания (новые первые).
     */
    List<OutboxDltEvent> findAllByOrderByDltCreatedAtDesc();

    /**
     * Считает количество записей в DLT.
     */
    long count();

    /**
     * Перемещает событие из DLT обратно в outbox для повторной обработки.
     *
     * Атомарно (в одной транзакции):
     * 1. Читает запись из outbox_dlt по id
     * 2. Вставляет новую запись в outbox со статусом PENDING
     * 3. Удаляет запись из outbox_dlt
     * 4. Возвращает ID новой записи в outbox
     *
     * @param dltId ID записи в DLT
     * @return ID новой записи в outbox, или null если DLT запись не найдена
     */
    @Modifying
    @Query(value = """
            WITH dlt_record AS (
                SELECT original_outbox_id, event_type, payload
                FROM outbox_dlt
                WHERE id = :dltId
            ),
            inserted AS (
                INSERT INTO outbox (event_type, payload, status, created_at, retry_count, last_attempt_at, error_message, sent_at)
                SELECT event_type, payload, 'PENDING', NOW(), 0, NULL, NULL, NULL
                FROM dlt_record
                RETURNING id
            )
            DELETE FROM outbox_dlt
            WHERE id = :dltId
            RETURNING (SELECT id FROM inserted)
            """, nativeQuery = true)
    Long restoreToOutbox(@Param("dltId") Long dltId);
}
