package com.warehouse.repository;

import com.warehouse.entity.OutboxEvent;
import com.warehouse.entity.OutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

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
     *
     * @param limit максимальное количество событий для выборки
     * @return список неотправленных событий
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
     * Находит событие по ID.
     *
     * @param id ID события
     * @return событие или Optional.empty(), если не найдено
     */
    @Query("""
            SELECT e
            FROM OutboxEvent e
            WHERE e.id = :id
            """)
    Optional<OutboxEvent> findById(@Param("id") Long id);

    /**
     * Обновляет статус события на SENT после успешной отправки в Kafka.
     *
     * @param id ID события
     * @param sentAt время отправки
     * @return количество обновленных строк
     */
    @Modifying
    @Transactional
    @Query("""
            UPDATE OutboxEvent e
            SET e.status = com.warehouse.entity.OutboxStatus.SENT,
                e.sentAt = :sentAt
            WHERE e.id = :id
            """)
    int updateToSent(@Param("id") Long id, @Param("sentAt") LocalDateTime sentAt);

    /**
     * Обновляет статус события на FAILED при ошибке отправки.
     *
     * @param id ID события
     * @param errorMessage сообщение об ошибке
     * @return количество обновленных строк
     */
    @Modifying
    @Transactional
    @Query("""
            UPDATE OutboxEvent e
            SET e.status = com.warehouse.entity.OutboxStatus.FAILED,
                e.errorMessage = :errorMessage
            WHERE e.id = :id
            """)
    int updateToFailed(@Param("id") Long id, @Param("errorMessage") String errorMessage);

    /**
     * Обновляет статус события на PENDING для повторной попытки.
     *
     * @param id ID события
     * @return количество обновленных строк
     */
    @Modifying
    @Transactional
    @Query("""
            UPDATE OutboxEvent e
            SET e.status = com.warehouse.entity.OutboxStatus.PENDING,
                e.errorMessage = NULL
            WHERE e.id = :id
            """)
    int resetToPending(@Param("id") Long id);
}
